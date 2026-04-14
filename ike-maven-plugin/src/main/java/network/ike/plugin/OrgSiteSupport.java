package network.ike.plugin;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.MojoException;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared utilities for org-site registration and deregistration.
 *
 * <p>Handles cloning the org site repository, writing/deleting project
 * fragments, regenerating the master index, rendering AsciiDoc to
 * XHTML, and publishing the built site to GitHub Pages.
 *
 * <p>Parallel to {@link ReleaseSupport} — all subprocess invocations
 * use {@link ProcessBuilder}, no library dependencies beyond the JDK,
 * maven-plugin-api, and AsciidoctorJ.
 */
public final class OrgSiteSupport {

    /** Default Git URL for the org site repository. */
    static final String ORG_REPO_DEFAULT =
            "https://github.com/IKE-Network/IKE-Network.github.io.git";

    /** Directory within the org repo that holds project fragments. */
    static final String FRAGMENT_DIR = "projects";

    /** Path to the master index AsciiDoc source, relative to repo root. */
    private static final String INDEX_ADOC = "src/site/asciidoc/index.adoc";

    /** Branch used for rendered site content (GitHub Pages source). */
    private static final String GH_PAGES_BRANCH = "gh-pages";

    private OrgSiteSupport() {}

    // ── Fragment I/O ─────────────────────────────────────────────────

    /**
     * Write a project registration fragment to the org repo.
     *
     * <p>Creates the {@code projects/} directory if absent. Overwrites
     * any existing fragment for the same artifact ID (re-registration
     * on version bump).
     *
     * @param orgRoot       root of the cloned org repository
     * @param artifactId    Maven artifact ID (used as filename)
     * @param name          human-readable project name
     * @param description   one-line project description
     * @param version       release version (not SNAPSHOT)
     * @param siteUrl       public site URL (e.g., https://ike.network/ike-pipeline/)
     * @param githubUrl     GitHub repository URL
     * @param modules       reactor module names (may be empty)
     * @throws MojoException if the fragment cannot be written
     */
    public static void writeFragment(File orgRoot, String artifactId,
                                      String name, String description,
                                      String version, String siteUrl,
                                      String githubUrl, List<String> modules)
            throws MojoException {
        Path fragmentDir = orgRoot.toPath().resolve(FRAGMENT_DIR);
        try {
            Files.createDirectories(fragmentDir);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not create fragment directory: " + fragmentDir, e);
        }

        String siteLabel = siteUrl.replaceFirst("^https?://", "")
                .replaceFirst("/$", "");
        String githubLabel = githubUrl.replaceFirst("^https?://github\\.com/", "")
                .replaceFirst("/$", "");

        var sb = new StringBuilder();
        sb.append("// IKE Project Registration Fragment\n");
        sb.append("// Managed by ike:register-site — do not edit manually.\n");
        sb.append("//\n");
        sb.append("// project-id: ").append(artifactId).append('\n');
        sb.append("// project-version: ").append(version).append('\n');
        sb.append("// project-url: ").append(siteUrl).append('\n');
        sb.append("// github-url: ").append(githubUrl).append('\n');
        sb.append("// registered: ").append(Instant.now()).append('\n');
        sb.append('\n');
        sb.append("= ").append(name).append('\n');
        sb.append('\n');
        if (description != null && !description.isBlank()) {
            sb.append(description).append('\n');
            sb.append('\n');
        }
        sb.append("[cols=\"1,2\",options=\"autowidth\"]\n");
        sb.append("|===\n");
        sb.append("| Version | ").append(version).append('\n');
        sb.append("| Site | ").append(siteUrl).append('[')
                .append(siteLabel).append("]\n");
        sb.append("| GitHub | ").append(githubUrl).append('[')
                .append(githubLabel).append("]\n");
        sb.append("|===\n");

        if (modules != null && !modules.isEmpty()) {
            sb.append('\n');
            sb.append("=== Modules\n");
            sb.append('\n');
            for (String module : modules) {
                sb.append("* ").append(module).append('\n');
            }
        }

        Path fragmentFile = fragmentDir.resolve(artifactId + ".adoc");
        try {
            Files.writeString(fragmentFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not write fragment: " + fragmentFile, e);
        }
    }

    /**
     * Delete a project registration fragment.
     *
     * @param orgRoot    root of the cloned org repository
     * @param artifactId Maven artifact ID (filename without extension)
     * @throws MojoException if the fragment does not exist or cannot be deleted
     */
    public static void deleteFragment(File orgRoot, String artifactId)
            throws MojoException {
        Path fragmentFile = orgRoot.toPath()
                .resolve(FRAGMENT_DIR).resolve(artifactId + ".adoc");
        if (!Files.exists(fragmentFile)) {
            throw new MojoException(
                    "No registration fragment found: " + fragmentFile);
        }
        try {
            Files.delete(fragmentFile);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not delete fragment: " + fragmentFile, e);
        }
    }

    // ── Index generation ─────────────────────────────────────────────

    /**
     * Regenerate {@code src/site/asciidoc/index.adoc} from all fragments
     * in the {@code projects/} directory.
     *
     * <p>Fragments are sorted alphabetically by filename. The index
     * preamble (title, description) is embedded here as a template.
     *
     * @param orgRoot root of the cloned org repository
     * @throws MojoException if fragments cannot be read or index cannot be written
     */
    public static void regenerateIndex(File orgRoot)
            throws MojoException {
        Path fragmentDir = orgRoot.toPath().resolve(FRAGMENT_DIR);
        List<String> fragmentNames = new ArrayList<>();

        if (Files.isDirectory(fragmentDir)) {
            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(fragmentDir, "*.adoc")) {
                for (Path entry : stream) {
                    fragmentNames.add(entry.getFileName().toString());
                }
            } catch (IOException e) {
                throw new MojoException(
                        "Could not scan fragment directory: " + fragmentDir, e);
            }
        }
        Collections.sort(fragmentNames);

        var sb = new StringBuilder();
        sb.append("= IKE Network\n");
        sb.append(":icons: font\n");
        sb.append('\n');
        sb.append("The Integrated Knowledge Environment (IKE) is a community-driven\n");
        sb.append("platform for knowledge engineering.\n");
        sb.append('\n');

        if (!fragmentNames.isEmpty()) {
            sb.append("== Projects\n");
            sb.append('\n');
            // Relative path from src/site/asciidoc/ to projects/
            for (String fragment : fragmentNames) {
                sb.append("include::../../../projects/")
                        .append(fragment)
                        .append("[leveloffset=+1]\n");
                sb.append('\n');
            }
        }

        Path indexFile = orgRoot.toPath().resolve(INDEX_ADOC);
        try {
            Files.createDirectories(indexFile.getParent());
            Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not write index: " + indexFile, e);
        }
    }

