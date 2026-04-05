package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Publish a pre-built Maven site to GitHub Pages via an orphan branch.
 *
 * <p>This goal pushes the contents of {@code target/staging/} (the
 * output of {@code mvn site site:stage}) to the {@code gh-pages}
 * branch of the project's GitHub repository. Each publish is a
 * single force-pushed orphan commit — no history accumulates and
 * the branch is never cloned by default.
 *
 * <p>Designed to be called after the internal site deploy succeeds,
 * keeping the internal release process as the source of truth and
 * GitHub Pages as a public publication channel.
 *
 * <p>Usage:
 * <pre>
 * # After site is already built:
 * mvn ike:publish-site
 *
 * # Build site first, then publish:
 * mvn site site:stage ike:publish-site
 *
 * # Dry run:
 * mvn ike:publish-site -DdryRun=true
 * </pre>
 */
@Mojo(name = "publish-site", requiresProject = false, aggregator = true, threadSafe = true)
public class PublishSiteMojo extends AbstractMojo {

    /** The directory containing the staged site to publish. */
    @Parameter(property = "stagingDirectory", defaultValue = "${project.build.directory}/staging")
    private File stagingDirectory;

    /** The branch to push to. */
    @Parameter(property = "publishBranch", defaultValue = "gh-pages")
    private String publishBranch;

    /** The git remote to push to. */
    @Parameter(property = "publishRemote", defaultValue = "origin")
    private String publishRemote;

    /** Commit message for the orphan commit. */
    @Parameter(property = "publishMessage")
    private String publishMessage;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /** Creates this goal instance. */
    public PublishSiteMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File rootPom = new File(gitRoot, "pom.xml");

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);
        String version = ReleaseSupport.readPomVersion(rootPom);

        // Resolve staging directory relative to git root if default
        if (!stagingDirectory.isAbsolute()) {
            stagingDirectory = new File(gitRoot, "target/staging");
        }

        if (publishMessage == null || publishMessage.isBlank()) {
            publishMessage = "site: publish " + projectId + " " + version;
        }

        getLog().info("");
        getLog().info("PUBLISH SITE TO GITHUB PAGES");
        getLog().info("  Project:     " + projectId);
        getLog().info("  Version:     " + version);
        getLog().info("  Source:      " + stagingDirectory);
        getLog().info("  Remote:      " + publishRemote);
        getLog().info("  Branch:      " + publishBranch);
        getLog().info("  Dry run:     " + dryRun);
        getLog().info("");

        if (!stagingDirectory.isDirectory()) {
            throw new MojoExecutionException(
                    "Staging directory does not exist: " + stagingDirectory +
                            ". Run 'mvn site site:stage' first.");
        }

        // Verify the remote exists
        if (!ReleaseSupport.hasRemote(gitRoot, publishRemote)) {
            throw new MojoExecutionException(
                    "No '" + publishRemote + "' remote configured.");
        }

        if (dryRun) {
            getLog().info("[DRY RUN] Would create orphan commit from: "
                    + stagingDirectory);
            getLog().info("[DRY RUN] Would force-push to: "
                    + publishRemote + "/" + publishBranch);
            return;
        }

        // Create a temporary directory for the orphan repo
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("ike-publish-site-");
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Could not create temp directory", e);
        }

        try {
            File tempRoot = tempDir.toFile();

            // Init a bare repo, create orphan branch, copy content, commit
            ReleaseSupport.exec(tempRoot, getLog(),
                    "git", "init");
            ReleaseSupport.exec(tempRoot, getLog(),
                    "git", "checkout", "--orphan", publishBranch);

            // Copy staged site into the temp repo
            copyDirectory(stagingDirectory.toPath(), tempDir);

            // Commit all content
            ReleaseSupport.exec(tempRoot, getLog(),
                    "git", "add", "-A");
            ReleaseSupport.exec(tempRoot, getLog(),
                    "git", "commit", "-m", publishMessage);

            // Resolve the remote URL from the real repo
            String remoteUrl = ReleaseSupport.execCapture(gitRoot,
                    "git", "remote", "get-url", publishRemote);

            // Force-push to the remote
            ReleaseSupport.exec(tempRoot, getLog(),
                    "git", "push", "--force", remoteUrl,
                    publishBranch + ":" + publishBranch);

            getLog().info("");
            getLog().info("Site published to " + publishRemote + "/"
                    + publishBranch);

        } finally {
            // Clean up temp directory
            deleteDirectory(tempDir);
        }
    }

    // ── File utilities (pure, static) ────────────────────────────────

    /**
     * Recursively copy a directory tree.
     *
     * @param source the source directory
     * @param target the target directory
     */
    static void copyDirectory(Path source, Path target)
            throws MojoExecutionException {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                        BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file)),
                            StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to copy site directory", e);
        }
    }

    /**
     * Recursively delete a directory tree. Best-effort — failures are
     * logged but do not throw.
     */
    static void deleteDirectory(Path dir) {
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
        } catch (IOException ignored) {
            // Best-effort cleanup of temp dir
        }
    }
}
