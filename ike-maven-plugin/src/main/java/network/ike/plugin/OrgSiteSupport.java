package network.ike.plugin;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.MojoException;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;

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

    /**
     * Default Git URL for the org-site SOURCE repository. The source
     * repo has the Maven pom + src/site/ tree and is where fragments
     * are written.
     */
    static final String SRC_REPO_DEFAULT =
            "https://github.com/IKE-Network/ike-network-site.git";

    /**
     * Default Git URL for the org-site PUBLISH repository. The publish
     * repo holds only the rendered HTML and is what GitHub Pages
     * serves at https://ike.network/. Publish flow: clone, wipe
     * everything except .git, copy target/site/ from the source build,
     * commit, push.
     */
    static final String PUB_REPO_DEFAULT =
            "https://github.com/IKE-Network/IKE-Network.github.io.git";

    /**
     * @deprecated Use {@link #SRC_REPO_DEFAULT} for the source repo
     *             and {@link #PUB_REPO_DEFAULT} for the publish repo.
     *             Pre-#367 the workflow assumed a single repo serving
     *             both roles; that never worked because the publish
     *             repo has no pom. Kept for legacy callers.
     */
    @Deprecated
    static final String ORG_REPO_DEFAULT =
            "https://github.com/IKE-Network/IKE-Network.github.io.git";

    /** Directory within the org repo that holds project fragments. */
    static final String FRAGMENT_DIR = "projects";

    /** Path to the master index AsciiDoc source, relative to repo root. */
    private static final String INDEX_ADOC = "src/site/asciidoc/index.adoc";

    /** Branch used for rendered site content (GitHub Pages source). */
    private static final String GH_PAGES_BRANCH = "gh-pages";

    /**
     * The IKE foundation projects, in parent-tier order, mapped to
     * their Maven Central coordinates ({@code groupId:artifactId}).
     *
     * <p>Membership drives the generated landing page: a project in
     * this map renders under the {@code Foundation} section with a
     * Maven Central version badge; every other registered project
     * renders under {@code Examples}. The foundation is a small,
     * deliberately fixed set — adding a member is a release-cascade
     * decision, not a routine registration, so it lives in code.
     */
    static final Map<String, String> FOUNDATION = foundationCoordinates();

    private static Map<String, String> foundationCoordinates() {
        // Order here drives the rendered Foundation section ordering on
        // https://ike.network/. Slot each entry where the project sits
        // in the dependency direction (upstream → downstream): every
        // entry below depends on every entry above.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ike-base-parent",         "network.ike:ike-base-parent");
        // Tier-0 zero-dependency value types (ConstantBackedEnum,
        // EnumDefinition, ReleasePolicy). Depended on by ike-tooling
        // (IKE-Network/ike-issues#498). Listed early since it sits
        // above ike-tooling in the dependency direction.
        m.put("ike-java-support",        "network.ike:ike-java-support");
        m.put("ike-tooling",             "network.ike.tooling:ike-tooling");
        m.put("ike-docs",                "network.ike.docs:ike-docs");
        // Standalone Tier-0 artifact consumed by ike-platform at
        // workspace runtime (#460). Listed before platform so the
        // section reads in dependency order.
        m.put("ike-workspace-extension", "network.ike.tooling:ike-workspace-extension");
        // Maven 4 build extension consumers register via
        // .mvn/extensions.xml. Validates canonical ${G·A} pins and
        // the ${G·A·policy} ladder shipped in #498. Not in the
        // consumer-coordinate dependency direction (registered, not
        // resolved) so its order relative to its siblings is by
        // logical grouping, not by dep direction.
        m.put("ike-version-management-extension",
                "network.ike.tooling:ike-version-management-extension");
        m.put("ike-platform",            "network.ike.platform:ike-platform");
        return Collections.unmodifiableMap(m);
    }

    /**
     * Build the Maven Central version-badge AsciiDoc for a project.
     *
     * @param artifactId the project artifact ID
     * @return an AsciiDoc {@code image:} macro for foundation
     *         projects, or {@code null} if the project is not a
     *         foundation member
     */
    static String mavenCentralBadge(String artifactId) {
        String coordinates = FOUNDATION.get(artifactId);
        if (coordinates == null) {
            return null;
        }
        String path = coordinates.replace(':', '/');
        return "image:https://img.shields.io/maven-central/v/" + path
                + "[Maven Central,link="
                + "https://central.sonatype.com/artifact/" + path + "]";
    }

    /**
     * Kroki rendering service used to turn diagram source into SVG at
     * page-render time. The encoded source is appended to
     * {@code <KROKI_BASE>/<diagram-type>/svg/<encoded>}.
     */
    static final String KROKI_BASE = "https://kroki.komet.sh";

    /**
     * GraphViz source for the foundation build/release dependency
     * diagram rendered into the org-site landing page preamble.
     *
     * <p>Edges:
     * <ul>
     *   <li>Dashed {@code ike-base-parent → ike-java-support} — parent
     *       inheritance only; downstream Tier-1 members inherit it too
     *       (the dashed edge is the apex of that inheritance chain).</li>
     *   <li>Solid arrows trace the build/release dependency direction
     *       (upstream → downstream): jsup → tooling, jsup → wsext,
     *       tooling → docs, docs → platform, wsext → platform.</li>
     *   <li>{@code ike-version-management-extension} sits in a dotted
     *       cluster on its own — registered at every consumer build,
     *       not resolved in the dependency direction (#470/#472).</li>
     * </ul>
     */
    static final String FOUNDATION_DIAGRAM =
            "digraph foundation {\n"
            + "  rankdir=TB;\n"
            + "  bgcolor=transparent;\n"
            + "  compound=true;\n"
            + "  node [shape=box, style=\"rounded,filled\","
                    + " fontname=\"Helvetica\", fontsize=11,"
                    + " fillcolor=\"#e8f5e9\", color=\"#2e7d32\"];\n"
            + "  edge [fontname=\"Helvetica\", fontsize=9,"
                    + " color=\"#555555\"];\n"
            + "\n"
            + "  base  [label=\"ike-base-parent\\n(parent only)\","
                    + " fillcolor=\"#fff3e0\", color=\"#e65100\"];\n"
            + "  jsup  [label=\"ike-java-support\\n(value types)\"];\n"
            + "  tool  [label=\"ike-tooling\"];\n"
            + "  wsext [label=\"ike-workspace-extension\"];\n"
            + "  docs  [label=\"ike-docs\"];\n"
            + "  plat  [label=\"ike-platform\"];\n"
            + "\n"
            + "  base -> jsup [style=dashed, label=\" parent\"];\n"
            + "  jsup -> tool;\n"
            + "  jsup -> wsext;\n"
            + "  tool -> docs;\n"
            + "  docs -> plat;\n"
            + "  wsext -> plat;\n"
            + "\n"
            + "  subgraph cluster_vme {\n"
            + "    style=dotted;\n"
            + "    color=\"#6a1b9a\";\n"
            + "    label=\"registered at every consumer build\\n"
                    + "validates ${G·A} pins and ${G·A·policy}\";\n"
            + "    fontsize=10;\n"
            + "    vme [label=\"ike-version-management-extension\","
                    + " fillcolor=\"#f3e5f5\", color=\"#6a1b9a\"];\n"
            + "  }\n"
            + "}\n";

    /**
     * Build a Kroki SVG URL for the given diagram source. Kroki encodes
     * the source as URL-safe base64 of its zlib-compressed bytes; the
     * client emits an {@code image::URL[]} reference and the browser
     * fetches the rendered SVG at page-load time.
     *
     * <p>Used by {@link #regenerateIndex} to embed the foundation
     * dependency diagram into the landing-page preamble without
     * requiring a Kroki extension in the asciidoctor-parser-doxia
     * pipeline that renders the org site.
     *
     * @param diagramType Kroki diagram type (e.g. {@code "graphviz"},
     *                    {@code "plantuml"})
     * @param source      raw diagram source
     * @return a fully-qualified {@code https://...} URL that serves the
     *         rendered SVG
     */
    static String krokiUrl(String diagramType, String source) {
        byte[] raw = source.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        var baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        String encoded = Base64.getUrlEncoder()
                .encodeToString(baos.toByteArray());
        return KROKI_BASE + "/" + diagramType + "/svg/" + encoded;
    }

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
        sb.append("// Managed by ").append(IkeGoal.SITE_PUBLISH.qualified())
                .append(" — do not edit manually.\n");
        sb.append("//\n");
        sb.append("// project-id: ").append(artifactId).append('\n');
        sb.append("// project-version: ").append(version).append('\n');
        sb.append("// project-url: ").append(siteUrl).append('\n');
        sb.append("// github-url: ").append(githubUrl).append('\n');
        sb.append("// registered: ").append(Instant.now()).append('\n');
        sb.append('\n');
        sb.append("= ").append(name).append('\n');
        sb.append('\n');
        String badge = mavenCentralBadge(artifactId);
        if (badge != null) {
            sb.append(badge).append('\n');
            sb.append('\n');
        }
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
     * <p>Registered projects are split into two sections: foundation
     * members (see {@link #FOUNDATION}, rendered in parent-tier order)
     * and everything else (the examples, rendered alphabetically). The
     * index preamble and section intros are embedded here as a
     * template.
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

        // Foundation members in parent-tier order; everything else
        // (the examples) keeps the alphabetical order.
        List<String> foundation = new ArrayList<>();
        for (String id : FOUNDATION.keySet()) {
            String fragment = id + ".adoc";
            if (fragmentNames.contains(fragment)) {
                foundation.add(fragment);
            }
        }
        List<String> examples = new ArrayList<>();
        for (String fragment : fragmentNames) {
            if (!foundation.contains(fragment)) {
                examples.add(fragment);
            }
        }

        var sb = new StringBuilder();
        sb.append("= IKE Network\n");
        sb.append(":icons: font\n");
        sb.append('\n');
        sb.append("The IKE Network (Integrated Knowledge Exchange) is a sociotechnical\n");
        sb.append("fabric where knowledge compounds.\n");
        sb.append('\n');

        if (!foundation.isEmpty()) {
            sb.append("== Foundation\n");
            sb.append('\n');
            sb.append("The IKE foundation — published to Maven Central and\n");
            sb.append("inheritable by any project.\n");
            sb.append('\n');
            sb.append("The foundation members fall into two layered orderings.\n");
            sb.append("`ike-base-parent` sits at the apex of the *parent*\n");
            sb.append("inheritance chain — every other foundation artifact\n");
            sb.append("inherits from it. `ike-platform` sits at the terminus\n");
            sb.append("of the *build-and-release* dependency chain — releases\n");
            sb.append("propagate through it last.\n");
            sb.append('\n');
            sb.append(".Build/release dependency order\n");
            sb.append("image::")
                    .append(krokiUrl("graphviz", FOUNDATION_DIAGRAM))
                    .append("[Build/release dependency order]\n");
            sb.append('\n');
            sb.append("Members at the same level have no dependency on each\n");
            sb.append("other — `ike-tooling` and `ike-workspace-extension`\n");
            sb.append("can release in either order or in parallel.\n");
            sb.append('\n');
            appendIncludes(sb, foundation);
        }

        if (!examples.isEmpty()) {
            sb.append("== Examples\n");
            sb.append('\n');
            sb.append("Reference projects that show how to consume the IKE\n");
            sb.append("foundation. They are deliberately *not* published to\n");
            sb.append("Maven Central: they are worked examples to read and\n");
            sb.append("copy, not libraries to depend on. Publishing them as\n");
            sb.append("artifacts would invite accidental coupling to code\n");
            sb.append("that exists only to illustrate a pattern.\n");
            sb.append('\n');
            appendIncludes(sb, examples);
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

    /**
     * Append {@code include::} directives for the given fragment
     * filenames to the index buffer, one per line with a trailing
     * blank line. The path is relative to {@code src/site/asciidoc/}.
     *
     * @param sb        the index buffer being assembled
     * @param fragments fragment filenames (e.g. {@code ike-docs.adoc})
     */
    private static void appendIncludes(StringBuilder sb,
                                       List<String> fragments) {
        for (String fragment : fragments) {
            sb.append("include::../../../projects/")
                    .append(fragment)
                    .append("[leveloffset=+1]\n");
            sb.append('\n');
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
     * Build the org site by invoking {@code mvn site} in the cloned
     * org repo. Output lands at {@code target/site/}.
     *
     * <p>Note: only {@code site} is invoked, not {@code site:stage}.
     * {@code site:stage} requires {@code <distributionManagement>}
     * in the project pom to compute relative paths; the org-site
     * source pom intentionally omits it because the org site has no
     * Nexus deployment target — it ships only as static HTML to the
     * publish repo. {@code mvn site} alone produces the rendered
     * {@code target/site/} tree that publishToPubRepo then ships.
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
                mvnw.getAbsolutePath(), "site", "-B");
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
     * Run the full registration workflow on a two-repo org-site
     * layout (#367):
     *
     * <ol>
     *   <li>Clone the SOURCE repo ({@code srcRepoUrl}) — has the
     *       Maven pom and src/site/ tree.</li>
     *   <li>Write the per-project fragment into
     *       {@code projects/&lt;artifactId&gt;.adoc}.</li>
     *   <li>Regenerate the master index from all fragments.</li>
     *   <li>Render the index AsciiDoc to XHTML (for doxia).</li>
     *   <li>Run {@code mvn site} to produce {@code target/site/}.</li>
     *   <li>Commit + push the source repo so the fragment + index
     *       are persisted.</li>
     *   <li>Publish {@code target/site/} to the PUBLISH repo
     *       ({@code pubRepoUrl}) by cloning, wiping non-Git
     *       contents, copying the build output, committing, and
     *       pushing.</li>
     * </ol>
     *
     * <p>Pre-#367 this method assumed a single repo for both roles
     * and ran {@code mvn site} inside a repo that had no pom —
     * which silently failed every release that tried to auto-update
     * the landing page. The two-repo split mirrors the README in
     * IKE-Network.github.io and the manual flow operators have been
     * using all along.
     *
     * @param callerGitRoot git root of the calling project
     * @param log           Maven logger
     * @param srcRepoUrl    git URL of the source repo (has pom)
     * @param pubRepoUrl    git URL of the publish repo (rendered HTML)
     * @param srcBranch     branch in the source repo
     * @param pubBranch     branch in the publish repo
     * @param artifactId    Maven artifact ID
     * @param name          human-readable project name
     * @param description   project description
     * @param version       release version
     * @param siteUrl       public site URL
     * @param githubUrl     GitHub repository URL
     * @param modules       reactor module names
     * @throws MojoException if any step fails
     */
    public static void registerProject(File callerGitRoot, Log log,
                                        String srcRepoUrl, String pubRepoUrl,
                                        String srcBranch, String pubBranch,
                                        String artifactId, String name,
                                        String description, String version,
                                        String siteUrl, String githubUrl,
                                        List<String> modules)
            throws MojoException {
        File srcRoot = cloneOrgRepo(srcRepoUrl, srcBranch, log);
        try {
            writeFragment(srcRoot, artifactId, name, description,
                    version, siteUrl, githubUrl, modules);
            regenerateIndex(srcRoot);
            // No renderToXhtml here: the source pom has
            // asciidoctor-parser-doxia-module wired into
            // maven-site-plugin's plugin dependencies, so buildSite's
            // `mvn site site:stage` renders src/site/asciidoc/*.adoc
            // natively. A separate pre-render to
            // target/generated-site/xhtml/index.xhtml produces a
            // second source for index.html and trips the site plugin's
            // duplicate-output check.
            buildSite(srcRoot, log);
            commitAndPush(srcRoot,
                    "site: register " + artifactId + " " + version,
                    srcBranch, log);
            publishToPubRepo(srcRoot, pubRepoUrl, pubBranch,
                    artifactId, version, log);
        } finally {
            ReleaseSupport.deleteDirectory(srcRoot.toPath());
        }
    }

    /**
     * Run the full deregistration workflow against the two-repo
     * org-site layout (#367 — mirror of
     * {@link #registerProject(File, Log, String, String, String,
     *        String, String, String, String, String, String,
     *        String, List)}).
     *
     * @param log        Maven logger
     * @param srcRepoUrl git URL of the source repo (has pom)
     * @param pubRepoUrl git URL of the publish repo (rendered HTML)
     * @param srcBranch  branch in the source repo
     * @param pubBranch  branch in the publish repo
     * @param artifactId artifact ID to deregister
     * @throws MojoException if any step fails
     */
    public static void deregisterProject(Log log,
                                          String srcRepoUrl, String pubRepoUrl,
                                          String srcBranch, String pubBranch,
                                          String artifactId)
            throws MojoException {
        File srcRoot = cloneOrgRepo(srcRepoUrl, srcBranch, log);
        try {
            deleteFragment(srcRoot, artifactId);
            regenerateIndex(srcRoot);
            // No renderToXhtml — see registerProject for the rationale.
            buildSite(srcRoot, log);
            commitAndPush(srcRoot,
                    "site: deregister " + artifactId,
                    srcBranch, log);
            publishToPubRepo(srcRoot, pubRepoUrl, pubBranch,
                    artifactId, "(deregister)", log);
        } finally {
            ReleaseSupport.deleteDirectory(srcRoot.toPath());
        }
    }

    /**
     * Publish the {@code target/site/} contents from a freshly-
     * built source repo to a separate publish repo.
     *
     * <p>Clones the publish repo at {@code pubBranch}, wipes every
     * file/directory except {@code .git/}, copies the entire
     * {@code target/site/} tree into the clone root, commits with a
     * descriptive message, and pushes. Because the source build
     * emits {@code .nojekyll} and {@code CNAME} (from
     * {@code src/site/resources/}), those land naturally — no
     * special preservation needed.
     *
     * <p>Replaces the pre-#367 {@code publishToGhPages} flow, which
     * pushed to a {@code gh-pages} branch of the SAME repo as the
     * source. The new flow correctly addresses the IKE-Network
     * org-site layout where source and publish live in different
     * repos and the publish repo serves from {@code main}.
     *
     * @param srcRoot     the source repo with {@code target/site/}
     *                    built
     * @param pubRepoUrl  git URL of the publish repo
     * @param pubBranch   branch to push to (typically {@code main})
     * @param artifactId  for the commit message
     * @param version     for the commit message
     * @param log         Maven logger
     * @throws MojoException if any step fails
     */
    public static void publishToPubRepo(File srcRoot, String pubRepoUrl,
                                         String pubBranch,
                                         String artifactId, String version,
                                         Log log)
            throws MojoException {
        Path siteDir = srcRoot.toPath()
                .resolve("target").resolve("site");
        if (!Files.isDirectory(siteDir)) {
            throw new MojoException(
                    "Source repo's target/site/ does not exist: "
                            + siteDir + ". buildSite likely failed.");
        }

        File pubRoot = cloneOrgRepo(pubRepoUrl, pubBranch, log);
        try {
            // Wipe everything in pubRoot except .git/. The source
            // build's target/site/ contains .nojekyll and CNAME
            // (from src/site/resources/), so we don't need to
            // preserve anything pre-existing.
            try (Stream<Path> entries = Files.list(pubRoot.toPath())) {
                for (Path entry : entries.toList()) {
                    if (entry.getFileName().toString().equals(".git")) {
                        continue;
                    }
                    if (Files.isDirectory(entry)) {
                        ReleaseSupport.deleteDirectory(entry);
                    } else {
                        Files.delete(entry);
                    }
                }
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to clear publish repo before copy: "
                                + e.getMessage(), e);
            }

            try {
                ReleaseSupport.copyDirectory(siteDir, pubRoot.toPath());
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to copy target/site to publish repo: "
                                + e.getMessage(), e);
            }

            // Commit + push. If the rendered output happens to be
            // byte-identical to the previous publish (rare —
            // outputTimestamp moves), `git commit` will fail with
            // 'nothing to commit'; tolerate that as a no-op
            // publish.
            ReleaseSupport.exec(pubRoot, log, "git", "add", "-A");
            String status;
            try {
                status = ReleaseSupport.execCapture(pubRoot,
                        "git", "status", "--porcelain").trim();
            } catch (MojoException e) {
                status = "";
            }
            if (status.isEmpty()) {
                log.info("  Publish repo unchanged after rebuild — "
                        + "nothing to push.");
                return;
            }
            String message = "site: publish "
                    + artifactId + " " + version
                    + " (auto-register, #367)";
            ReleaseSupport.exec(pubRoot, log,
                    "git", "commit", "-m", message);
            ReleaseSupport.exec(pubRoot, log,
                    "git", "push", "origin", pubBranch);
            log.info("  Org-site updated: "
                    + artifactId + " " + version);
        } finally {
            ReleaseSupport.deleteDirectory(pubRoot.toPath());
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
