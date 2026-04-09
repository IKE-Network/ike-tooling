package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>A "Developer ID Installer" certificate in the login keychain</li>
 *   <li>A notarytool keychain profile configured via:
 *       <pre>xcrun notarytool store-credentials "notarytool" \
 *     --apple-id "your@email.com" \
 *     --team-id "YOURTEAMID" \
 *     --password "app-specific-password"</pre></li>
 * </ul>
 *
 * <h3>Usage</h3>
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
@Mojo(name = "notarize",
      defaultPhase = LifecyclePhase.VERIFY,
      requiresProject = true,
      threadSafe = true)
public class NotarizeMojo extends AbstractMojo {

    /** Creates this goal instance. */
    public NotarizeMojo() {}

    /** The current Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

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

    @Override
    public void execute() throws MojoExecutionException {
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
     * @throws MojoExecutionException if any step fails
     */
    private void notarize(Path artifact) throws MojoExecutionException {
        String fileName = artifact.getFileName().toString();
        File workDir = artifact.getParent().toFile();

        // Step 1: Submit to Apple notary service
        getLog().info("Submitting for notarization: " + fileName);
        ReleaseSupport.exec(workDir, getLog(),
                "xcrun", "notarytool", "submit",
                artifact.toString(),
                "--keychain-profile", keychainProfile,
                "--timeout", timeoutMinutes + "m",
                "--wait");

        // Step 2: Staple the notarization ticket
        getLog().info("Stapling ticket: " + fileName);
        ReleaseSupport.exec(workDir, getLog(),
                "xcrun", "stapler", "staple",
                artifact.toString());

        // Step 3: Verify with Gatekeeper
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
     * Find all .pkg and .dmg files in the artifact directory.
     *
     * @return list of installer artifact paths
     * @throws MojoExecutionException if the directory cannot be read
     */
    private List<Path> findInstallerArtifacts() throws MojoExecutionException {
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
            throw new MojoExecutionException(
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
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") || osName.contains("darwin");
    }
}
