package network.ike.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@link Cohort} an artifact / release-style workspace verb
 * acts on, from a starting directory (IKE-Network/ike-issues#610, under
 * #601).
 *
 * <p>Searches upward for a {@code workspace.yaml}. When found, the cohort is
 * the workspace's subprojects in topological order — the aggregator root is
 * excluded, as it is not a released artifact. When not found, the cohort is
 * the single repository at the start directory: a cohort of one. This is the
 * home for the "single repo vs workspace" decision that artifact goals — the
 * bare mode of {@code ws:release} / {@code ws:scaffold} (#605/#607) — make
 * for themselves today; the migration in ike-issues#612 folds them onto it.
 *
 * <p>The <em>decentralized</em> cohort (the foundation cascade, assembled
 * from {@code release-cascade.yaml}) is modelled by
 * {@link network.ike.workspace.cascade.ReleaseCascade} — already a
 * topologically-ordered list; expressing it as a {@code Cohort} is a
 * follow-on as the foundation cascade adopts this type.
 */
public final class CohortResolver {

    private CohortResolver() {}

    /**
     * Resolve the cohort from a starting directory.
     *
     * @param startDir the directory to resolve from (typically the CWD)
     * @return the workspace cohort when a {@code workspace.yaml} is found at
     *         or above {@code startDir}; otherwise the single-repository
     *         cohort of one rooted at {@code startDir}
     */
    public static Cohort resolve(Path startDir) {
        Path manifest = WorkingSetResolver.findManifest(startDir);
        return manifest == null ? singleRepo(startDir) : workspace(manifest);
    }

    /**
     * Build a single-repository cohort of one rooted at {@code dir}.
     *
     * @param dir the repository directory
     * @return the cohort of one
     */
    public static Cohort singleRepo(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        Path name = root.getFileName();
        return new Cohort(List.of(new Cohort.Member(
                name == null ? root.toString() : name.toString(), root)),
                false);
    }

    private static Cohort workspace(Path manifest) {
        Path root = manifest.getParent();
        Manifest model = ManifestReader.read(manifest);
        WorkspaceGraph graph = new WorkspaceGraph(model);
        List<Cohort.Member> members = new ArrayList<>();
        for (String name : graph.topologicalSort()) {
            members.add(new Cohort.Member(name, root.resolve(name)));
        }
        return new Cohort(List.copyOf(members), false);
    }
}