    // ── AsciiDoc rendering ───────────────────────────────────────────

    /**
     * Render the master index AsciiDoc to XHTML using AsciidoctorJ
     * in-process.
     *
     * <p>Output is placed in
     * {@code target/generated-site/xhtml/index.xhtml} within the
     * org repo clone. The Maven site plugin picks this up via
     * {@code <generatedSiteDirectory>}.
     *
     * @param orgRoot root of the cloned org repository
     * @param log     Maven logger
     * @throws MojoException if rendering fails
     */
    public static void renderToXhtml(File orgRoot, Log log)
            throws MojoException {
        Path indexAdoc = orgRoot.toPath().resolve(INDEX_ADOC);
        if (!Files.exists(indexAdoc)) {
            throw new MojoException(
                    "Index AsciiDoc not found: " + indexAdoc);
        }

        Path xhtmlDir = orgRoot.toPath()
                .resolve("target").resolve("generated-site").resolve("xhtml");
        try {
            Files.createDirectories(xhtmlDir);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not create XHTML output directory: " + xhtmlDir, e);
        }

        log.info("Rendering index.adoc to XHTML...");
        try (Asciidoctor asciidoctor = Asciidoctor.Factory.create()) {
            Attributes attrs = Attributes.builder()
                    .attribute("icons", "font")
                    .attribute("source-highlighter", "coderay")
                    .attribute("sectanchors", "true")
                    .attribute("idprefix", "")
                    .attribute("idseparator", "-")
                    .build();

            Options options = Options.builder()
                    .safe(SafeMode.UNSAFE)
                    .backend("html5")
                    .baseDir(indexAdoc.getParent().toFile())
                    .toDir(xhtmlDir.toFile())
                    .standalone(false)
                    .attributes(attrs)
                    .build();

            asciidoctor.convertFile(indexAdoc.toFile(), options);

            // Rename .html → .xhtml for Doxia XHTML parser
            Path htmlFile = xhtmlDir.resolve("index.html");
            Path xhtmlFile = xhtmlDir.resolve("index.xhtml");
            if (Files.exists(htmlFile)) {
                Files.move(htmlFile, xhtmlFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to rename rendered index to .xhtml", e);
        }
    }

    // ── Site build ───────────────────────────────────────────────────

    /**
     * Build the org site by invoking {@code mvn site site:stage} in
     * the cloned org repo.
     *
     * @param orgRoot root of the cloned org repository
     * @param log     Maven logger
     * @throws MojoException if the build fails
     */
    public static void buildSite(File orgRoot, Log log)
            throws MojoException {
        File mvnw = resolveMaven(orgRoot, log);
        log.info("Building org site...");
        ReleaseSupport.exec(orgRoot, log,
                mvnw.getAbsolutePath(), "site", "site:stage", "-B");
    }

    // ── GitHub Pages publishing ──────────────────────────────────────

    /**
     * Publish the staged site to the {@code gh-pages} branch using
     * the same orphan-commit-force-push pattern as
     * {@link ReleaseSupport}.
     *
     * @param orgRoot   root of the cloned org repository
     * @param repoUrl   git remote URL for force-push
     * @param log       Maven logger
     * @throws MojoException if publishing fails
     */
    public static void publishToGhPages(File orgRoot, String repoUrl, Log log)
            throws MojoException {
        Path stagingDir = orgRoot.toPath().resolve("target").resolve("staging");
        if (!Files.isDirectory(stagingDir)) {
            throw new MojoException(
                    "Staging directory does not exist: " + stagingDir
                            + ". Site build may have failed.");
        }

        log.info("Publishing org site to gh-pages...");

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("ike-org-site-publish-");
        } catch (IOException e) {
            throw new MojoException(
                    "Could not create temp directory for publish", e);
        }

        try {
            File tempRoot = tempDir.toFile();

            ReleaseSupport.exec(tempRoot, log,
                    "git", "init");
            ReleaseSupport.exec(tempRoot, log,
                    "git", "checkout", "--orphan", GH_PAGES_BRANCH);

            try {
                ReleaseSupport.copyDirectory(stagingDir, tempDir);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to copy staging dir: " + e.getMessage(), e);
            }

            ReleaseSupport.exec(tempRoot, log,
                    "git", "add", "-A");
            ReleaseSupport.exec(tempRoot, log,
                    "git", "commit", "-m", "site: publish org landing page");

            ReleaseSupport.exec(tempRoot, log,
                    "git", "push", "--force", repoUrl,
                    GH_PAGES_BRANCH + ":" + GH_PAGES_BRANCH);

            log.info("Org site published to gh-pages");
        } finally {
            ReleaseSupport.deleteDirectory(tempDir);
        }
    }

    // ── Repository operations ────────────────────────────────────────

    /**
     * Shallow-clone the org site repository into a temporary directory.
     *
     * @param repoUrl  git remote URL
     * @param branch   branch to clone
     * @param log      Maven logger
     * @return the cloned directory
     * @throws MojoException if cloning fails
     */
    public static File cloneOrgRepo(String repoUrl, String branch, Log log)
            throws MojoException {
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("ike-org-site-");
        } catch (IOException e) {
            throw new MojoException(
                    "Could not create temp directory for clone", e);
        }

        log.info("Cloning " + repoUrl + " (" + branch + ")...");
        ReleaseSupport.exec(tempDir.getParent().toFile(), log,
                "git", "clone", "--depth", "1", "--branch", branch,
                repoUrl, tempDir.getFileName().toString());

        return tempDir.toFile();
    }

