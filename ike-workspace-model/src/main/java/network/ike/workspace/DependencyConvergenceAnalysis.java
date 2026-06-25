package network.ike.workspace;

import network.ike.workspace.DependencyTreeParser.ResolvedDependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Compare resolved dependency trees across workspace subprojects to
 * detect version divergence.
 *
 * <p>When multiple subprojects in a workspace resolve the same
 * {@code groupId:artifactId} to different versions, that divergence
 * can cause classpath conflicts in assembled applications (e.g.,
 * a desktop application that depends on several workspace libraries).
 *
 * <p>This analysis operates on the <em>resolved</em> dependency trees
 * (output of {@code mvn dependency:tree}), not the declared POMs.
 * This means it catches divergence introduced by transitive
 * dependencies, BOM imports, and Maven's nearest-wins resolution.
 */
public final class DependencyConvergenceAnalysis {

    private DependencyConvergenceAnalysis() {}

    /**
     * A dependency whose resolved version differs across subprojects.
     *
     * @param groupId              Maven groupId
     * @param artifactId           Maven artifactId
     * @param versionToSubprojects map from resolved version to the list
     *                             of subproject names that resolve to it
     */
    public record Divergence(
            String groupId, String artifactId,
            Map<String, List<String>> versionToSubprojects) {

        /**
         * The artifact coordinate as {@code groupId:artifactId}.
         *
         * @return the coordinate string
         */
        public String coordinate() {
            return groupId + ":" + artifactId;
        }

        /**
         * Number of distinct versions found.
         *
         * @return the version count
         */
        public int versionCount() {
            return versionToSubprojects.size();
        }
    }

    /**
     * Analyze dependency trees from multiple subprojects for version
     * divergence.
     *
     * <p>For each unique {@code groupId:artifactId} that appears in
     * more than one subproject's tree, checks whether the resolved
     * version is the same. Returns only those artifacts where at
     * least two different versions are resolved.
     *
     * <p>The root artifact of each subproject (depth 0) is excluded
     * from comparison — those are expected to differ.
     *
     * @param subprojectTrees map from subproject name to its parsed
     *                        dependency tree
     * @return list of divergences, sorted by coordinate
     */
    public static List<Divergence> analyze(
            Map<String, List<ResolvedDependency>> subprojectTrees) {

        // Key: "groupId:artifactId" → inner map: "subprojectName" → "version"
        Map<String, Map<String, String>> artifactVersions = new LinkedHashMap<>();

        for (Map.Entry<String, List<ResolvedDependency>> entry : subprojectTrees.entrySet()) {
            String subprojectName = entry.getKey();
            for (ResolvedDependency dep : entry.getValue()) {
                // Skip root artifacts
                if (dep.depth() == 0) continue;
                // Skip test-scoped dependencies
                if ("test".equals(dep.scope())) continue;

                String key = dep.groupId() + ":" + dep.artifactId();
                artifactVersions
                        .computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .put(subprojectName, dep.version());
            }
        }

        // Find divergences
        List<Divergence> divergences = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : artifactVersions.entrySet()) {
            Map<String, String> subprojectVersions = entry.getValue();
            if (subprojectVersions.size() < 2) continue;

            // Group subprojects by version
            Map<String, List<String>> versionToSubprojects =
                    subprojectVersions.entrySet().stream()
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getValue,
                                    TreeMap::new,
                                    Collectors.mapping(
                                            Map.Entry::getKey,
                                            Collectors.toList())));

            if (versionToSubprojects.size() > 1) {
                String[] parts = entry.getKey().split(":", 2);
                divergences.add(new Divergence(
                        parts[0], parts[1], versionToSubprojects));
            }
        }

        // Sort by coordinate for stable output
        divergences.sort((a, b) -> a.coordinate().compareTo(b.coordinate()));
        return divergences;
    }

    /**
     * Format divergences as a markdown report suitable for rendering
     * as readable plugin output.
     *
     * <p>Produces a markdown document with a summary table and
     * per-artifact detail sections. The format is designed to be
     * readable both in terminal output and when rendered as HTML.
     *
     * @param divergences    the divergences to report
     * @param workspaceName  the workspace name for the report title
     * @return markdown string, or empty string if no divergences
     */
    public static String formatMarkdownReport(
            List<Divergence> divergences, String workspaceName) {
        if (divergences.isEmpty()) {
            return "";
        }

        StringBuilder md = new StringBuilder();

        md.append("# Dependency Convergence — ").append(workspaceName).append("\n\n");
        md.append("**").append(divergences.size())
                .append(" artifact(s)** resolve to different versions ")
                .append("across workspace subprojects.\n\n");

        // Summary table
        md.append("| Artifact | Versions | Subprojects |\n");
        md.append("|----------|----------|-------------|\n");
        for (Divergence d : divergences) {
            String versions = String.join(", ",
                    d.versionToSubprojects().keySet());
            int subprojectCount = d.versionToSubprojects().values().stream()
                    .mapToInt(List::size).sum();
            md.append("| `").append(d.coordinate()).append("` | ")
                    .append(versions).append(" | ")
                    .append(subprojectCount).append(" |\n");
        }

        // Detail sections
        md.append("\n---\n\n");
        md.append("## Details\n\n");

        for (Divergence d : divergences) {
            md.append("### `").append(d.coordinate()).append("`\n\n");
            for (Map.Entry<String, List<String>> vEntry : d.versionToSubprojects().entrySet()) {
                md.append("**").append(vEntry.getKey()).append("**");
                md.append(" — ");
                md.append(String.join(", ", vEntry.getValue()));
                md.append("\n\n");
            }
        }

        return md.toString();
    }
}
