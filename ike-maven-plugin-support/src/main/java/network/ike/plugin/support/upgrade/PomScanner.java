package network.ike.plugin.support.upgrade;

import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a {@code pom.xml} into a {@link PomScanResult} that the
 * {@link VersionUpgradePlanBuilder} can iterate without re-parsing.
 *
 * <p>Uses OpenRewrite's XML LST so we get raw, non-interpolated text
 * (we never want to ask "what does {@code ${ike-tooling.version}}
 * resolve to?" — we want the literal {@code "${ike-tooling.version}"}
 * string from the {@code <version>} element). The Maven 4 effective
 * model would interpolate, hiding the property reference we need to
 * upgrade.
 *
 * <p>The scanner walks every section that can declare a versioned
 * coordinate:
 * <ul>
 *   <li>{@code /project/parent} → {@link PomScanResult#parent()}</li>
 *   <li>{@code /project/properties} → {@link PomScanResult#versionProperties()}
 *       (every property whose value parses as a version-looking string;
 *       used by the planner to look up upgrade candidates)</li>
 *   <li>{@code /project/dependencies/dependency},
 *       {@code /project/dependencyManagement/dependencies/dependency}
 *       (all kinds — including BOM imports),
 *       {@code /project/build/plugins/plugin},
 *       {@code /project/build/pluginManagement/plugins/plugin},
 *       and the same set inside each {@code <profile>} →
 *       {@link PomScanResult#literals()} for hard-coded versions, and
 *       {@link PomScanResult#propertyToCoords()} for
 *       {@code ${prop}}-referenced versions</li>
 * </ul>
 *
 * <p>Whether a coordinate is upgradeable is decided later by the
 * planner — the scanner only collects facts.
 */
public final class PomScanner {

    private static final XmlParser PARSER = new XmlParser();

    private PomScanner() {}

    /**
     * Scan a POM file into a structured result.
     *
     * @param pomPath path to {@code pom.xml}
     * @return the scan result
     * @throws PomScanException if the file cannot be read or parsed
     */
    public static PomScanResult scan(Path pomPath) {
        try {
            byte[] bytes = Files.readAllBytes(pomPath);
            return scan(new String(bytes, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new PomScanException("Cannot read " + pomPath, e);
        }
    }

    /**
     * Scan POM content (already loaded as a string).
     *
     * @param pomXml the POM XML content
     * @return the scan result
     * @throws PomScanException if the content cannot be parsed
     */
    public static PomScanResult scan(String pomXml) {
        Xml.Document doc = PARSER.parse(pomXml)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElseThrow(() -> new PomScanException(
                        "Cannot parse POM"));
        Xml.Tag project = doc.getRoot();
        if (!"project".equals(project.getName())) {
            throw new PomScanException(
                    "Root element is not <project>: "
                            + project.getName());
        }

        Coord parent = parseParent(project);
        Map<String, String> versionProperties = parseProperties(project);
        Map<String, Set<Coord>> propertyToCoords = new LinkedHashMap<>();
        List<LiteralCoord> literals = new ArrayList<>();

        // Top-level scope.
        scanScope(project, "main", propertyToCoords, literals);

        // Each profile body.
        for (Xml.Tag profiles : project.getChildren("profiles")) {
            for (Xml.Tag profile : profiles.getChildren("profile")) {
                String profileId = profile.getChildValue("id")
                        .orElse("(unnamed)");
                scanScope(profile, "profile[" + profileId + "]",
                        propertyToCoords, literals);
            }
        }

        return new PomScanResult(parent, versionProperties,
                propertyToCoords, literals);
    }

    // ── Section handlers ───────────────────────────────────────────

    private static Coord parseParent(Xml.Tag project) {
        Optional<Xml.Tag> parent = project.getChild("parent");
        if (parent.isEmpty()) {
            return null;
        }
        Xml.Tag p = parent.get();
        String groupId = p.getChildValue("groupId").orElse(null);
        String artifactId = p.getChildValue("artifactId").orElse(null);
        String version = p.getChildValue("version").orElse(null);
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        return new Coord(groupId, artifactId, version);
    }

    private static Map<String, String> parseProperties(Xml.Tag project) {
        Map<String, String> out = new LinkedHashMap<>();
        Optional<Xml.Tag> propsTag = project.getChild("properties");
        if (propsTag.isEmpty()) {
            return out;
        }
        for (org.openrewrite.xml.tree.Content c
                : propsTag.get().getContent() != null
                ? propsTag.get().getContent()
                : List.<org.openrewrite.xml.tree.Content>of()) {
            if (c instanceof Xml.Tag t) {
                String value = t.getValue().orElse("");
                out.put(t.getName(), value);
            }
        }
        return out;
    }

    private static void scanScope(Xml.Tag root, String location,
                                  Map<String, Set<Coord>> propertyToCoords,
                                  List<LiteralCoord> literals) {
        // <dependencies>/<dependency>
        for (Xml.Tag deps : root.getChildren("dependencies")) {
            for (Xml.Tag dep : deps.getChildren("dependency")) {
                handleCoord(dep, location + "/dependencies",
                        propertyToCoords, literals);
            }
        }
        // <dependencyManagement>/<dependencies>/<dependency>
        for (Xml.Tag dm : root.getChildren("dependencyManagement")) {
            for (Xml.Tag deps : dm.getChildren("dependencies")) {
                for (Xml.Tag dep : deps.getChildren("dependency")) {
                    handleCoord(dep, location + "/dependencyManagement",
                            propertyToCoords, literals);
                }
            }
        }
        // <build>/<plugins>/<plugin> and <build>/<pluginManagement>/<plugins>/<plugin>
        for (Xml.Tag build : root.getChildren("build")) {
            for (Xml.Tag plugins : build.getChildren("plugins")) {
                for (Xml.Tag plugin : plugins.getChildren("plugin")) {
                    handleCoord(plugin, location + "/build/plugins",
                            propertyToCoords, literals);
                }
            }
            for (Xml.Tag pm : build.getChildren("pluginManagement")) {
                for (Xml.Tag plugins : pm.getChildren("plugins")) {
                    for (Xml.Tag plugin : plugins.getChildren("plugin")) {
                        handleCoord(plugin,
                                location + "/build/pluginManagement",
                                propertyToCoords, literals);
                    }
                }
            }
        }
    }

    private static void handleCoord(Xml.Tag coordTag, String location,
                                    Map<String, Set<Coord>> propertyToCoords,
                                    List<LiteralCoord> literals) {
        String groupId = coordTag.getChildValue("groupId").orElse(null);
        String artifactId = coordTag.getChildValue("artifactId").orElse(null);
        String version = coordTag.getChildValue("version").orElse(null);
        if (groupId == null || artifactId == null || version == null) {
            return; // Inherited version; not actionable here.
        }
        // Plugins for the org.apache.maven.plugins group commonly omit
        // groupId; we still skip those because we can't form a coord.

        String propertyName = extractPropertyReference(version);
        if (propertyName != null) {
            propertyToCoords
                    .computeIfAbsent(propertyName, k -> new LinkedHashSet<>())
                    .add(new Coord(groupId, artifactId, "${" + propertyName + "}"));
        } else {
            literals.add(new LiteralCoord(groupId, artifactId,
                    location, version));
        }
    }

    /**
     * Extract the inner property name from a {@code ${name}}
     * expression. Returns null if the value is a literal or contains
     * something more complex than a single property reference.
     *
     * @param value the raw text from a {@code <version>} element
     * @return the property name, or null
     */
    static String extractPropertyReference(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")
                && trimmed.indexOf('}') == trimmed.length() - 1) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            if (inner.isEmpty() || inner.contains("$")) {
                return null;
            }
            return inner;
        }
        return null;
    }

    // ── Records ────────────────────────────────────────────────────

    /**
     * A single coordinate (groupId, artifactId, version) as it appears
     * in the POM. {@code version} may be a literal or a
     * {@code ${property}} reference.
     *
     * @param groupId    the coordinate's groupId
     * @param artifactId the coordinate's artifactId
     * @param version    the version text exactly as it appears (literal
     *                   or {@code ${prop}})
     */
    public record Coord(String groupId, String artifactId, String version) {}

    /**
     * A literal-versioned coordinate found in the POM, with its
     * source location for plan rendering.
     *
     * @param groupId    the coordinate's groupId
     * @param artifactId the coordinate's artifactId
     * @param location   POM section where it was found (e.g.
     *                   {@code "main/build/pluginManagement"})
     * @param version    the literal version text
     */
    public record LiteralCoord(String groupId, String artifactId,
                                String location, String version) {}

    /**
     * The structured result of scanning a POM.
     *
     * @param parent             the {@code <parent>} reference, or null
     *                           if the POM has none
     * @param versionProperties  property name → declared value, for
     *                           every property in {@code <properties>}
     * @param propertyToCoords   property name → set of coordinates that
     *                           reference {@code ${name}} as their
     *                           version. Used to pick a representative
     *                           coordinate for upgrade lookup.
     * @param literals           coordinates with literal {@code <version>}
     *                           values
     */
    public record PomScanResult(
            Coord parent,
            Map<String, String> versionProperties,
            Map<String, Set<Coord>> propertyToCoords,
            List<LiteralCoord> literals
    ) {

        /**
         * Canonical constructor that defensively copies maps and lists
         * to immutable views.
         *
         * @param parent             see {@link #parent()}
         * @param versionProperties  see {@link #versionProperties()}
         * @param propertyToCoords   see {@link #propertyToCoords()}
         * @param literals           see {@link #literals()}
         */
        public PomScanResult {
            versionProperties = versionProperties == null
                    ? Map.of() : Map.copyOf(versionProperties);
            propertyToCoords = propertyToCoords == null
                    ? Map.of() : Map.copyOf(propertyToCoords);
            literals = literals == null
                    ? List.of() : List.copyOf(literals);
        }
    }

    /** Keep the visitor API live for future scanners. */
    @SuppressWarnings("unused")
    private static final Class<?> VISITOR_REF = XmlVisitor.class;
}