    /**
     * Commit all changes in the org repo and push to the remote.
     *
     * @param orgRoot root of the cloned org repository
     * @param message commit message
     * @param branch  branch to push
     * @param log     Maven logger
     * @throws MojoException if commit or push fails
     */
    public static void commitAndPush(File orgRoot, String message,
                                      String branch, Log log)
            throws MojoException {
        ReleaseSupport.exec(orgRoot, log, "git", "add", "-A");

        // Check if there are changes to commit
        String status = ReleaseSupport.execCapture(orgRoot,
                "git", "status", "--porcelain");
        if (status.isBlank()) {
            log.info("No changes to commit (fragment unchanged)");
            return;
        }

        ReleaseSupport.exec(orgRoot, log,
                "git", "commit", "-m", message);
        ReleaseSupport.exec(orgRoot, log,
                "git", "push", "origin", branch);
    }

    /**
     * Run the full registration workflow: clone, write fragment,
     * regenerate index, render, build, commit, publish.
     *
     * @param callerGitRoot   git root of the calling project (for URL resolution)
     * @param log             Maven logger
     * @param orgRepoUrl      git URL of the org site repo
     * @param orgBranch       branch for source content
     * @param artifactId      Maven artifact ID
     * @param name            human-readable project name
     * @param description     project description
     * @param version         release version
     * @param siteUrl         public site URL
     * @param githubUrl       GitHub repository URL
     * @param modules         reactor module names
     * @throws MojoException if any step fails
     */
    public static void registerProject(File callerGitRoot, Log log,
                                        String orgRepoUrl, String orgBranch,
                                        String artifactId, String name,
                                        String description, String version,
                                        String siteUrl, String githubUrl,
                                        List<String> modules)
            throws MojoException {
        File orgRoot = cloneOrgRepo(orgRepoUrl, orgBranch, log);
        try {
            writeFragment(orgRoot, artifactId, name, description,
                    version, siteUrl, githubUrl, modules);
            regenerateIndex(orgRoot);
            renderToXhtml(orgRoot, log);
            buildSite(orgRoot, log);
            commitAndPush(orgRoot,
                    "site: register " + artifactId + " " + version,
                    orgBranch, log);
            publishToGhPages(orgRoot, orgRepoUrl, log);
        } finally {
            ReleaseSupport.deleteDirectory(orgRoot.toPath());
        }
    }

