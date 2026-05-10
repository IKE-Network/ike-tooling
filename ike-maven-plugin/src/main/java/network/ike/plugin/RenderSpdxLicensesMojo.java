package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Render an SPDX-grouped {@code licenses.adoc} from the CycloneDX
 * SBOM (ike-issues#335).
 *
 * <p>Reads {@code target/bom.json} (produced by
 * {@code cyclonedx-maven-plugin} at {@code package} phase per
 * ike-issues#333), groups every component by its SPDX license
 * identifier or expression, and writes
 * {@code target/generated-site/asciidoc/licenses.adoc}. The Maven
 * Site Plugin then renders that AsciiDoc to
 * {@code target/site/licenses.html} with the same Forest-theme
 * chrome as every other site page — no manual HTML / skin-chrome
 * duplication.
 *
 * <p>Components with multiple {@code licenses} array entries (the
 * JRuby case: GPL-2.0 + LGPL-2.1 + EPL-2.0) are collapsed into a
 * single SPDX OR-expression with deterministic alphabetical
 * ordering, so the same combination always groups under the same
 * heading. Components carrying a CycloneDX
 * {@code license.expression} field use that verbatim.
 *
 * <p>The auto-generated {@code licenses.html} from
 * {@code maven-project-info-reports-plugin} should be disabled in
 * the reactor's {@code <reportSets>} configuration so the only
 * {@code licenses.html} produced is the SPDX-grouped one. Without
 * that disable step both reports race for the same target file
 * and the winner depends on plugin ordering.
 *
 * <p>Skip with {@code -Dike.skip.spdx-licenses=true} to fall back
 * to whatever the auto-generated {@code licenses} report would
 * produce (assuming it's still enabled in {@code <reportSets>}).
 *
 * <pre>{@code
 * mvn package                              # produces bom.json
 * mvn ike:render-spdx-licenses             # produces licenses.adoc
 * mvn site                                 # renders licenses.html
 * }</pre>
 *
 * @see GenerateBomMojo
 */
@Mojo(name = "render-spdx-licenses", defaultPhase = "pre-site")
public class RenderSpdxLicensesMojo implements org.apache.maven.api.plugin.Mojo {

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
     * (ike-issues#333). If the file is missing, this goal logs a
     * warning and exits cleanly — typically because the caller
     * skipped {@code package} or pre-condition phases haven't run
     * yet.
     */
    @Parameter(property = "ike.bom.path",
            defaultValue = "${project.build.directory}/bom.json")
    File bomPath;

    /**
     * Output AsciiDoc file. Default is the standard generated-site
     * location which Maven Site picks up at site:site time and
     * renders to {@code target/site/licenses.html} with the
     * project's skin chrome.
     */
    @Parameter(property = "ike.spdx-licenses.output",
            defaultValue = "${project.build.directory}/generated-site/asciidoc/licenses.adoc")
    File outputPath;

    /**
     * Skip generation. The auto-generated
     * {@code maven-project-info-reports-plugin} licenses report
     * remains as the licenses page if its reportSet still includes
     * {@code licenses}.
     */
    @Parameter(property = "ike.skip.spdx-licenses", defaultValue = "false")
    boolean skip;

    /** Project artifact ID, used in the rendered page title. */
    @Parameter(defaultValue = "${project.artifactId}", readonly = true)
    String projectArtifactId;

    /** Project version, used in the rendered page title. */
    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    /** Creates this goal instance. */
    public RenderSpdxLicensesMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("ike:render-spdx-licenses skipped "
                    + "(-Dike.skip.spdx-licenses=true)");
            return;
        }

        if (!bomPath.exists()) {
            getLog().warn("Skipping ike:render-spdx-licenses: SBOM not "
                    + "found at " + bomPath + ". Run 'mvn package' first "
                    + "to produce the SBOM (ike-issues#333). Falling "
                    + "back to the auto-generated licenses.html if "
                    + "maven-project-info-reports-plugin's licenses "
                    + "report is still enabled.");
            return;
        }

        Map<String, List<Component>> grouped;
        try {
            grouped = parseAndGroup(bomPath);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not parse SBOM at " + bomPath + ": "
                            + e.getMessage(), e);
        }

        String adoc = renderAdoc(grouped);

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

        int totalComponents = grouped.values().stream()
                .mapToInt(List::size).sum();
        getLog().info("Wrote SPDX licenses page: " + outputPath);
        getLog().info("  Groups: " + grouped.size()
                + " distinct license expressions");
        getLog().info("  Components: " + totalComponents);
    }

    /**
     * Parse the SBOM JSON and group components by SPDX expression.
     *
     * <p>JSON is parsed via snakeyaml (JSON is a valid YAML 1.1
     * subset) so we don't need to add Jackson as a plugin
     * dependency — snakeyaml is already on the workspace-model
     * classpath.
     *
     * @param bom path to {@code bom.json}
     * @return map from SPDX expression to component list, ordered
     *         alphabetically by expression
     * @throws IOException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Component>> parseAndGroup(File bom)
            throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(bom.toPath(),
                StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) yaml.load(reader);
        }

        if (root == null) {
            return new TreeMap<>();
        }

        List<Map<String, Object>> components =
                (List<Map<String, Object>>) root.get("components");
        if (components == null) {
            return new TreeMap<>();
        }

        Map<String, List<Component>> grouped = new TreeMap<>();
        for (Map<String, Object> c : components) {
            String name    = stringOrEmpty(c.get("name"));
            String group   = stringOrEmpty(c.get("group"));
            String version = stringOrEmpty(c.get("version"));
            String purl    = stringOrEmpty(c.get("purl"));
            String spdx    = extractSpdxExpression(c);
            grouped.computeIfAbsent(spdx, k -> new ArrayList<>())
                    .add(new Component(group, name, version, purl));
        }

        // Sort components within each group by group:name:version.
        Comparator<Component> byCoord = Comparator
                .comparing((Component cm) -> cm.group)
                .thenComparing(cm -> cm.name)
                .thenComparing(cm -> cm.version);
        for (List<Component> list : grouped.values()) {
            list.sort(byCoord);
        }
        return grouped;
    }

    /**
     * Extract the SPDX license expression for a component.
     *
     * <p>Three CycloneDX shapes are handled, in priority order:
     * <ol>
     *   <li>A single license entry with {@code expression} set —
     *       used verbatim. This is how CycloneDX represents
     *       SPDX expressions like {@code Apache-2.0 OR MIT}.</li>
     *   <li>Multiple license entries with {@code id} fields —
     *       collapsed into a single OR-expression with
     *       alphabetical ordering for determinism. This is the
     *       JRuby case where the upstream POM declared three
     *       independent {@code <license>} blocks.</li>
     *   <li>A single license entry with {@code id} or {@code name}
     *       — used verbatim. Falls back to {@code name} for
     *       components whose POM declares a non-SPDX string.</li>
     * </ol>
     *
     * <p>Components with no license metadata are grouped under the
     * pseudo-identifier {@code (no license declared)} so they
     * surface visibly rather than silently disappearing.
     *
     * @param c the component map from the SBOM
     * @return the SPDX expression for grouping
     */
    @SuppressWarnings("unchecked")
    private String extractSpdxExpression(Map<String, Object> c) {
        Object licensesObj = c.get("licenses");
        if (!(licensesObj instanceof List<?> raw) || raw.isEmpty()) {
            return "(no license declared)";
        }
        List<Map<String, Object>> entries = (List<Map<String, Object>>) raw;

        // Shape (1): single entry with a top-level expression.
        if (entries.size() == 1) {
            Object exp = entries.get(0).get("expression");
            if (exp != null) {
                return exp.toString();
            }
        }

        // Collect SPDX IDs (or names) from each entry.
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> license =
                    (Map<String, Object>) entry.get("license");
            if (license == null) continue;
            Object id   = license.get("id");
            Object name = license.get("name");
            if (id != null) {
                ids.add(id.toString());
            } else if (name != null) {
                ids.add(name.toString());
            }
        }

        if (ids.isEmpty()) {
            return "(no license declared)";
        }
        if (ids.size() == 1) {
            return ids.get(0);
        }

        // Shape (2): synthesize OR-expression. Alphabetical
        // ordering keeps the same component-license combination
        // grouped under the same heading on every build.
        ids.sort(Comparator.naturalOrder());
        return String.join(" OR ", ids);
    }

    /**
     * Render the grouped components to AsciiDoc.
     *
     * @param grouped components keyed by SPDX expression
     * @return AsciiDoc page source
     */
    private String renderAdoc(Map<String, List<Component>> grouped) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Licenses (SPDX)\n");
        sb.append(":icons: font\n");
        sb.append(":source-highlighter: coderay\n");
        sb.append(":toc: preamble\n");
        sb.append(":toclevels: 2\n\n");
        sb.append("Licenses for declared dependencies of `")
                .append(projectArtifactId)
                .append("` ")
                .append(projectVersion)
                .append(", grouped by SPDX expression. ");
        sb.append("Rendered from `link:bom.json[bom.json]` ")
                .append("(CycloneDX) at `pre-site` phase by ")
                .append("`ike:render-spdx-licenses` ")
                .append("(ike-issues#335).\n\n");

        if (grouped.isEmpty()) {
            sb.append("No declared dependencies in this module's "
                    + "SBOM — nothing to list.\n");
            return sb.toString();
        }

        // Summary table: one row per SPDX expression with the
        // component count.
        sb.append("== Summary\n\n");
        sb.append("|===\n");
        sb.append("| SPDX Expression | Components\n\n");
        int totalComponents = 0;
        for (Map.Entry<String, List<Component>> g : grouped.entrySet()) {
            sb.append("| ");
            sb.append(formatLicenseHeader(g.getKey()));
            sb.append("\n| ").append(g.getValue().size()).append("\n\n");
            totalComponents += g.getValue().size();
        }
        sb.append("| *Total* | *").append(totalComponents).append("*\n");
        sb.append("|===\n\n");

        // Per-license sections.
        for (Map.Entry<String, List<Component>> g : grouped.entrySet()) {
            String spdx = g.getKey();
            sb.append("== ").append(spdx).append("\n\n");

            String spdxUrl = spdxUrl(spdx);
            if (spdxUrl != null) {
                sb.append("Reference: ").append(spdxUrl).append("[")
                        .append(spdx).append(" on spdx.org]\n\n");
            }

            sb.append("|===\n");
            sb.append("| Group | Artifact | Version\n\n");
            for (Component c : g.getValue()) {
                sb.append("| `").append(escapeAdoc(c.group)).append("`\n");
                sb.append("| `").append(escapeAdoc(c.name)).append("`\n");
                sb.append("| `").append(escapeAdoc(c.version)).append("`\n\n");
            }
            sb.append("|===\n\n");
        }

        sb.append("== See also\n\n");
        sb.append("* link:bom.json[Software Bill of Materials (CycloneDX)] — "
                + "the canonical machine-readable inventory this page "
                + "is derived from.\n");
        sb.append("* link:THIRD_PARTY_NOTICES.html[Third-Party Notices] — "
                + "curated companion that covers components mechanical "
                + "reports can't see (Maven Site skin, external "
                + "services, fonts inside artifacts).\n");
        sb.append("* link:dependency-info.html[Dependency Info] — "
                + "consumption snippet for this module.\n");
        return sb.toString();
    }

    /**
     * Format an SPDX expression for display in a table cell.
     *
     * <p>Wraps in backticks so AsciiDoc renders it as inline code
     * (visually distinct from prose). The pseudo-id
     * {@code (no license declared)} is rendered as italics
     * instead, since it isn't really a code identifier.
     *
     * @param spdx the SPDX expression
     * @return rendered cell content
     */
    private String formatLicenseHeader(String spdx) {
        if (spdx.startsWith("(") && spdx.endsWith(")")) {
            return "_" + spdx + "_";
        }
        return "`" + spdx + "`";
    }

    /**
     * Build an spdx.org URL for a single SPDX identifier.
     *
     * <p>Returns {@code null} for compound expressions
     * ({@code OR} / {@code AND} / {@code WITH}) and the
     * {@code (no license declared)} pseudo-id, since those don't
     * resolve to a single SPDX entry.
     *
     * @param spdx the SPDX expression
     * @return the URL, or {@code null} if not a single identifier
     */
    private String spdxUrl(String spdx) {
        if (spdx == null
                || spdx.isBlank()
                || spdx.startsWith("(")
                || spdx.contains(" OR ")
                || spdx.contains(" AND ")
                || spdx.contains(" WITH ")) {
            return null;
        }
        return "https://spdx.org/licenses/" + spdx + ".html";
    }

    /**
     * Escape a string for safe AsciiDoc inline-code use.
     *
     * <p>Backticks are the only character that can break out of
     * inline code spans. Everything else (including HTML special
     * characters) is fine inside {@code `…`}.
     *
     * @param s the input string
     * @return the escaped string
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
    private static final class Component {
        final String group;
        final String name;
        final String version;
        final String purl;

        Component(String group, String name, String version, String purl) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.purl = purl;
        }
    }
}
