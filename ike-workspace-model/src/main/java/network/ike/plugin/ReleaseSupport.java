package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Shared utilities for release mojos.
 *
 * <p>All subprocess invocations use {@link ProcessBuilder} — no
 * library dependencies beyond the JDK and maven-plugin-api.
 */
public class ReleaseSupport {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    private ReleaseSupport() {}

    /**
     * Check if the current platform is macOS.
     *
     * @return {@code true} if running on macOS or Darwin
     */
    public static boolean isMacOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return osName.contains("mac") || osName.contains("darwin");
    }

    /**
     * Run a command, inherit IO so output streams to the Maven console.
     * Throws on non-zero exit code.
     *
     * @param workDir working directory for the subprocess
     * @param log     Maven logger for output routing
     * @param command the command and arguments to execute
     * @throws MojoException if the command exits non-zero or cannot be started
     */
    public static void exec(File workDir, Log log, String... command)
            throws MojoException {
        log.debug("» " + String.join(" ", command));
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();
            // Route subprocess output through Maven's logger, stripping
            // Maven log prefixes to avoid redundant [INFO] [stdout] [INFO].
            // Maps subprocess [WARNING]/[ERROR] to the correct parent level.
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    routeSubprocessLine(log, line);
                }
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        "Command failed (exit " + exit + "): " +
                                String.join(" ", command));
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Route a subprocess output line through Maven's logger at the
     * correct level. Strips Maven log prefixes ([INFO], [WARNING],
     * [ERROR]) from the line to avoid redundant nesting.
     *
     * @param log  Maven logger
     * @param line raw subprocess output line
     */
    public static void routeSubprocessLine(Log log, String line) {
        routeSubprocessLine(log, line, "");
    }

    /**
     * Route a subprocess output line through Maven's logger with a prefix.
     *
     * @param log    Maven logger
     * @param line   raw subprocess output line
     * @param prefix string prepended to each routed line
     */
    public static void routeSubprocessLine(Log log, String line, String prefix) {
        if (line.startsWith("[ERROR] ")) {
            log.error(prefix + line.substring(8));
        } else if (line.startsWith("[WARNING] ")) {
            log.warn(prefix + line.substring(10));
        } else if (line.startsWith("[INFO] ")) {
            log.info(prefix + line.substring(7));
        } else if (line.startsWith("[DEBUG] ")) {
            log.debug(prefix + line.substring(8));
        } else if (line.startsWith("WARNING: ")) {
            // JVM-style warnings (e.g., sun.misc.Unsafe deprecation)
            log.warn(prefix + line.substring(9));
        } else if (line.startsWith("ERROR: ")) {
            // JVM-style errors
            log.error(prefix + line.substring(7));
        } else {
            log.debug(prefix + line);
        }
    }

    /**
     * A command paired with a display label for parallel execution.
     *
     * @param label   human-readable name shown in log output
     * @param command the command and arguments to execute
     */
    public record LabeledTask(String label, String[] command) {}

    /**
     * Run multiple commands concurrently, prefixing each line of output
     * with the task's label (e.g., {@code [nexus] ...}).
     *
     * <p>Spawns virtual threads to read stdout/stderr from each process.
     * All processes run to completion even if one fails — the exception
     * reports which task(s) failed.
     *
     * @param workDir working directory for each subprocess
     * @param log     Maven logger for output routing
     * @param tasks   the labeled tasks to run concurrently
     * @throws MojoException if any task fails or execution is interrupted
     */
    public static void execParallel(File workDir, Log log, LabeledTask... tasks)
            throws MojoException {
        for (LabeledTask task : tasks) {
            log.debug("» [" + task.label() + "] " + String.join(" ", task.command()));
        }

        List<String> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (LabeledTask task : tasks) {
            Thread thread = Thread.ofVirtual()
                    .name("exec-" + task.label())
                    .start(() -> {
                        try {
                            Process process = new ProcessBuilder(task.command())
                                    .directory(workDir)
                                    .redirectErrorStream(true)
                                    .start();

                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(process.getInputStream(),
                                            StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    String prefix = "[" + task.label() + "] ";
                                    synchronized (log) {
                                        routeSubprocessLine(log, line, prefix);
                                    }
                                }
                            }

                            int exit = process.waitFor();
                            if (exit != 0) {
                                failures.add(task.label() + " (exit " + exit + ")");
                            }
                        } catch (IOException | InterruptedException e) {
                            failures.add(task.label() + " (" + e.getMessage() + ")");
                        }
                    });
            threads.add(thread);
        }

        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            throw new MojoException("Parallel execution interrupted", e);
        }

        if (!failures.isEmpty()) {
            throw new MojoException(
                    "Parallel tasks failed: " + String.join(", ", failures));
        }
    }

    /**
     * Run a command and capture stdout as a trimmed String.
     * Throws on non-zero exit code.
     *
     * @param workDir working directory for the subprocess
     * @param command the command and arguments to execute
     * @return trimmed stdout output
     * @throws MojoException if the command exits non-zero or cannot be started
     */
    public static String execCapture(File workDir, String... command)
            throws MojoException {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        "Command failed (exit " + exit + "): " +
                                String.join(" ", command));
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Run a command, streaming output through Maven's logger AND
     * capturing the full output as a String. Throws on non-zero exit.
     *
     * @param workDir working directory for the subprocess
     * @param log     Maven logger for real-time output
     * @param command the command and arguments to execute
     * @return the complete stdout+stderr output as a trimmed string
     * @throws MojoException if the command exits non-zero
     */
    public static String execCaptureAndLog(File workDir, Log log, String... command)
            throws MojoException {
        log.debug("» " + String.join(" ", command));
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder captured = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    routeSubprocessLine(log, line);
                    captured.append(line).append('\n');
                }
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                String output = captured.toString().trim();
                String detail = output.isEmpty()
                        ? ""
                        : "\nOutput:\n" + output;
                throw new MojoException(
                        "Command failed (exit " + exit + "): "
                                + String.join(" ", command) + detail);
            }
            return captured.toString().trim();
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Read the project's own {@code <version>} from a POM file,
     * skipping any {@code <version>} inside the {@code <parent>} block.
     *
     * @param pomFile the POM file to read
     * @return the version string
     * @throws MojoException if the file cannot be read or has no version
     */
    public static String readPomVersion(File pomFile) throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);

            // Strip the <parent>...</parent> block so we don't match
            // the parent version instead of the project version.
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = VERSION_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new MojoException(
                    "Could not extract <version> from " + pomFile);
        } catch (IOException e) {
            throw new MojoException("Failed to read " + pomFile, e);
        }
    }

    /**
     * Stamp {@code <project.build.outputTimestamp>} in the root POM to
     * {@code newTimestamp}, enabling reproducible builds for the release.
     *
     * <p>The property must already exist in the POM (inherited from
     * ike-parent). If it is absent this method is a no-op with a warning.
     *
     * @param pomFile      the root POM to update
     * @param newTimestamp ISO-8601 UTC timestamp, e.g. {@code 2026-03-30T12:00:00Z}
     * @param log          Maven log (used for warnings only)
     * @throws MojoException if the file cannot be read or written
     */
    public static void stampOutputTimestamp(File pomFile, String newTimestamp, Log log)
            throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                    "(<project\\.build\\.outputTimestamp>)[^<]*(</project\\.build\\.outputTimestamp>)");
            java.util.regex.Matcher m = pat.matcher(content);
            if (!m.find()) {
                log.warn("project.build.outputTimestamp not found in " + pomFile
                        + " — reproducible build stamp skipped");
                return;
            }
            String updated = m.replaceFirst("$1" + newTimestamp + "$2");
            Files.writeString(pomFile.toPath(), updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to stamp outputTimestamp in " + pomFile, e);
        }
    }

    /**
     * Replace the project's own {@code <version>old</version>} with
     * {@code <version>new</version>}, skipping any version inside
     * the {@code <parent>} block.
     *
     * @param pomFile    the POM file to update
     * @param oldVersion the current version string to replace
     * @param newVersion the new version string
     * @throws MojoException if the version is not found or the file cannot be updated
     */
    public static void setPomVersion(File pomFile, String oldVersion, String newVersion)
            throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            String oldTag = "<version>" + oldVersion + "</version>";
            String newTag = "<version>" + newVersion + "</version>";

            // Find the end of the <parent> block (if any) so we skip it
            int searchStart = 0;
            Matcher parentEnd = Pattern.compile("</parent>").matcher(content);
            if (parentEnd.find()) {
                searchStart = parentEnd.end();
            }

            int idx = content.indexOf(oldTag, searchStart);
            if (idx < 0) {
                throw new MojoException(
                        "POM does not contain " + oldTag +
                                " (outside <parent> block)");
            }
            String updated = content.substring(0, idx) + newTag +
                    content.substring(idx + oldTag.length());
            Files.writeString(pomFile.toPath(), updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Failed to update " + pomFile, e);
        }
    }

    /**
     * Check if the current platform is Windows.
     *
     * @return {@code true} if {@code os.name} contains "win"
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Resolve the Maven executable. Prefers the Maven wrapper
     * ({@code mvnw} on Unix, {@code mvnw.cmd} on Windows) at the
     * git root; falls back to system Maven located via {@code which}
     * on Unix or {@code where} on Windows.
     *
     * @param gitRoot the git repository root directory
     * @param log     Maven logger
     * @return the resolved Maven executable
     * @throws MojoException if neither wrapper nor system Maven is found
     */
    public static File resolveMavenWrapper(File gitRoot, Log log) throws MojoException {
        return resolveMavenWrapperFor(gitRoot, log, isWindows());
    }

    /**
     * OS-injected variant of {@link #resolveMavenWrapper(File, Log)} for testing.
     * Production callers should use the two-argument overload.
     *
     * @param gitRoot the git repository root directory
     * @param log     Maven logger
     * @param windows {@code true} to use Windows wrapper/lookup conventions,
     *                {@code false} for Unix conventions
     * @return the resolved Maven executable
     * @throws MojoException if neither wrapper nor system Maven is found
     */
    static File resolveMavenWrapperFor(File gitRoot, Log log, boolean windows)
            throws MojoException {
        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        File wrapper = new File(gitRoot, wrapperName);
        if (wrapper.exists()) {
            return wrapper;
        }
        // Fall back to system mvn — resolve via PATH
        String systemName = windows ? "mvn.cmd" : "mvn";
        String lookupTool = windows ? "where" : "which";
        try {
            String output = execCapture(gitRoot, lookupTool, systemName);
            String path = firstNonEmptyLine(output);
            log.info("No Maven wrapper found; using system '" + path + "'");
            return new File(path);
        } catch (MojoException _) {
            throw new MojoException(
                    "Neither Maven wrapper (" + wrapper.getAbsolutePath() +
                            ") nor system '" + systemName + "' found on PATH.");
        }
    }

    /**
     * Return the first non-empty line of {@code output}, trimmed.
     * Handles the Windows {@code where} command, which may emit multiple
     * matches separated by newlines (e.g. {@code mvn.cmd} from a wrapper
     * shim and from a system install).
     *
     * @param output multi-line command output
     * @return first non-empty line trimmed, or the trimmed full output
     *         if no non-empty line exists
     */
    static String firstNonEmptyLine(String output) {
        return output.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(output.trim());
    }

    /**
     * Get the git repository root directory.
     *
     * @param startDir any directory inside the repository
     * @return the repository root directory
     * @throws MojoException if git rev-parse fails
     */
    public static File gitRoot(File startDir) throws MojoException {
        String root = execCapture(startDir,
                "git", "rev-parse", "--show-toplevel");
        return new File(root);
    }

    /**
     * Assert that the git working tree is clean (no staged or unstaged changes).
     *
     * @param workDir any directory inside the repository
     * @throws MojoException if the working tree has uncommitted changes
     */
    public static void requireCleanWorktree(File workDir) throws MojoException {
        try {
            execCapture(workDir, "git", "diff", "--quiet");
        } catch (MojoException _) {
            throw new MojoException(
                    "Working tree has unstaged changes. Commit or stash before proceeding.");
        }
        try {
            execCapture(workDir, "git", "diff", "--cached", "--quiet");
        } catch (MojoException _) {
            throw new MojoException(
                    "Working tree has staged changes. Commit or stash before proceeding.");
        }
    }

    /**
     * Get the current git branch name.
     *
     * @param workDir any directory inside the repository
     * @return the current branch name
     * @throws MojoException if git rev-parse fails
     */
    public static String currentBranch(File workDir) throws MojoException {
        return execCapture(workDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * Check whether a named git remote exists.
     *
     * @param workDir    any directory inside the repository
     * @param remoteName the remote name to check (e.g., "origin")
     * @return {@code true} if the remote exists
     */
    public static boolean hasRemote(File workDir, String remoteName) {
        try {
            String remotes = execCapture(workDir, "git", "remote");
            return remotes.lines().anyMatch(line -> line.trim().equals(remoteName));
        } catch (MojoException _) {
            return false;
        }
    }

    /**
     * Return the URL of a named git remote, or null if the remote does
     * not exist.
     *
     * @param workDir    any directory inside the repository
     * @param remoteName the remote name (typically {@code "origin"})
     * @return the remote URL, or null if the remote is absent
     */
    public static String getRemoteUrl(File workDir, String remoteName) {
        try {
            String url = execCapture(workDir,
                    "git", "remote", "get-url", remoteName);
            return url.isBlank() ? null : url.trim();
        } catch (MojoException _) {
            return null;
        }
    }

    /**
     * Derive the release version from a SNAPSHOT version.
     * {@code "2-SNAPSHOT"} becomes {@code "2"};
     * {@code "1.1.0-SNAPSHOT"} becomes {@code "1.1.0"}.
     *
     * @param snapshotVersion the SNAPSHOT version string
     * @return the release version without the -SNAPSHOT suffix
     */
    public static String deriveReleaseVersion(String snapshotVersion) {
        return snapshotVersion.replace("-SNAPSHOT", "");
    }

    /**
     * Derive the next SNAPSHOT version by incrementing the last numeric
     * segment. {@code "2"} becomes {@code "3-SNAPSHOT"};
     * {@code "1.1.0"} becomes {@code "1.1.1-SNAPSHOT"}.
     *
     * @param releaseVersion the release version to increment
     * @return the next SNAPSHOT version
     */
    public static String deriveNextSnapshot(String releaseVersion) {
        String base = releaseVersion.replace("-SNAPSHOT", "");
        int lastDot = base.lastIndexOf('.');
        if (lastDot >= 0) {
            String prefix = base.substring(0, lastDot + 1);
            String last = base.substring(lastDot + 1);
            return prefix + (Integer.parseInt(last) + 1) + "-SNAPSHOT";
        }
        // Simple integer version (e.g., "2" -> "3-SNAPSHOT")
        return (Integer.parseInt(base) + 1) + "-SNAPSHOT";
    }

    /**
     * Update a named Maven property in POM content.
     * Replaces {@code <propertyName>oldValue</propertyName>} with
     * {@code <propertyName>newVersion</propertyName>}.
     *
     * @param pomContent   the POM file content as a string
     * @param propertyName the Maven property name (e.g., "ike-bom.version")
     * @param newVersion   the new version value
     * @return the updated POM content (unchanged if property not found)
     */
    public static String updateVersionProperty(String pomContent,
                                         String propertyName,
                                         String newVersion) {
        String propPattern = "<" + java.util.regex.Pattern.quote(propertyName)
                + ">[^<]+</" + java.util.regex.Pattern.quote(propertyName) + ">";
        return pomContent.replaceAll(propPattern,
                "<" + propertyName + ">" + newVersion + "</" + propertyName + ">");
    }

    private static final String PROJECT_VERSION_EXPR = "${project.version}";
    private static final String BACKUP_SUFFIX = ".ike-backup";

    /**
     * Find all {@code pom.xml} files under the git root, excluding
     * {@code target/} directories and the {@code .mvn/} directory.
     *
     * @param gitRoot the git repository root directory
     * @return list of discovered POM files
     * @throws MojoException if the file tree cannot be walked
     */
    public static List<File> findPomFiles(File gitRoot) throws MojoException {
        try (Stream<Path> walk = Files.walk(gitRoot.toPath())) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        String rel = gitRoot.toPath().relativize(p).toString();
                        return !rel.contains("target" + File.separator)
                                && !rel.startsWith(".mvn" + File.separator);
                    })
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoException("Failed to scan for POM files", e);
        }
    }

    /**
     * Replace all occurrences of {@code ${project.version}} with a
     * literal version string in every POM file under the git root.
     * Before replacing, each affected file is saved as
     * {@code pom.xml.ike-backup} so it can be restored later.
     *
     * @param gitRoot the git repository root directory
     * @param version the literal version to substitute
     * @param log     Maven logger
     * @return the list of POM files that were modified
     * @throws MojoException if a file cannot be read or written
     */
    public static List<File> replaceProjectVersionRefs(File gitRoot, String version,
                                                 Log log)
            throws MojoException {
        List<File> pomFiles = findPomFiles(gitRoot);
        List<File> modified = new ArrayList<>();

        for (File pom : pomFiles) {
            try {
                String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
                if (!content.contains(PROJECT_VERSION_EXPR)) {
                    continue;
                }
                // Save backup before modifying
                Path backup = pom.toPath().resolveSibling(pom.getName() + BACKUP_SUFFIX);
                Files.copy(pom.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);

                // Replace all occurrences
                String updated = content.replace(PROJECT_VERSION_EXPR, version);
                Files.writeString(pom.toPath(), updated, StandardCharsets.UTF_8);

                String rel = gitRoot.toPath().relativize(pom.toPath()).toString();
                log.info("  Resolved ${project.version} -> " + version +
                        " in " + rel);
                modified.add(pom);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to process " + pom, e);
            }
        }
        return modified;
    }

    /**
     * Restore all POM files from their {@code .ike-backup} copies and
     * delete the backup files. This reverses
     * {@link #replaceProjectVersionRefs}.
     *
     * @param gitRoot the git repository root directory
     * @param log     Maven logger
     * @return the list of POM files that were restored
     * @throws MojoException if a backup cannot be restored
     */
    public static List<File> restoreBackups(File gitRoot, Log log)
            throws MojoException {
        List<File> pomFiles = findPomFiles(gitRoot);
        List<File> restored = new ArrayList<>();

        for (File pom : pomFiles) {
            Path backup = pom.toPath().resolveSibling(pom.getName() + BACKUP_SUFFIX);
            if (!Files.exists(backup)) {
                continue;
            }
            try {
                Files.copy(backup, pom.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.delete(backup);

                String rel = gitRoot.toPath().relativize(pom.toPath()).toString();
                log.info("  Restored ${project.version} in " + rel);
                restored.add(pom);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to restore backup for " + pom, e);
            }
        }
        return restored;
    }

    /**
     * Stage a list of files with {@code git add}.
     *
     * @param gitRoot the git repository root directory
     * @param log     Maven logger
     * @param files   the files to stage
     * @throws MojoException if the git add command fails
     */
    public static void gitAddFiles(File gitRoot, Log log, List<File> files)
            throws MojoException {
        if (files.isEmpty()) return;
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("add");
        for (File f : files) {
            command.add(gitRoot.toPath().relativize(f.toPath()).toString());
        }
        exec(gitRoot, log, command.toArray(new String[0]));
    }

    private static final DateTimeFormatter CHECKPOINT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Derive a checkpoint version from the current POM version.
     *
     * <p>Format: {@code {base}-checkpoint.{yyyyMMdd}.{shortSha}} where
     * {@code base} is the POM version minus {@code -SNAPSHOT}, and
     * {@code shortSha} is the abbreviated SHA of the current HEAD commit.
     *
     * <p>This scheme is fully deterministic — the same commit on any
     * machine always produces the same version string. No tag-sequence
     * coordination across machines is required.
     *
     * @param pomVersion current POM version (may include -SNAPSHOT)
     * @param gitRoot    git repository root (for HEAD SHA lookup)
     * @return the checkpoint version string
     * @throws MojoException if the HEAD SHA cannot be resolved
     */
    public static String deriveCheckpointVersion(String pomVersion, File gitRoot)
            throws MojoException {
        String base = pomVersion.replace("-SNAPSHOT", "");
        String date = LocalDate.now().format(CHECKPOINT_DATE_FMT);
        String shortSha = execCapture(gitRoot, "git", "rev-parse", "--short", "HEAD");
        return base + "-checkpoint." + date + "." + shortSha;
    }

    /**
     * Check whether a git tag exists (locally).
     *
     * @param gitRoot the git repository root directory
     * @param tagName the tag name to check
     * @return {@code true} if the tag exists locally
     */
    public static boolean tagExists(File gitRoot, String tagName) {
        try {
            execCapture(gitRoot, "git", "rev-parse", "--verify", "refs/tags/" + tagName);
            return true;
        } catch (MojoException _) {
            return false;
        }
    }

    /** Base path on the site server. */
    public static final String SITE_DISK_BASE = "/srv/ike-site/";

    /** SSH host alias used by wagon-ssh-external. */
    public static final String SITE_SSH_HOST = "proxy";

    /**
     * Remove a directory tree on the site server via SSH.
     *
     * <p>Used to clean up snapshot sites after release or feature-finish.
     *
     * <p>Safety: validates the path starts with {@link #SITE_DISK_BASE}
     * and contains at least two path components after the base to
     * prevent accidental deletion of the entire site root.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath absolute path on the server (e.g.,
     *                   {@code /srv/ike-site/ike-pipeline/snapshot/main})
     * @throws MojoException if the path is unsafe or SSH fails
     */
    public static void cleanRemoteSiteDir(File workDir, Log log, String remotePath)
            throws MojoException {
        cleanRemoteSiteDir(workDir, log, remotePath, "ssh", SITE_SSH_HOST);
    }

    /**
     * Overload accepting an explicit SSH command prefix — package-private
     * for testing against containers.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath absolute path on the server to remove
     * @param sshPrefix  the SSH command tokens (e.g., "ssh", "-i", "key",
     *                   "-p", "2222", "user@localhost")
     * @throws MojoException if the path is unsafe or SSH fails
     */
    public static void cleanRemoteSiteDir(File workDir, Log log, String remotePath,
                                    String... sshPrefix)
            throws MojoException {
        validateRemotePath(remotePath);
        log.debug("Cleaning remote site: " + remotePath);
        String[] cmd = new String[sshPrefix.length + 3];
        System.arraycopy(sshPrefix, 0, cmd, 0, sshPrefix.length);
        cmd[sshPrefix.length] = "rm";
        cmd[sshPrefix.length + 1] = "-rf";
        cmd[sshPrefix.length + 2] = remotePath;
        exec(workDir, log, cmd);
    }

    /**
     * Atomically swap a newly deployed site into place on the server.
     *
     * <p>The deployment flow is:
     * <ol>
     *   <li>SCP deploys to a staging path ({@code <target>.staging})</li>
     *   <li>This method renames the old directory to {@code <target>.old}</li>
     *   <li>Renames the staging directory to the final target</li>
     *   <li>Removes the old directory</li>
     * </ol>
     *
     * <p>This avoids a window where the site is missing (rm + deploy)
     * and ensures the site always serves either the old or new version.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath final target path on the server
     * @throws MojoException if SSH commands fail
     */
    public static void swapRemoteSiteDir(File workDir, Log log, String remotePath)
            throws MojoException {
        swapRemoteSiteDir(workDir, log, remotePath, "ssh", SITE_SSH_HOST);
    }

    /**
     * Overload accepting an explicit SSH command prefix — package-private
     * for testing against containers.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath final target path on the server
     * @param sshPrefix  the SSH command tokens (e.g., "ssh", "-i", "key",
     *                   "-p", "2222", "user@localhost")
     * @throws MojoException if the path is unsafe or SSH fails
     */
    public static void swapRemoteSiteDir(File workDir, Log log, String remotePath,
                                   String... sshPrefix)
            throws MojoException {
        validateRemotePath(remotePath);
        String staging = remotePath + ".staging";
        String old = remotePath + ".old";

        log.info("Swapping site: " + staging + " → " + remotePath);
        String[] cmd = new String[sshPrefix.length + 1];
        System.arraycopy(sshPrefix, 0, cmd, 0, sshPrefix.length);
        cmd[sshPrefix.length] = "rm -rf " + old
                + " && (mv " + remotePath + " " + old + " 2>/dev/null || true)"
                + " && mv " + staging + " " + remotePath
                + " && rm -rf " + old;
        exec(workDir, log, cmd);
    }

    /**
     * Return the staging path for a site deploy (final path + ".staging").
     *
     * @param diskPath the final on-disk site path
     * @return {@code diskPath} with {@code .staging} appended
     */
    public static String siteStagingPath(String diskPath) {
        return diskPath + ".staging";
    }

    /**
     * Update the {@code latest} symlink alongside a version-prefixed
     * site deploy so that {@code <site-base>/latest/} always points at
     * the most recent release (ike-issues#303).
     *
     * <p>For a release deployed to
     * {@code /srv/ike-site/ike-platform/17/}, this issues
     * {@code cd /srv/ike-site/ike-platform && ln -snf 17 latest} on the
     * site host. Idempotent — the {@code -f} flag replaces any prior
     * symlink target.
     *
     * <p>Uses the same SSH host as {@link #swapRemoteSiteDir}.
     * Best-effort: callers should catch {@link MojoException} and
     * surface as a warning rather than failing the release — the
     * version-prefixed site is reachable at its own URL even if the
     * alias update fails.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath the version-prefixed final site path
     *                   (e.g. {@code /srv/ike-site/ike-platform/17})
     * @throws MojoException if the path is unsafe or SSH fails
     */
    public static void updateLatestSymlink(File workDir, Log log,
                                           String remotePath)
            throws MojoException {
        updateLatestSymlink(workDir, log, remotePath, "ssh", SITE_SSH_HOST);
    }

    /**
     * Overload accepting an explicit SSH command prefix —
     * package-private for testing against containers.
     *
     * @param workDir    local directory for process execution
     * @param log        Maven log
     * @param remotePath the version-prefixed final site path
     * @param sshPrefix  the SSH command tokens
     * @throws MojoException if the path is unsafe or SSH fails
     */
    public static void updateLatestSymlink(File workDir, Log log,
                                           String remotePath,
                                           String... sshPrefix)
            throws MojoException {
        validateRemotePath(remotePath);
        String parent = parentDir(remotePath);
        String leaf = leafName(remotePath);
        if (parent == null || leaf == null || leaf.isEmpty()) {
            throw new MojoException(
                    "Cannot derive parent/leaf from site path: " + remotePath);
        }

        log.info("Updating latest symlink: " + parent + "/latest -> " + leaf);
        String[] cmd = new String[sshPrefix.length + 1];
        System.arraycopy(sshPrefix, 0, cmd, 0, sshPrefix.length);
        cmd[sshPrefix.length] = "cd " + parent
                + " && ln -snf " + leaf + " latest";
        exec(workDir, log, cmd);
    }

    /**
     * Compute the parent directory of a Unix-style absolute path
     * without crossing the {@link #SITE_DISK_BASE} boundary. Returns
     * {@code null} when the input is at or above the base.
     *
     * <p>Package-private for testing.
     */
    static String parentDir(String absPath) {
        int lastSlash = absPath.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        String parent = absPath.substring(0, lastSlash);
        return parent.startsWith(SITE_DISK_BASE.replaceAll("/$", ""))
                ? parent : null;
    }

    /**
     * Last path segment of a Unix-style absolute path — the basename.
     * Trailing slashes are tolerated.
     *
     * <p>Package-private for testing.
     */
    static String leafName(String absPath) {
        String trimmed = absPath.endsWith("/")
                ? absPath.substring(0, absPath.length() - 1) : absPath;
        int lastSlash = trimmed.lastIndexOf('/');
        return lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1);
    }

    /**
     * Return the scpexe URL for the staging directory.
     *
     * @param targetUrl the final site URL
     * @return {@code targetUrl} with {@code .staging} appended
     */
    public static String siteStagingUrl(String targetUrl) {
        return targetUrl + ".staging";
    }

    /**
     * Publish a project's rendered site to its repo's {@code gh-pages}
     * branch (ike-issues#312).
     *
     * <p>Force-pushes a single orphan commit containing the contents of
     * {@code stagingDir} to {@code gh-pages} on the project's git remote.
     * Adds a {@code .nojekyll} marker so GitHub Pages skips Jekyll
     * processing — the content is already rendered HTML and we don't
     * want underscore-prefixed directories to be stripped.
     *
     * <p>Does NOT write a {@code CNAME} file: the org-level
     * {@code IKE-Network.github.io/CNAME} (set to {@code ike.network})
     * extends to all project pages under the org automatically. A
     * per-project CNAME would either be ignored or conflict.
     *
     * <p>The stagingDir content is published verbatim — no path
     * mangling, no version-prefixing. GitHub Pages then serves it at
     * {@code https://ike.network/<repo>/} via the org's CNAME.
     *
     * <p>Patterned on {@code OrgSiteSupport.publishToGhPages} (in
     * the ike-maven-plugin module) but generalized to any project's
     * staging dir + remote. ike-workspace-model can't {@code @link}
     * directly to ike-maven-plugin classes — it sits below in the
     * dependency stack, so they're not on its javadoc classpath.
     *
     * @param stagingDir directory containing the rendered site
     *                   (typically {@code target/staging/})
     * @param repoUrl    git URL of the project repo to push to
     * @param log        Maven logger
     * @param projectId  project artifact ID, used in the commit message
     * @param version    release version, used in the commit message
     * @throws MojoException if any step fails
     */
    public static void publishProjectSiteToGhPages(Path stagingDir,
                                                    String repoUrl,
                                                    Log log,
                                                    String projectId,
                                                    String version)
            throws MojoException {
        if (!Files.isDirectory(stagingDir)) {
            throw new MojoException(
                    "Staging directory does not exist: " + stagingDir
                            + ". Site build may have failed.");
        }

        log.info("Publishing " + projectId + " site to gh-pages...");

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("ike-project-gh-pages-");
        } catch (IOException e) {
            throw new MojoException(
                    "Could not create temp directory for gh-pages publish", e);
        }

        try {
            File tempRoot = tempDir.toFile();

            exec(tempRoot, log, "git", "init");
            exec(tempRoot, log, "git", "checkout", "--orphan", "gh-pages");

            try {
                copyDirectory(stagingDir, tempDir);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to copy staging dir to temp: " + e.getMessage(), e);
            }

            // .nojekyll — disable Jekyll preprocessing on rendered HTML.
            Path nojekyll = tempDir.resolve(".nojekyll");
            try {
                Files.writeString(nojekyll, "");
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to write .nojekyll marker: " + e.getMessage(), e);
            }

            // Defensive: never carry a per-repo CNAME — the org CNAME
            // (IKE-Network.github.io -> ike.network) extends down.
            // If a stray CNAME ended up in the staging dir, drop it.
            Path strayCname = tempDir.resolve("CNAME");
            if (Files.exists(strayCname)) {
                try {
                    Files.delete(strayCname);
                    log.info("  Dropped stray CNAME from staging "
                            + "(per-project CNAMEs conflict with org CNAME)");
                } catch (IOException e) {
                    throw new MojoException(
                            "Could not delete stray CNAME: " + e.getMessage(), e);
                }
            }

            exec(tempRoot, log, "git", "add", "-A");
            exec(tempRoot, log, "git", "commit", "-m",
                    "site: publish " + projectId + " " + version);
            exec(tempRoot, log, "git", "push", "--force",
                    repoUrl, "gh-pages:gh-pages");

            log.info("  Published: https://ike.network/" + projectId + "/");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * Validate that a remote path is safe for deletion operations.
     *
     * <p>Ensures the path starts with {@link #SITE_DISK_BASE} and has
     * sufficient depth to prevent accidental deletion of the site root.
     *
     * @param remotePath absolute path on the server
     * @throws MojoException if the path is unsafe
     */
    public static void validateRemotePath(String remotePath)
            throws MojoException {
        if (!remotePath.startsWith(SITE_DISK_BASE)) {
            throw new MojoException(
                    "Refusing to delete — path does not start with "
                            + SITE_DISK_BASE + ": " + remotePath);
        }
        String relative = remotePath.substring(SITE_DISK_BASE.length());
        long depth = relative.chars().filter(c -> c == '/').count();
        if (relative.isBlank() || depth < 1) {
            throw new MojoException(
                    "Refusing to delete — path too shallow (need project/type): "
                            + remotePath);
        }
    }

    /**
     * Resolve the on-disk site path for a given project, type, and
     * optional subdirectory.
     *
     * @param projectId  Maven artifact ID (e.g., "ike-pipeline")
     * @param siteType   "release", "snapshot", or "checkpoint"
     * @param subPath    optional subdirectory (branch name, version);
     *                   null or blank to omit
     * @return absolute path on the server
     */
    public static String siteDiskPath(String projectId, String siteType,
                               String subPath) {
        String path = SITE_DISK_BASE + projectId + "/" + siteType;
        if (subPath != null && !subPath.isBlank()) {
            path += "/" + subPath;
        }
        return path;
    }

    /**
     * Convert a git branch name to a safe site path segment.
     * Replaces {@code /} with {@code /} (keeps hierarchy for
     * {@code feature/name} structure).
     *
     * @param branch git branch name
     * @return sanitized path segment safe for use in URLs and file paths
     */
    public static String branchToSitePath(String branch) {
        // Keep forward slashes for directory structure (feature/name → feature/name)
        // but sanitize anything dangerous
        return branch.replaceAll("[^a-zA-Z0-9/_.-]", "-");
    }

    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");

    /**
     * Read the project's own {@code <artifactId>} from a POM file,
     * skipping any {@code <artifactId>} inside the {@code <parent>} block.
     *
     * @param pomFile the POM file to read
     * @return the artifact ID string
     * @throws MojoException if the file cannot be read or has no artifact ID
     */
    public static String readPomArtifactId(File pomFile) throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = ARTIFACT_ID_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new MojoException(
                    "Could not extract <artifactId> from " + pomFile);
        } catch (IOException e) {
            throw new MojoException("Failed to read " + pomFile, e);
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     * Best-effort — failures are silently ignored.
     *
     * @param dir the directory to delete
     */
    public static void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d,
                        IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Best-effort — log but don't fail
        }
    }

    /**
     * Recursively copy a directory tree.
     *
     * @param source the source directory to copy from
     * @param target the target directory to copy to
     * @throws IOException if a file cannot be copied
     */
    public static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Read the {@code <name>} element from a POM file.
     *
     * @param pomFile the POM file to read
     * @return the name, or null if not present
     * @throws MojoException if the file cannot be read
     */
    public static String readPomName(File pomFile) throws MojoException {
        return readPomElement(pomFile, "name");
    }

    /**
     * Read the {@code <description>} element from a POM file.
     *
     * @param pomFile the POM file to read
     * @return the description, or null if not present
     * @throws MojoException if the file cannot be read
     */
    public static String readPomDescription(File pomFile) throws MojoException {
        return readPomElement(pomFile, "description");
    }

    private static String readPomElement(File pomFile, String element)
            throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile(
                    "<" + element + ">([^<]+)</" + element + ">");
            Matcher matcher = pattern.matcher(content);
            return matcher.find() ? matcher.group(1).trim() : null;
        } catch (IOException e) {
            throw new MojoException("Failed to read " + pomFile, e);
        }
    }
}
