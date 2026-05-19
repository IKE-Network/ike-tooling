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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Render a Web-friendly SBOM viewer page from the CycloneDX SBOM
 * (ike-issues#341).
 *
 * <p>The third human-facing view of the same {@code bom.json}:
 * <ul>
 *   <li>{@code licenses.html} — SPDX-grouped slice (#335)</li>
 *   <li>{@code built-with.html} — narrative + summary slice (#336)</li>
 *   <li>{@code dependencies.html} <em>(this mojo)</em> — full
 *       sortable component table</li>
 * </ul>
 *
 * <p>All three derive from the same source of truth. This mojo
 * walks every component in the SBOM and emits a single rendered
 * page with each component's coordinates, SPDX license, type, and
 * (when present) hash digests.
 *
 * <p>Replaces the auto-generated {@code dependencies} report from
 * {@code maven-project-info-reports-plugin}, which scans only
 * declared {@code <dependencies>} (not the full transitive graph
 * the SBOM captures) and reports licenses verbatim from each POM
 * (not SPDX-canonical).
 *
 * <p>Skip with {@code -Dike.skip.sbom-viewer=true}.
 *
 * <pre>{@code
 * mvn package                      # produces bom.json
 * mvn ike:render-sbom-viewer       # produces dependencies.adoc
 * mvn site                         # renders dependencies.html
 * }</pre>
 *
 * @see RenderSpdxLicensesMojo  the SPDX-grouped slice
 * @see BuiltWithMojo           the narrative + summary slice
 * @see GenerateBomMojo         IKE-specific Maven dependency BOM
 *                              (different concept)
 */
@Mojo(name = IkeGoal.NAME_RENDER_SBOM_VIEWER, defaultPhase = "pre-site")
public class RenderSbomViewerMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Path to the CycloneDX SBOM produced at {@code package} phase
     * by {@code cyclonedx-maven-plugin} (ike-issues#333).
     */
    @Parameter(property = "ike.bom.path",
            defaultValue = "${project.build.directory}/bom.json")
    File bomPath;

    /**
     * Output AsciiDoc file. Default lands in the standard
     * generated-site location which Maven Site renders to
     * {@code target/site/dependencies.html}.
     */
    @Parameter(property = "ike.sbom-viewer.output",
            defaultValue = "${project.build.directory}/generated-site/asciidoc/dependencies.adoc")
    File outputPath;

    /** Skip generation. */
    @Parameter(property = "ike.skip.sbom-viewer", defaultValue = "false")
    boolean skip;

    /** Project artifact ID, used in the rendered page title. */
    @Parameter(defaultValue = "${project.artifactId}", readonly = true)
    String projectArtifactId;

    /** Project version, used in the rendered page title. */
    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    /** Creates this goal instance. */
    public RenderSbomViewerMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info(IkeGoal.RENDER_SBOM_VIEWER.qualified()
                    + " skipped (-Dike.skip.sbom-viewer=true)");
            return;
        }

        if (!bomPath.exists()) {
            getLog().warn("Skipping " + IkeGoal.RENDER_SBOM_VIEWER.qualified()
                    + ": SBOM not "
                    + "found at " + bomPath + ". Run 'mvn package' "
                    + "first to produce the SBOM (ike-issues#333).");
            return;
        }

        SbomData bom;
        try {
            bom = parseSbom(bomPath);
        } catch (IOException e) {
            throw new MojoException(
                    "Could not parse SBOM at " + bomPath + ": "
                            + e.getMessage(), e);
        }

        String adoc = renderAdoc(bom);

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

        getLog().info("Wrote SBOM viewer page: " + outputPath);
        getLog().info("  Components: " + bom.components.size());
        getLog().info("  Distinct license expressions: "
                + bom.licenseGroupCount);
    }

    /**
     * Parse the SBOM JSON. Uses snakeyaml to read JSON-as-YAML so we
     * don't need a Jackson dep on this mojo's classpath (matches
     * {@link RenderSpdxLicensesMojo} and {@link BuiltWithMojo}).
     *
     * @param bom path to {@code bom.json}
     * @return parsed component list + summary statistics
     * @throws IOException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private SbomData parseSbom(File bom) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(bom.toPath(),
                StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) yaml.load(reader);
        }
        if (root == null) {
            return new SbomData(new ArrayList<>(), 0);
        }
        List<Map<String, Object>> rawComponents =
                (List<Map<String, Object>>) root.get("components");
        if (rawComponents == null) {
            return new SbomData(new ArrayList<>(), 0);
        }

        List<Component> components = new ArrayList<>();
        for (Map<String, Object> c : rawComponents) {
            String group       = stringOrEmpty(c.get("group"));
            String name        = stringOrEmpty(c.get("name"));
            String version     = stringOrEmpty(c.get("version"));
            String description = stringOrEmpty(c.get("description"));
            String purl        = stringOrEmpty(c.get("purl"));
            String type        = stringOrEmpty(c.get("type"));
            String spdx        = extractSpdxExpression(c);
            String sha256      = extractHash(c, "SHA-256");
            components.add(new Component(group, name, version,
                    description, purl, type, spdx, sha256));
        }

        // Sort by group:name:version for stable, browseable listing.
        components.sort(Comparator
                .comparing((Component cm) -> cm.group)
                .thenComparing(cm -> cm.name)
                .thenComparing(cm -> cm.version));

        long licenseGroups = components.stream()
                .map(cm -> cm.spdx)
                .distinct()
                .count();
        return new SbomData(components, (int) licenseGroups);
    }

    /**
     * Pull an SPDX license expression out of a CycloneDX component.
     * Same logic as {@link RenderSpdxLicensesMojo} (kept independent
     * to avoid coupling the two mojos via a shared utility).
     *
     * @param c the component map from the SBOM
     * @return the SPDX expression for this component
     */
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
     * Pull a specific hash algorithm's content out of a CycloneDX
     * component's {@code hashes} array, or empty string if absent.
     *
     * @param c   the component map
     * @param alg the hash algorithm name (e.g. {@code "SHA-256"})
     * @return the hash content, or empty string if absent
     */
    @SuppressWarnings("unchecked")
    private String extractHash(Map<String, Object> c, String alg) {
        Object hashesObj = c.get("hashes");
        if (!(hashesObj instanceof List<?> raw)) return "";
        for (Object h : raw) {
            if (!(h instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> hashMap = (Map<String, Object>) rawMap;
            if (alg.equals(hashMap.get("alg"))) {
                Object content = hashMap.get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }

    /**
     * Render the SBOM data to AsciiDoc.
     *
     * @param bom parsed SBOM
     * @return AsciiDoc page source
     */
    private String renderAdoc(SbomData bom) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Dependencies (SBOM)\n");
        sb.append(":icons: font\n");
        sb.append(":source-highlighter: coderay\n");
        sb.append(":toc: preamble\n");
        sb.append(":toclevels: 2\n\n");

        sb.append("Full transitive dependency graph for `")
                .append(projectArtifactId).append("` ")
                .append(projectVersion)
                .append(", generated from ")
                .append("link:bom.json[bom.json] (CycloneDX 1.6) at "
                        + "build time. Same SBOM source as the SPDX-"
                        + "grouped link:licenses.html[licenses.html] "
                        + "and the curated link:built-with.html"
                        + "[built-with.html] — three views of the "
                        + "same data.\n\n");

        sb.append("== Summary\n\n");
        sb.append("|===\n");
        sb.append("| Total components | ").append(bom.components.size())
                .append("\n");
        sb.append("| Distinct license expressions | ")
                .append(bom.licenseGroupCount).append("\n");
        sb.append("|===\n\n");

        if (bom.components.isEmpty()) {
            sb.append("_No components in this module's SBOM._\n\n");
        } else {
            sb.append("== Components\n\n");
            sb.append("Sorted by group, artifact, version. Click "
                    + "link:bom.json[bom.json] for the raw "
                    + "machine-readable form (Dependency-Track, Trivy, "
                    + "Snyk, GitHub dep-graph all ingest it directly).\n\n");
            sb.append("[%autowidth.stretch, options=\"header\"]\n");
            sb.append("|===\n");
            sb.append("| Group | Artifact | Version | License | Type\n\n");
            for (Component c : bom.components) {
                sb.append("| `").append(escapeAdoc(c.group)).append("`\n");
                sb.append("| `").append(escapeAdoc(c.name)).append("`\n");
                sb.append("| `").append(escapeAdoc(c.version)).append("`\n");
                if (c.spdx.startsWith("(") && c.spdx.endsWith(")")) {
                    sb.append("| _").append(c.spdx).append("_\n");
                } else {
                    sb.append("| `").append(escapeAdoc(c.spdx)).append("`\n");
                }
                sb.append("| ").append(escapeAdoc(
                        c.type.isEmpty() ? "library" : c.type)).append("\n\n");
            }
            sb.append("|===\n\n");
        }

        sb.append("== Download\n\n");
        sb.append("* link:bom.json[Software Bill of Materials "
                + "(CycloneDX, JSON)] — raw machine-readable form. "
                + "Includes purls, hashes, and dependency-graph "
                + "edges that this page summarizes.\n");
        sb.append("* link:bom.xml[bom.xml] — same content in XML.\n");
        sb.append("* As a Maven artifact: pull "
                + "`").append(projectArtifactId)
                .append(":​")  // zero-width space to soften wrap
                .append(projectVersion)
                .append("` with `<classifier>cyclonedx</classifier>"
                        + "<type>json</type>` from Nexus / Maven Central.\n\n");

        sb.append("== See also\n\n");
        sb.append("* link:licenses.html[Licenses (SPDX)] — same "
                + "components grouped by license expression.\n");
        sb.append("* link:built-with.html[Built With] — curated "
                + "narrative + per-license summary.\n");
        sb.append("* https://github.com/IKE-Network/ike-issues/issues/341"
                + "[ike-issues#341] — the issue that introduced this "
                + "page.\n");
        return sb.toString();
    }

    /**
     * Escape a string for safe inline-code use in AsciiDoc.
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
    private record Component(
            String group, String name, String version,
            String description, String purl, String type,
            String spdx, String sha256) { }

    /** Parsed SBOM with a small summary side-channel. */
    private record SbomData(List<Component> components,
                            int licenseGroupCount) { }
}