    /**
     * Run the full deregistration workflow: clone, delete fragment,
     * regenerate index, render, build, commit, publish.
     *
     * @param log        Maven logger
     * @param orgRepoUrl git URL of the org site repo
     * @param orgBranch  branch for source content
     * @param artifactId artifact ID to deregister
     * @throws MojoException if any step fails
     */
    public static void deregisterProject(Log log, String orgRepoUrl,
                                          String orgBranch, String artifactId)
            throws MojoException {
        File orgRoot = cloneOrgRepo(orgRepoUrl, orgBranch, log);
        try {
            deleteFragment(orgRoot, artifactId);
            regenerateIndex(orgRoot);
            renderToXhtml(orgRoot, log);
            buildSite(orgRoot, log);
            commitAndPush(orgRoot,
                    "site: deregister " + artifactId,
                    orgBranch, log);
            publishToGhPages(orgRoot, orgRepoUrl, log);
        } finally {
            ReleaseSupport.deleteDirectory(orgRoot.toPath());
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /**
     * Resolve Maven executable. Prefers {@code mvnw} in the repo,
     * falls back to system {@code mvn}.
     */
    private static File resolveMaven(File repoRoot, Log log)
            throws MojoException {
        File mvnw = new File(repoRoot, "mvnw");
        if (mvnw.isFile() && mvnw.canExecute()) {
            return mvnw;
        }
        // Fall back to system Maven
        try {
            String path = ReleaseSupport.execCapture(repoRoot, "which", "mvn");
            return new File(path.trim());
        } catch (Exception e) {
            throw new MojoException(
                    "No Maven wrapper or system 'mvn' found", e);
        }
    }
}
