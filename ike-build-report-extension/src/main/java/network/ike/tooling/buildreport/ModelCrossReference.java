package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives MODEL findings from file-model observations at session end.
 *
 * <p>This is the "derive, don't harvest" answer to the empirical
 * result that Maven 4 (rc-5) publishes model problems only as log
 * text, never as events: instead of parsing Maven's warning, the
 * extension recomputes the condition — an import-scoped POM dependency
 * whose resolved coordinates match a reactor member — directly from
 * the models it observed. Findings key on the <em>source</em>
 * declaration, which is stable across runs, unlike Maven's own
 * attribution of the equivalent warning (observed to move between
 * consuming projects with model-build order).</p>
 */
public final class ModelCrossReference {

    /** The stable ledger key for in-reactor BOM imports (MNG-8012 class). */
    public static final String BOM_IMPORT_KEY = "model-problem/bom-import-from-reactor";

    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private ModelCrossReference() {
    }

    /**
     * Computes MODEL findings for imports that resolve to reactor members.
     *
     * <p>Reactor membership is defined by the observation's POM file
     * living under the execution root. Imports match on full
     * {@code groupId:artifactId:version}; an import with an
     * unresolvable version never matches (no false positives on
     * released-BOM imports). A model importing itself is ignored.</p>
     *
     * @param observations  the session's file-model observations
     * @param executionRoot the session's execution root directory
     * @return one WARNING finding per in-reactor BOM import, in
     *         observation order
     */
    public static List<Finding> findings(List<ModelObservations.FileModel> observations, Path executionRoot) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(executionRoot, "executionRoot");
        Path root = executionRoot.toAbsolutePath().normalize();

        // Maven reads the same POM's file model more than once per
        // session (observed: exactly twice); one declaration is one
        // finding, so observations dedupe by POM file, first read wins.
        Map<Path, ModelObservations.FileModel> reactorMembers = new LinkedHashMap<>();
        Set<String> memberGavs = new HashSet<>();
        for (ModelObservations.FileModel observation : observations) {
            Path pom = observation.pomFile().toAbsolutePath().normalize();
            if (pom.startsWith(root)) {
                reactorMembers.putIfAbsent(pom, observation);
                memberGavs.add(observation.gav());
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (ModelObservations.FileModel member : reactorMembers.values()) {
            for (ModelObservations.BomImport bomImport : member.bomImports()) {
                if (bomImport.version() == null) {
                    continue;
                }
                String importedGav = bomImport.gav();
                if (!memberGavs.contains(importedGav) || importedGav.equals(member.gav())) {
                    continue;
                }
                findings.add(new Finding(
                        FindingCategory.MODEL,
                        Severity.WARNING,
                        BOM_IMPORT_KEY,
                        member.artifactId() + " imports " + importedGav
                                + " (" + describeSource(member.pomFile(), root, bomImport.line()) + ")"));
            }
        }
        return findings;
    }

    /**
     * Resolves a one-level {@code ${property}} reference against the
     * declaring model's own properties.
     *
     * @param rawVersion the version text as written in the POM; may be
     *                   {@code null}
     * @param properties the declaring model's properties
     * @return the resolved version, or {@code null} when the reference
     *         cannot be resolved from the given properties
     */
    public static String resolveVersion(String rawVersion, Map<String, String> properties) {
        if (rawVersion == null) {
            return null;
        }
        Matcher matcher = PROPERTY_REFERENCE.matcher(rawVersion);
        if (!matcher.find()) {
            return rawVersion;
        }
        String resolved = properties.get(matcher.group(1));
        if (resolved == null || PROPERTY_REFERENCE.matcher(resolved).find()) {
            return null;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(resolved));
    }

    private static String describeSource(Path pomFile, Path root, int line) {
        Path normalized = pomFile.toAbsolutePath().normalize();
        String location = normalized.startsWith(root)
                ? root.relativize(normalized).toString()
                : normalized.toString();
        return line > 0 ? location + ":" + line : location;
    }
}
