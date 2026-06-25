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
 * <p>The workspace root (aggregator) is a first-class {@link Member}, not an
 * orchestrator standing outside the set: it is branched, version-qualified,
 * committed, and tagged alongside the subprojects. Each member therefore
 * carries a {@link Member.Kind} so reports and lifecycle goals can include the
 * aggregator and special-case it where its role differs (#764).
 *
 * <p>This is the working-set half of the scope-resolution layer; the
 * dependency-ordered <em>artifact cohort</em> that release-style verbs need
 * is modelled separately (ike-issues#610).
 *
 * @param root     the primary directory — the workspace root, or the single
 *                 repository
 * @param manifest the {@code workspace.yaml} path, or {@code null} for a
 *                 single-repository working set
 * @param baseName the working set's identity for derived names — the base for
 *                 sibling directories ({@code <baseName>-<feature>}): the
 *                 manifest {@code workspace-root:} {@code artifactId} when
 *                 present, otherwise the root directory name
 * @param members  every git working tree in the set, in a deterministic
 *                 order: the declared subprojects followed by the workspace
 *                 root (a single-repository working set has the one repo as
 *                 its sole member). The workspace root is the member whose
 *                 {@link Member#directory()} equals {@link #root()} and whose
 *                 {@link Member#kind()} is {@link Member.Kind#AGGREGATOR}.
 */
public record WorkingSet(Path root, Path manifest, String baseName,
                         List<Member> members) {

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
     * @param kind      whether this member is a declared subproject or the
     *                  workspace root (aggregator)
     */
    public record Member(String name, Path directory, Kind kind) {

        /**
         * The role a {@link Member} plays in its {@link WorkingSet}.
         * "Subproject" is a member <em>kind</em>, never the organizing unit —
         * the working set is the unit, and the aggregator is one of its
         * members (#764).
         */
        public enum Kind {
            /** A declared subproject of a workspace. */
            SUBPROJECT,
            /**
             * The workspace root that aggregates the subprojects — and, for a
             * single-repository working set, the sole member, which is the
             * root of its own trivial set.
             */
            AGGREGATOR
        }

        /**
         * Create a {@link Kind#SUBPROJECT} member.
         *
         * @param name      the subproject name
         * @param directory the subproject's git working tree
         * @return a subproject member
         */
        public static Member subproject(String name, Path directory) {
            return new Member(name, directory, Kind.SUBPROJECT);
        }

        /**
         * Create a {@link Kind#AGGREGATOR} member.
         *
         * @param name      the directory name of the workspace root (or single
         *                  repository)
         * @param directory the workspace root's git working tree
         * @return an aggregator member
         */
        public static Member aggregator(String name, Path directory) {
            return new Member(name, directory, Kind.AGGREGATOR);
        }

        /**
         * Whether this member is the workspace root (aggregator).
         *
         * @return {@code true} if {@link #kind()} is {@link Kind#AGGREGATOR}
         */
        public boolean isAggregator() {
            return kind == Kind.AGGREGATOR;
        }
    }
}
