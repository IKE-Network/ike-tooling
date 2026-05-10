package network.ike.plugin;

import org.apache.maven.api.Project;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render a "Built With" page from the CycloneDX SBOM and a curated
 * narrative supplement (ike-issues#336).
 *
 * <p>The user-facing rename of "Third-Party Notices" — friendlier
 * scannable name with the same content shape: per-module mechanical
 * inventory + project-wide curated supplement.
 *
 * <p>Sources, in priority order:
 * <ol>
 *   <li><strong>Per-module mechanical</strong>: reads
 *       {@code target/bom.json} (CycloneDX, produced at
 *       {@code package} per ike-issues#333) and lists this module's
 *       direct dependencies with SPDX licenses.</li>
 *   <li><strong>Project-wide curated supplement</strong>: reads
 *       {@code src/main/built-with/supplement.yaml} from the
 *       project root, and if not found, walks up the filesystem
 *       to find one at a parent reactor. This is where curated
 *       narrative lives — the things mechanical reports can't see
 *       (Sentry skin, Kroki, fonts inside artifacts, frontend
 *       assets, external PDF renderers).</li>
 * </ol>
 *
 * <p>The supplement file is a simple YAML schema:
 *
 * <pre>{@code
 * schema: 1
 * sections:
 *   - heading: "Maven Site skin"
 *     components:
 *       - name: Sentry Maven Skin
 *         url: https://github.com/sentrysoftware/maven-skins
 *         license: Apache-2.0
 *         role: Provides the rendered HTML chrome.
 * }</pre>
 *
 * <p>Output: {@code target/generated-site/asciidoc/built-with.adoc},
 * which Maven Site renders to {@code target/site/built-with.html}
 * with the project's skin chrome.
 *
 * <p>Skip with {@code -Dike.skip.built-with=true}.
 *
 * <pre>{@code
 * mvn package                       # produces bom.json
 * mvn ike:built-with                # produces built-with.adoc
 * mvn site                          # renders built-with.html
 * }</pre>
 *
 * @see RenderSpdxLicensesMojo  the SPDX-grouped licenses report
 * @see GenerateBomMojo         the IKE-specific BOM (different
 *                              concept — Maven dependency BOM)
 */
@Mojo(name = "built-with", defaultPhase = "pre-site")
public class BuiltWithMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Path to the CycloneDX SBOM produced by
     * {@code cyclonedx-maven-plugin} at {@code package} phase
     * (ike-issues#333). Absent → log a warning and skip.
     */
    @Parameter(property = "ike.bom.path",
            defaultValue = "${project.build.directory}/bom.json")
    File bomPath;

    /**
     * Path to the curated narrative supplement YAML file. Default
     * is {@code src/main/built-with/supplement.yaml} at the
     * current module's basedir. The resolver tries three locations
     * in order:
     * <ol>
     *   <li>This path verbatim (per-project local override).</li>
     *   <li>Walk up from project basedir looking for the same path
     *       (per-reactor supplement at the workspace root).</li>
     *   <li>{@link #unpackedSupplementPath} — fallback to the
     *       platform-wide supplement unpacked from the
     *       {@code ike-build-standards:built-with:zip} classifier
     *       (#340). External consumers get this for free without
     *       authoring their own supplement.</li>
     * </ol>
     * Absent in all three → the mechanical-only page is rendered
     * (still useful per-module).
     */
    @Parameter(property = "ike.built-with.supplement",
            defaultValue = "${project.basedir}/src/main/built-with/supplement.yaml")
    File supplementPath;

    /**
     * Path to the platform-wide supplement unpacked from the
     * {@code ike-build-standards:built-with:zip} classifier (#340).
     * Used as the third-priority fallback after the per-project and
     * walk-up locations. Defaults to
     * {@code target/built-with-supplement.yaml}, matching where
     * {@code maven-dependency-plugin}'s
     * {@code unpack-built-with-supplement} execution drops the
     * unpacked file at pre-site phase.
     */
    @Parameter(property = "ike.built-with.unpacked-supplement",
            defaultValue = "${project.build.directory}/supplement.yaml")
    File unpackedSupplementPath;

    /**
     * Output AsciiDoc file. Default is the standard generated-site
     * location which Maven Site picks up at site:site time and
     * renders to {@code target/site/built-with.html}.
     */
    @Parameter(property = "ike.built-with.output",
            defaultValue = "${project.build.directory}/generated-site/asciidoc/built-with.adoc")
    File outputPath;

    /** Skip generation. */
    @Parameter(property = "ike.skip.built-with", defaultValue = "false")
    boolean skip;

    /** Project artifact ID, used in the rendered page title. */
    @Parameter(defaultValue = "${project.artifactId}", readonly = true)
    String projectArtifactId;

    /** Project version, used in the rendered page title. */
    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    /**
     * The current Maven project — used to find this module's basedir
     * for walking up to a parent reactor's supplement.yaml.
     */
    @Inject
    private Project project;

    /** Creates this goal instance. */
    public BuiltWithMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("ike:built-with skipped "
                    + "(-Dike.skip.built-with=true)");
            return;
        }

        if (!bomPath.exists()) {
            getLog().warn("Skipping ike:built-with: SBOM not found at "
                    + bomPath + ". Run 'mvn package' first to produce "
                    + "the SBOM (ike-issues#333).");
            return;
        }

        // Resolve the supplement: prefer per-module path, fall back
        // to walking up to a parent reactor's supplement (project-
        // wide curated content shared by all modules).
        File effectiveSupplement = resolveSupplement();

        Map<String, List<Component>> grouped;
        try {
            grouped = parseAndGroupBom(bomPath);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not parse SBOM at " + bomPath + ": "
                            + e.getMessage(), e);
        }

        Supplement supplement = null;
        if (effectiveSupplement != null) {
            try {
                supplement = parseSupplement(effectiveSupplement);
                getLog().info("  Using curated supplement from "
                        + effectiveSupplement);
            } catch (IOException e) {
                getLog().warn("  Could not parse supplement at "
                        + effectiveSupplement + ": " + e.getMessage()
                        + ". Continuing with mechanical-only content.");
            }
        } else {
            getLog().info("  No supplement.yaml found — rendering "
                    + "mechanical-only built-with.adoc. To add curated "
                    + "narrative, create src/main/built-with/supplement.yaml "
                    + "at the reactor root.");
        }

        String adoc = renderAdoc(grouped, supplement);

        File parent = outputPath.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new MojoException(
                    "Could not create output directory: " + parent);
        }

        try {
            Files.writeString(outputPath.toPath(), adoc,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not write " + outputPath + ": " + e.getMessage(),
                    e);
        }

        getLog().info("Wrote built-with page: " + outputPath);
        getLog().info("  Mechanical: "
                + grouped.values().stream().mapToInt(List::size).sum()
                + " components in "
                + grouped.size() + " license groups");
        if (supplement != null) {
            getLog().info("  Curated: " + supplement.sections.size()
                    + " supplement sections");
        }
    }

    /**
     * Find the supplement file by walking up from the project basedir.
     * Returns the parameter path if it exists, otherwise walks up
     * through ancestor directories looking for the same relative
     * path (typically a reactor root).
     *
     * @return the resolved supplement file, or {@code null} if not found
     */
    private File resolveSupplement() {
        // (1) Per-project local override.
        if (supplementPath.exists() && supplementPath.isFile()) {
            return supplementPath;
        }
        // (2) Walk up from project basedir looking for the same
        // relative path — this is how submodules pick up the
        // reactor's supplement.yaml without per-module configuration.
        Path basedir = project.getBasedir();
        Path relativeFromBasedir = basedir.relativize(supplementPath.toPath());
        Path parent = basedir.getParent();
        while (parent != null) {
            Path candidate = parent.resolve(relativeFromBasedir);
            if (Files.isRegularFile(candidate)) {
                return candidate.toFile();
            }
            parent = parent.getParent();
        }
        // (3) Platform-wide fallback (ike-issues#340): the supplement
        // unpacked from the ike-build-standards:built-with:zip
        // classifier. This is what gives external consumers the
        // Curated narrative section without authoring their own
        // supplement.yaml.
        if (unpackedSupplementPath.exists() && unpackedSupplementPath.isFile()) {
            return unpackedSupplementPath;
        }
        return null;
    }

    /**
     * Parse the SBOM JSON and group components by SPDX expression.
     * Same logic as RenderSpdxLicensesMojo's grouping; duplicated
     * here to keep the mojos independent.
     *
     * @param bom path to {@code bom.json}
     * @return map from SPDX expression to component list
     * @throws IOException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Component>> parseAndGroupBom(File bom)
            throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(bom.toPath(),
                StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) yaml.load(reader);
        }
        if (root == null) return new LinkedHashMap<>();
        List<Map<String, Object>> components =
                (List<Map<String, Object>>) root.get("components");
        if (components == null) return new LinkedHashMap<>();

        Map<String, List<Component>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> c : components) {
            String spdx = extractSpdxExpression(c);
            grouped.computeIfAbsent(spdx, k -> new ArrayList<>())
                    .add(new Component(
                            stringOrEmpty(c.get("group")),
                            stringOrEmpty(c.get("name")),
                            stringOrEmpty(c.get("version")),
                            stringOrEmpty(c.get("description"))));
        }
        return grouped;
    }

    @SuppressWarnings("unchecked")
    private String extractSpdxExpression(Map<String, Object> c) {
        Object licensesObj = c.get("licenses");
        if (!(licensesObj instanceof List<?> raw) || raw.isEmpty()) {
            return "(no license declared)";
        }
        List<Map<String, Object>> entries = (List<Map<String, Object>>) raw;
        if (entries.size() == 1) {
            Object exp = entries.get(0).get("expression");
            if (exp != null) return exp.toString();
        }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> license =
                    (Map<String, Object>) entry.get("license");
            if (license == null) continue;
            Object id = license.get("id");
            Object name = license.get("name");
            if (id != null) ids.add(id.toString());
            else if (name != null) ids.add(name.toString());
        }
        if (ids.isEmpty()) return "(no license declared)";
        if (ids.size() == 1) return ids.get(0);
        Collections.sort(ids);
        return String.join(" OR ", ids);
    }

    /**
     * Parse the supplement YAML.
     *
     * @param file the supplement file
     * @return the parsed supplement
     * @throws IOException if the file cannot be read
     */
    @SuppressWarnings("unchecked")
    private Supplement parseSupplement(File file) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file.toPath(),
                StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) yaml.load(reader);
        }
        if (root == null) {
            return new Supplement(new ArrayList<>());
        }

        List<SupplementSection> sections = new ArrayList<>();
        Object sectionsObj = root.get("sections");
        if (sectionsObj instanceof List<?> rawSections) {
            for (Object sec : rawSections) {
                if (!(sec instanceof Map<?, ?> rawSec)) continue;
                Map<String, Object> secMap = (Map<String, Object>) rawSec;
                String heading = stringOrEmpty(secMap.get("heading"));

                List<SupplementComponent> comps = new ArrayList<>();
                Object compsObj = secMap.get("components");
                if (compsObj instanceof List<?> rawComps) {
                    for (Object comp : rawComps) {
                        if (!(comp instanceof Map<?, ?> rawComp)) continue;
                        Map<String, Object> compMap =
                                (Map<String, Object>) rawComp;
                        comps.add(new SupplementComponent(
                                stringOrEmpty(compMap.get("name")),
                                stringOrEmpty(compMap.get("url")),
                                stringOrEmpty(compMap.get("license")),
                                stringOrEmpty(compMap.get("role"))));
                    }
                }
                sections.add(new SupplementSection(heading, comps));
            }
        }
        return new Supplement(sections);
    }

    /**
     * Render the built-with page to AsciiDoc.
     *
     * @param grouped    SBOM components by SPDX expression
     * @param supplement curated narrative (may be null)
     * @return AsciiDoc page source
     */
    private String renderAdoc(Map<String, List<Component>> grouped,
                              Supplement supplement) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Built With\n");
        sb.append(":icons: font\n");
        sb.append(":source-highlighter: coderay\n");
        sb.append(":toc: preamble\n");
        sb.append(":toclevels: 2\n\n");

        sb.append("Open-source software that `")
                .append(projectArtifactId).append("` ")
                .append(projectVersion)
                .append(" depends on, links against, ships within, "
                        + "or invokes at runtime.\n\n");

        sb.append("Three layers of attribution ship with each release:\n\n");
        sb.append("* link:bom.json[Software Bill of Materials "
                + "(CycloneDX, JSON)] — full transitive dependency "
                + "graph with SPDX-normalized licenses and artifact "
                + "hashes. Ingestible by Dependency-Track, Trivy, "
                + "Snyk, GitHub's dependency graph.\n");
        sb.append("* link:licenses.html[Licenses (SPDX)] — "
                + "human-readable SPDX-grouped view of declared "
                + "dependencies, generated from `bom.json` (#335).\n");
        sb.append("* This page — curated companion covering what "
                + "mechanical reports can't see (Maven Site skin, "
                + "external services, fonts inside artifacts, "
                + "frontend assets in rendered HTML).\n\n");

        // Curated narrative (if supplement.yaml provided)
        if (supplement != null && !supplement.sections.isEmpty()) {
            sb.append("== Curated narrative\n\n");
            sb.append("Components covered by the project-wide "
                    + "supplement at `src/main/built-with/supplement.yaml`. "
                    + "These are the components that don't appear in "
                    + "`bom.json` because they aren't Maven artifacts "
                    + "(external services, fonts inside classifier "
                    + "ZIPs, runtime binaries, frontend assets).\n\n");
            for (SupplementSection section : supplement.sections) {
                sb.append("=== ").append(section.heading).append("\n\n");
                if (section.components.isEmpty()) continue;
                sb.append("|===\n");
                sb.append("| Component | License | Role\n\n");
                for (SupplementComponent c : section.components) {
                    if (!c.url.isEmpty()) {
                        sb.append("| ").append(c.url).append("[")
                                .append(escapeAdoc(c.name)).append("]\n");
                    } else {
                        sb.append("| ").append(escapeAdoc(c.name)).append("\n");
                    }
                    if (!c.license.isEmpty()) {
                        sb.append("| `").append(escapeAdoc(c.license))
                                .append("`\n");
                    } else {
                        sb.append("| _(no license declared)_\n");
                    }
                    sb.append("| ").append(escapeAdoc(c.role)).append("\n\n");
                }
                sb.append("|===\n\n");
            }
        }

        // Mechanical inventory
        sb.append("== Mechanical inventory\n\n");
        sb.append("Direct dependencies of this module, grouped by SPDX "
                + "expression. Generated from `bom.json` at build time.\n\n");
        if (grouped.isEmpty()) {
            sb.append("_No declared dependencies in this module's SBOM._\n\n");
        } else {
            sb.append("|===\n");
            sb.append("| SPDX Expression | Components\n\n");
            int total = 0;
            for (Map.Entry<String, List<Component>> g : grouped.entrySet()) {
                String spdx = g.getKey();
                String formatted = (spdx.startsWith("(") && spdx.endsWith(")"))
                        ? "_" + spdx + "_"
                        : "`" + spdx + "`";
                sb.append("| ").append(formatted).append("\n");
                sb.append("| ").append(g.getValue().size()).append("\n\n");
                total += g.getValue().size();
            }
            sb.append("| *Total* | *").append(total).append("*\n");
            sb.append("|===\n\n");
            sb.append("For full per-component detail (group, artifact, "
                    + "version, hashes, transitive deps), see "
                    + "link:bom.json[bom.json] or "
                    + "link:licenses.html[licenses.html].\n\n");
        }

        sb.append("== Related\n\n");
        sb.append("* link:index.html[site index]\n");
        sb.append("* https://github.com/IKE-Network/ike-issues/issues/336"
                + "[ike-issues#336] — the issue that introduced this "
                + "page (rename of the legacy \"Third-Party Notices\" "
                + "to friendlier \"Built With\").\n");
        return sb.toString();
    }

    /**
     * Escape a string for inline-code use in AsciiDoc.
     *
     * @param s input
     * @return escaped string
     */
    private String escapeAdoc(String s) {
        if (s == null) return "";
        return s.replace("`", "\\`");
    }

    /**
     * Coerce a possibly-null map value to a non-null string.
     *
     * @param o the value
     * @return the string value, or empty if null
     */
    private static String stringOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    /** A single component projected from the SBOM. */
    private record Component(String group, String name, String version,
                             String description) { }

    /** Parsed supplement.yaml. */
    private record Supplement(List<SupplementSection> sections) { }

    /** A heading + list of components from supplement.yaml. */
    private record SupplementSection(String heading,
                                     List<SupplementComponent> components) { }

    /** A single curated component entry. */
    private record SupplementComponent(String name, String url,
                                       String license, String role) { }
}
