package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sign and notarize macOS installer packages ({@code .pkg}, {@code .dmg}).
 *
 * <p>This goal automates the Apple notarization workflow for installer
 * artifacts produced by JReleaser's jpackage assembler:
 * <ol>
 *   <li>Locate {@code .pkg} / {@code .dmg} files in the jpackage output directory</li>
 *   <li>Submit each artifact to Apple's notary service via {@code xcrun notarytool}</li>
 *   <li>Staple the notarization ticket to the artifact via {@code xcrun stapler}</li>
 *   <li>Verify the result with {@code spctl --assess}</li>
 * </ol>
 *
 * <p>On non-macOS platforms, the goal skips silently — no profile
 * activation or conditional configuration required.
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>A "Developer ID Installer" certificate in the login keychain</li>
 *   <li>A notarytool keychain profile configured via:
 *       <pre>xcrun notarytool store-credentials "notarytool" \
 *     --apple-id "your@email.com" \
 *     --team-id "YOURTEAMID" \
 *     --password "app-specific-password"</pre></li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * mvn ike:notarize
 * </pre>
 *
 * <p>Or bound to the {@code verify} phase in a POM:
 * <pre>{@code
 * <plugin>
 *     <groupId>network.ike.tooling</groupId>
 *     <artifactId>ike-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <id>notarize-installer</id>
 *             <goals><goal>notarize</goal></goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 *
 * @see <a href="https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution">
 *      Apple: Notarizing macOS Software Before Distribution</a>
 */
@Mojo(name = IkeGoal.NAME_NOTARIZE,
      defaultPhase = "verify",
      projectRequired = true)
public class NotarizeMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public NotarizeMojo() {}

    /**
     * Directory containing jpackage output artifacts.
     * Defaults to the JReleaser jpackage output location.
     */
    @Parameter(property = "notarize.artifactDir",
               defaultValue = "${project.build.directory}/jreleaser/assemble/komet/jpackage")
    private File artifactDir;

    /**
     * Keychain profile name for {@code xcrun notarytool}.
     * Created via {@code xcrun notarytool store-credentials}.
     */
    @Parameter(property = "notarize.keychainProfile",
               defaultValue = "notarytool")
    private String keychainProfile;

    /**
     * Skip notarization entirely.
     */
    @Parameter(property = "notarize.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Timeout in minutes for notarization submission.
     * Apple typically processes in 5–15 minutes but can take longer.
     */
    @Parameter(property = "notarize.timeoutMinutes", defaultValue = "30")
    private int timeoutMinutes;

    /**
     * Maximum number of submit attempts before failing on transient
     * network errors. A value of 1 disables retry entirely; the previous
     * behavior. Genuine notarization rejections (Apple returns
     * {@code status: Invalid}) never trigger a retry — they fail immediately.
     */
    @Parameter(property = "notarize.maxAttempts", defaultValue = "3")
    private int maxAttempts;

    /**
     * Comma-separated list of seconds to wait before each retry attempt.
     * The first value is the wait before attempt 2, the second before
     * attempt 3, and so on. If shorter than {@code maxAttempts - 1} the
     * last entry is reused.
     */
    @Parameter(property = "notarize.retryBackoffSeconds", defaultValue = "30,120")
    private String retryBackoffSeconds;

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Notarization skipped (notarize.skip=true)");
            return;
        }

        if (!isMacOS()) {
            getLog().info("Notarization skipped — not running on macOS");
            return;
        }

        List<Path> artifacts = findInstallerArtifacts();
        if (artifacts.isEmpty()) {
            getLog().warn("No .pkg or .dmg files found in " + artifactDir);
            return;
        }

        getLog().info("");
        getLog().info("Apple Notarization");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Artifact directory:  " + artifactDir);
        getLog().info("  Keychain profile:    " + keychainProfile);
        getLog().info("  Timeout:             " + timeoutMinutes + " minutes");
        getLog().info("  Artifacts found:     " + artifacts.size());

        for (Path artifact : artifacts) {
            getLog().info("");
            notarize(artifact);
        }

        getLog().info("");
        getLog().info("Notarization complete — " + artifacts.size() + " artifact(s) processed");
        getLog().info("");
    }

    /**
     * Submit, staple, and verify a single installer artifact.
     *
     * @param artifact path to the .pkg or .dmg file
     * @throws MojoException if any step fails
     */
    private void notarize(Path artifact) throws MojoException {
        String fileName = artifact.getFileName().toString();
        File workDir = artifact.getParent().toFile();

        SubmitResult submission = submitWithRetry(workDir, artifact, fileName);

        if (submission.status() == null || !submission.status().equalsIgnoreCase("Accepted")) {
            getLog().error("Notarization REJECTED for " + fileName
                    + " — status: " + (submission.status() != null ? submission.status() : "unknown"));

            if (submission.submissionId() != null) {
                getLog().error("Fetching rejection details (id: " + submission.submissionId() + ")...");
                try {
                    ReleaseSupport.exec(workDir, getLog(),
                            "xcrun", "notarytool", "log",
                            submission.submissionId(),
                            "--keychain-profile", keychainProfile);
                } catch (Exception e) {
                    getLog().warn("Could not fetch notarization log: " + e.getMessage());
                }
            }

            throw new MojoException(
                    "Notarization failed for " + fileName
                            + " — status: " + (submission.status() != null ? submission.status() : "unknown")
                            + (submission.submissionId() != null ? " (id: " + submission.submissionId() + ")" : ""));
        }

        getLog().info("Notarization accepted — stapling ticket");

        getLog().info("Stapling ticket: " + fileName);
        ReleaseSupport.exec(workDir, getLog(),
                "xcrun", "stapler", "staple",
                artifact.toString());

        getLog().info("Verifying: " + fileName);
        String assessType = fileName.endsWith(".pkg") ? "install" : "open";
        ReleaseSupport.exec(workDir, getLog(),
                "spctl", "--assess",
                "--type", assessType,
                "--verbose=4",
                artifact.toString());

        getLog().info("Notarized and verified: " + fileName);
    }

    /**
     * Submit the artifact (or resume an in-flight submission) with bounded
     * retry on transient network errors. Genuine notarization rejections
     * (exit 0 with non-Accepted status) return on the first attempt and are
     * dispatched by the caller.
     *
     * <p>Three failure modes are distinguished:
     * <ul>
     *   <li>Non-zero exit, no submission id captured, output matches
     *       {@link #TRANSIENT_NETWORK} — fresh {@code submit} retry.</li>
     *   <li>Non-zero exit, submission id <em>was</em> captured before the
     *       failure — switch to {@code notarytool info <id> --wait} so we
     *       don't waste an upload slot resubmitting.</li>
     *   <li>Non-zero exit, no transient match — fail immediately.</li>
     * </ul>
     *
     * @param workDir   working directory for the subprocess
     * @param artifact  path to the .pkg or .dmg
     * @param fileName  bare file name (for log messages)
     * @return the resolved submission result
     * @throws MojoException if all attempts fail
     */
    private SubmitResult submitWithRetry(File workDir, Path artifact, String fileName)
            throws MojoException {
        List<Integer> backoff = parseBackoffSchedule(retryBackoffSeconds);
        int attempts = Math.max(1, maxAttempts);
        String knownSubmissionId = null;
        ExecOutcome lastOutcome = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                int waitSeconds = backoffWait(backoff, attempt);
                getLog().warn("Notarize attempt " + attempt + "/" + attempts
                        + " in " + waitSeconds + "s "
                        + "(previous attempt: " + summarizeOutcome(lastOutcome) + ")");
                sleepSeconds(waitSeconds);
            }

            String[] command;
            if (knownSubmissionId != null) {
                getLog().info("Resuming wait on submission " + knownSubmissionId
                        + " (attempt " + attempt + "/" + attempts + ")");
                command = new String[] {
                        "xcrun", "notarytool", "info",
                        knownSubmissionId,
                        "--keychain-profile", keychainProfile,
                        "--wait"
                };
            } else {
                getLog().info("Submitting for notarization: " + fileName
                        + " (attempt " + attempt + "/" + attempts + ")");
                command = new String[] {
                        "xcrun", "notarytool", "submit",
                        artifact.toString(),
                        "--keychain-profile", keychainProfile,
                        "--timeout", timeoutMinutes + "m",
                        "--wait"
                };
            }

            lastOutcome = runCapturing(workDir, getLog(), command);

            String idFromThisAttempt = extractSubmissionId(lastOutcome.output());
            if (idFromThisAttempt != null) {
                knownSubmissionId = idFromThisAttempt;
            }

            if (lastOutcome.exitCode() == 0) {
                return new SubmitResult(extractStatus(lastOutcome.output()),
                        knownSubmissionId,
                        lastOutcome.output());
            }

            if (!isTransientNetworkError(lastOutcome.output())) {
                throw new MojoException(
                        "Notarization command failed (exit " + lastOutcome.exitCode()
                                + ", non-transient): " + String.join(" ", command)
                                + (lastOutcome.output().isEmpty()
                                        ? ""
                                        : "\nOutput:\n" + lastOutcome.output().trim()));
            }

            getLog().warn("Transient network error on attempt " + attempt + "/"
                    + attempts + " — "
                    + (knownSubmissionId != null
                            ? "will resume wait on submission " + knownSubmissionId
                            : "will resubmit"));
        }

        throw new MojoException(
                "Notarization exhausted " + attempts + " attempt(s) for " + fileName
                        + " — last error: " + summarizeOutcome(lastOutcome)
                        + (knownSubmissionId != null
                                ? " (submission id: " + knownSubmissionId + ")"
                                : ""));
    }

    /**
     * Resolved outcome of a notarization {@code submit} or {@code info} call.
     *
     * @param status         terminal status as reported by Apple ({@code Accepted},
     *                       {@code Invalid}, {@code Rejected}), or {@code null} if
     *                       not parseable
     * @param submissionId   Apple-assigned submission id, or {@code null} if no id
     *                       was ever captured (e.g. transport error before upload)
     * @param fullOutput     captured stdout+stderr from the final attempt
     */
    private record SubmitResult(String status, String submissionId, String fullOutput) {}

    /**
     * Captured result of a single subprocess invocation.
     *
     * @param exitCode process exit code; {@code -1} for runner-side I/O failure
     * @param output   captured stdout+stderr (combined)
     */
    private record ExecOutcome(int exitCode, String output) {}

    /**
     * Transport-layer error patterns from {@code xcrun notarytool}. Matching
     * any of these on a non-zero exit triggers a retry; every other non-zero
     * exit is treated as a hard failure.
     *
     * <p>Intentionally narrow — only well-known network conditions, never
     * keychain or signing errors.
     */
    private static final Pattern TRANSIENT_NETWORK = Pattern.compile(
            "NSURLErrorDomain Code=-1001"
            + "|kCFErrorDomainCFNetwork"
            + "|Could not connect to the notary service"
            + "|connection was lost"
            + "|The Internet connection appears to be offline"
            + "|HTTPError\\(statusCode: nil",
            Pattern.CASE_INSENSITIVE);

    /**
     * Test whether captured output contains a recognized transient network
     * error pattern.
     *
     * @param output captured xcrun output
     * @return {@code true} if the output matches a known transient pattern
     */
    static boolean isTransientNetworkError(String output) {
        return output != null && TRANSIENT_NETWORK.matcher(output).find();
    }

    /**
     * Extract the Apple-assigned submission id from xcrun output.
     *
     * <p>Looks for lines beginning with {@code id:} after trimming. Returns
     * the first match — xcrun prints the same id repeatedly during
     * {@code --wait}, but they are all the same value. Skips lines starting
     * with {@code Current } so {@code Current status: ...} progress noise
     * is ignored.
     *
     * @param output captured xcrun output (may be {@code null})
     * @return the submission id, or {@code null} if none found
     */
    static String extractSubmissionId(String output) {
        if (output == null) return null;
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("id:")) {
                String candidate = trimmed.substring(3).trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Extract the terminal notarization status (e.g. {@code Accepted},
     * {@code Invalid}) from xcrun output. Returns the <em>last</em>
     * matching value so {@code Current status: In Progress} progress
     * lines are ignored in favor of the final {@code status: ...} block.
     *
     * @param output captured xcrun output (may be {@code null})
     * @return the terminal status, or {@code null} if not found
     */
    static String extractStatus(String output) {
        if (output == null) return null;
        String last = null;
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("status:")) {
                last = trimmed.substring("status:".length()).trim();
            }
        }
        return last;
    }

    /**
     * Parse a comma-separated list of positive integers (e.g. {@code "30,120"}).
     * Whitespace, blanks, non-numeric tokens, and non-positive values are skipped.
     *
     * @param csv comma-separated list (may be {@code null} or blank)
     * @return parsed list of positive seconds, possibly empty
     */
    static List<Integer> parseBackoffSchedule(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                int v = Integer.parseInt(t);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
                // skip non-numeric entries silently
            }
        }
        return out;
    }

    /**
     * Pick the wait-seconds for attempt {@code N} (where {@code N >= 2}).
     * Falls back to the last list entry if {@code schedule} is shorter than
     * {@code attempt - 1}. Returns 0 for an empty schedule (no wait).
     *
     * @param schedule parsed backoff seconds list
     * @param attempt  1-based attempt number; values &lt; 2 yield 0
     * @return seconds to wait before this attempt
     */
    static int backoffWait(List<Integer> schedule, int attempt) {
        if (schedule.isEmpty() || attempt < 2) return 0;
        int idx = Math.min(attempt - 2, schedule.size() - 1);
        return schedule.get(idx);
    }

    /**
     * Sleep for {@code seconds}, propagating interruption as a
     * {@link MojoException} so an interrupted Maven build aborts cleanly.
     *
     * @param seconds duration; values &le; 0 return immediately
     * @throws MojoException if interrupted while sleeping
     */
    private void sleepSeconds(int seconds) throws MojoException {
        if (seconds <= 0) return;
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted during retry backoff", ie);
        }
    }

    /**
     * One-line summary of a captured outcome for log messages.
     *
     * @param o outcome to summarize (may be {@code null})
     * @return short human-readable summary
     */
    private static String summarizeOutcome(ExecOutcome o) {
        if (o == null) return "no attempts made";
        String firstNonEmpty = o.output().lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        return "exit=" + o.exitCode()
                + (firstNonEmpty.isEmpty() ? "" : " — " + firstNonEmpty);
    }

    /**
     * Run a command capturing combined stdout+stderr while streaming it
     * through Maven's logger. Unlike {@link ReleaseSupport#execCaptureAndLog},
     * does <em>not</em> throw on non-zero exit — the caller inspects the
     * returned {@link ExecOutcome} and decides whether to retry or fail.
     *
     * @param workDir working directory for the subprocess
     * @param log     Maven logger for real-time output
     * @param command the command and arguments to execute
     * @return captured exit code and output (never {@code null})
     */
    private static ExecOutcome runCapturing(File workDir,
                                            org.apache.maven.api.plugin.Log log,
                                            String... command) {
        log.debug("» " + String.join(" ", command));
        StringBuilder captured = new StringBuilder();
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ReleaseSupport.routeSubprocessLine(log, line);
                    captured.append(line).append('\n');
                }
            }
            int exit = proc.waitFor();
            return new ExecOutcome(exit, captured.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ExecOutcome(-1,
                    captured.toString() + "\n[runner-error] " + e.getMessage());
        }
    }

    /**
     * Find all .pkg and .dmg files in the artifact directory.
     *
     * @return list of installer artifact paths
     * @throws MojoException if the directory cannot be read
     */
    private List<Path> findInstallerArtifacts() throws MojoException {
        List<Path> result = new ArrayList<>();
        if (!artifactDir.isDirectory()) {
            return result;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                artifactDir.toPath(),
                entry -> {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".pkg") || name.endsWith(".dmg");
                })) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to scan for installer artifacts in " + artifactDir, e);
        }
        return result;
    }

    /**
     * Check if the current platform is macOS.
     *
     * @return true if running on macOS
     */
    static boolean isMacOS() {
        return ReleaseSupport.isMacOS();
    }
}
