package network.ike.plugin;

import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.model.v4.MavenStaxReader;

import javax.xml.stream.XMLStreamException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scan POM files for {@code -SNAPSHOT} references that would leak into
 * a released artifact via Maven 4's consumer POM flattener.
 *
 * <p>Maven 4 resolves properties and promotes {@code <pluginManagement>}
 * entries into {@code <plugins>} when it writes the <em>consumer POM</em>
 * — the POM consumers download from the repository. If a
 * {@code <pluginManagement>} entry references a property (e.g.
 * {@code <version>${ike-tooling.version}</version>}) whose value ends in
 * {@code -SNAPSHOT}, the flattener writes the literal SNAPSHOT string
 * into the released artifact. Downstream consumers then see
 * {@code <version>117-SNAPSHOT</version>} in the released POM and their
 * builds fail with "artifact not found" — even though the release
 * passed locally.
 *
 * <p>This scanner uses Maven 4's own {@link MavenStaxReader} to parse
 * POMs into the typed {@link Model} tree, then inspects only the
 * contexts that feed the consumer POM:
 *
 * <ul>
 *   <li><strong>Source properties scan</strong> via
 *       {@link #scanSourceProperties(File)} — inspects
 *       {@code <properties>} and fails if any value ends in
 *       {@code -SNAPSHOT}. Catches the bug at its source before any
 *       release mutation runs.</li>
 *   <li><strong>Post-mutation version scan</strong> via
 *       {@link #scanForSnapshotVersions(List)} — walks the model's
 *       {@code <parent>}, {@code <dependencies>}, {@code <dependencyManagement>},
 *       {@code <build>/<plugins>}, {@code <build>/<pluginManagement>}, and
 *       every profile's equivalent sections. Any {@code -SNAPSHOT}
 *       version here is a baked-in reference that would leak through
 *       the consumer POM.</li>
 * </ul>
 *
 * <p><strong>Not scanned:</strong> the module's own {@code <version>}
 * (immediate child of {@code <project>}), because during release the
 * module version is handled by
 * {@link ReleaseSupport#setPomVersion(java.io.File, String, String)}
 * and is not a consumer-POM leakage path. Comments, CDATA, and
 * whitespace are natively ignored by the Maven parser.
 */
public final class SnapshotScanner {

    /** Suffix that marks a SNAPSHOT version. */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private SnapshotScanner() {}

    /**
     * A single SNAPSHOT reference that would leak into a released POM.
     *
     * @param pomFile  the POM file containing the reference
     * @param location descriptor of the element location
     *                 (e.g. {@code "properties/ike-tooling.version"} or
     *                 {@code "pluginManagement/plugin[ike-maven-plugin]"})
     * @param value    the SNAPSHOT-ending value found
     */
    public record Violation(File pomFile, String location, String value) {

        /**
         * Format this violation as a single indented bullet for an
         * aggregated error message.
         *
         * @param gitRoot the repository root used to relativize the
         *                POM path; may be {@code null} for absolute paths
         * @return a single-line bullet formatted for log output
         */
        public String toBullet(File gitRoot) {
            String path = (gitRoot != null)
                    ? gitRoot.toPath().relativize(pomFile.toPath()).toString()
                    : pomFile.getPath();
            return "    • " + path + ": " + location + " = " + value;
        }
    }

    /**
     * Scan the {@code <properties>} sections of a POM (root plus any
     * profile properties) for any value ending in {@code -SNAPSHOT}.
     *
     * <p>This is the primary gate that catches the
     * {@code <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>}
     * class of bug before any release mutation runs.
     *
     * @param pomFile the POM file to scan
     * @return violations found in properties; empty if clean
     * @throws MojoException if the file cannot be read or parsed
     */
    public static List<Violation> scanSourceProperties(File pomFile) {
        Model model = parseModel(pomFile);
        List<Violation> violations = new ArrayList<>();

        collectSnapshotProperties(model.getProperties(), "properties",
                pomFile, violations);

        for (Profile profile : model.getProfiles()) {
            collectSnapshotProperties(profile.getProperties(),
                    "profiles/" + profile.getId() + "/properties",
                    pomFile, violations);
        }

        return violations;
    }

    /**
     * Scan a list of POMs for any {@code <version>...-SNAPSHOT</version>}
     * in the consumer-POM-relevant contexts: {@code <parent>},
     * {@code <dependencies>}, {@code <dependencyManagement>},
     * {@code <build>/<plugins>}, {@code <build>/<pluginManagement>},
     * and the same sections within every profile.
     *
     * <p>Intended for use <em>after</em>
     * {@link ReleaseSupport#replaceProjectVersionRefs(File, String,
     * org.apache.maven.api.plugin.Log)} has resolved
     * {@code ${project.version}} to a literal.
     *
     * <p>Explicitly skips the module's own {@code <version>} element —
     * that is handled by {@link ReleaseSupport#setPomVersion} and does
     * not leak into the consumer POM as a stale SNAPSHOT.
     *
     * @param pomFiles POM files to scan
     * @return violations found across all POMs; empty if clean
     * @throws MojoException if any file cannot be read or parsed
     */
    public static List<Violation> scanForSnapshotVersions(List<File> pomFiles) {
        List<Violation> violations = new ArrayList<>();
        for (File pom : pomFiles) {
            Model model = parseModel(pom);
            scanModel(pom, model, "", violations);
            for (Profile profile : model.getProfiles()) {
                scanProfile(pom, profile, violations);
            }
        }
        return violations;
    }

    /**
     * Format a list of violations as an aggregated multi-line message
     * suitable for {@code MojoException} or preflight output.
     *
     * @param violations the violations to format (non-empty)
     * @param gitRoot    repo root for relative path display; may be null
     * @param headline   opening sentence (e.g. "Cannot release — ...")
     * @param remedyHint closing instruction shown after the bullets
     * @return a formatted multi-line error body
     */
    public static String formatViolations(List<Violation> violations,
                                           File gitRoot,
                                           String headline,
                                           String remedyHint) {
        StringBuilder sb = new StringBuilder();
        sb.append(headline).append("\n");
        for (Violation v : violations) {
            sb.append(v.toBullet(gitRoot)).append("\n");
        }
        sb.append(remedyHint);
        return sb.toString();
    }

    // ── parser ────────────────────────────────────────────────────────

    private static Model parseModel(File pomFile) {
        try (Reader reader = Files.newBufferedReader(pomFile.toPath(),
                StandardCharsets.UTF_8)) {
            return new MavenStaxReader().read(reader);
        } catch (IOException | XMLStreamException e) {
            throw new MojoException(
                    "Failed to parse " + pomFile + ": " + e.getMessage(), e);
        }
    }

    // ── properties collection ────────────────────────────────────────

    private static void collectSnapshotProperties(Map<String, String> props,
                                                   String locationPrefix,
                                                   File pomFile,
                                                   List<Violation> into) {
        if (props == null) return;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.endsWith(SNAPSHOT_SUFFIX)) {
                into.add(new Violation(pomFile,
                        locationPrefix + "/" + entry.getKey(), value));
            }
        }
    }

    // ── model traversal for version scans ────────────────────────────

    private static void scanModel(File pom, Model model, String prefix,
                                   List<Violation> into) {
        // <parent><version> — inherited parent reference
        Parent parent = model.getParent();
        if (parent != null && isSnapshot(parent.getVersion())) {
            into.add(new Violation(pom,
                    prefix + "parent[" + coords(parent.getGroupId(),
                            parent.getArtifactId()) + "]",
                    parent.getVersion()));
        }

        // <dependencies><dependency><version>
        scanDependencies(pom, model.getDependencies(),
                prefix + "dependencies", into);

        // <dependencyManagement><dependencies><dependency><version>
        DependencyManagement dm = model.getDependencyManagement();
        if (dm != null) {
            scanDependencies(pom, dm.getDependencies(),
                    prefix + "dependencyManagement", into);
        }

        // <build><plugins><plugin><version>
        if (model.getBuild() != null) {
            scanPlugins(pom, model.getBuild().getPlugins(),
                    prefix + "build/plugins", into);

            // <build><pluginManagement><plugins><plugin><version>
            PluginManagement pm = model.getBuild().getPluginManagement();
            if (pm != null) {
                scanPlugins(pom, pm.getPlugins(),
                        prefix + "build/pluginManagement", into);
            }
        }
    }

    private static void scanProfile(File pom, Profile profile,
                                     List<Violation> into) {
        String prefix = "profiles/" + profile.getId() + "/";

        scanDependencies(pom, profile.getDependencies(),
                prefix + "dependencies", into);

        DependencyManagement dm = profile.getDependencyManagement();
        if (dm != null) {
            scanDependencies(pom, dm.getDependencies(),
                    prefix + "dependencyManagement", into);
        }

        if (profile.getBuild() != null) {
            scanPlugins(pom, profile.getBuild().getPlugins(),
                    prefix + "build/plugins", into);

            PluginManagement pm = profile.getBuild().getPluginManagement();
            if (pm != null) {
                scanPlugins(pom, pm.getPlugins(),
                        prefix + "build/pluginManagement", into);
            }
        }
    }

    private static void scanDependencies(File pom, List<Dependency> deps,
                                          String prefix,
                                          List<Violation> into) {
        if (deps == null) return;
        for (Dependency dep : deps) {
            if (isSnapshot(dep.getVersion())) {
                into.add(new Violation(pom,
                        prefix + "/" + coords(dep.getGroupId(),
                                dep.getArtifactId()),
                        dep.getVersion()));
            }
        }
    }

    private static void scanPlugins(File pom, List<Plugin> plugins,
                                     String prefix,
                                     List<Violation> into) {
        if (plugins == null) return;
        for (Plugin plugin : plugins) {
            if (isSnapshot(plugin.getVersion())) {
                into.add(new Violation(pom,
                        prefix + "/" + coords(plugin.getGroupId(),
                                plugin.getArtifactId()),
                        plugin.getVersion()));
            }
        }
    }

    private static boolean isSnapshot(String version) {
        return version != null && version.endsWith(SNAPSHOT_SUFFIX);
    }

    private static String coords(String groupId, String artifactId) {
        if (groupId == null || groupId.isBlank()) {
            return artifactId == null ? "?" : artifactId;
        }
        return groupId + ":" + artifactId;
    }
}
