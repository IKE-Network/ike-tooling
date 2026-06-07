package network.ike.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * The co-located set of git working trees a working-tree workspace
 * operation acts on — one repository for a single repo, or the subprojects
 * plus the workspace root for a workspace (IKE-Network/ike-issues#609,
 * under the console/engine boundary, #601).
 *
 * <p>A working set is resolved from context by {@link WorkingSetResolver}:
 * a single repository is a working set of one; a {@code workspace.yaml}
 * declares a larger one. Working-tree verbs — commit, push, sync, branch,
 * fork, switch — act on each {@link Member}; the {@link #manifest()}, when
 * present, carries the workspace metadata.
 *
 * <p>This is the working-set half of the scope-resolution layer; the
 * dependency-ordered <em>artifact cohort</em> that release-style verbs need
 * is modelled separately (ike-issues#610).
 *
 * @param root     the primary directory — the workspace root, or the single
 *                 repository
 * @param manifest the {@code workspace.yaml} path, or {@code null} for a
 *                 single-repository working set
 * @param members  every git working tree in the set, in a deterministic
 *                 order: the declared subprojects followed by the workspace
 *                 root (a single-repository working set has the one repo as
 *                 its sole member). The workspace root is the member whose
 *                 {@link Member#directory()} equals {@link #root()}.
 */
public record WorkingSet(Path root, Path manifest, List<Member> members) {

    /**
     * Whether this working set is backed by a {@code workspace.yaml}.
     *
     * @return {@code true} for a workspace, {@code false} for a single repo
     */
    public boolean isWorkspace() {
        return manifest != null;
    }

    /**
     * Whether this is a single-repository working set — a working set of one.
     *
     * @return {@code true} for a single repository
     */
    public boolean isSingleRepo() {
        return manifest == null;
    }

    /**
     * One git working tree in a {@link WorkingSet}.
     *
     * @param name      the subproject name, or the directory name for the
     *                  workspace root and for a single repository
     * @param directory the git working tree's directory
     */
    public record Member(String name, Path directory) {}
}
