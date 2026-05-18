package network.ike.plugin.scaffold;

import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for {@code ike:scaffold-draft},
 * {@code ike:scaffold-publish}, and {@code ike:scaffold-revert}.
 *
 * <p>Resolves the scaffold tree (manifest + templates), loads
 * per-scope lockfiles from disk, and offers pure-function helpers
 * the three mojos use. Written as a final class with static
 * methods so the mojos can share wiring without inheritance.
 */
public final class ScaffoldMojoSupport {

    /** File name of the manifest inside a scaffold tree. */
    public static final String MANIFEST_FILE = "scaffold-manifest.yaml";

    /** Relative path of the per-project lockfile. */
    public static final String PROJECT_LOCKFILE_REL =
            ".ike/scaffold.lock";

    /** Relative path of the per-user lockfile. */
    public static final String USER_LOCKFILE_REL =
            ".ike/scaffold.lock";

    private ScaffoldMojoSupport() {}

    /**
     * Resolve the effective project root for a scaffold goal.
     *
     * <p>Goals annotated {@code projectRequired = false} cannot rely on
     * Maven's parameter-default expansion of {@code ${project.basedir}}
     * — Maven 4 leaves the placeholder uninterpolated when no project
     * is required, even when one is in scope. Callers therefore pass an
     * explicit {@code projectRoot} parameter when supplied by the user;
     * when blank, this helper derives the directory from
     * {@link Session#getTopDirectory()} (the directory Maven was
     * launched from).
     *
     * <p>The result is null when no {@code pom.xml} sits at the
     * resolved location, which the calling mojo treats as
     * fresh-machine bootstrap (user scope only, no project scope).
     *
     * @param projectRootOverride raw value of the {@code projectRoot}
     *                            parameter; null/blank means
     *                            "auto-detect"
     * @param session             Maven 4 session; required for
     *                            auto-detection. May be {@code null}
     *                            in tests that supply an explicit
     *                            override.
     * @return absolute project root, or {@code null} when no project
     *         is in scope at the resolved location
     */
    public static Path resolveProjectRoot(
            String projectRootOverride, Session session) {
        if (projectRootOverride != null
                && !projectRootOverride.isBlank()) {
            return Path.of(projectRootOverride);
        }
        if (session == null) {
            return null;
        }
        Path top = session.getTopDirectory();
        if (top == null) {
            return null;
        }
        if (!Files.isRegularFile(top.resolve("pom.xml"))) {
            return null;
        }
        return top;
    }

    /**
     * Resolve the manifest file at the root of a scaffold tree.
     *
     * @param scaffoldDir the directory containing
     *                    {@value #MANIFEST_FILE} and templates
     * @return parsed manifest
     * @throws ScaffoldException if the directory or manifest is missing
     */
    public static ScaffoldManifest loadManifest(Path scaffoldDir) {
        if (scaffoldDir == null) {
            throw new ScaffoldException(
                    "scaffoldDir is required; point it at the "
                            + "unpacked ike-build-standards scaffold tree");
        }
        if (!Files.isDirectory(scaffoldDir)) {
            throw new ScaffoldException(
                    "scaffoldDir is not a directory: " + scaffoldDir);
        }
        Path manifestPath = scaffoldDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            throw new ScaffoldException(
                    "missing " + MANIFEST_FILE + " under "
                            + scaffoldDir);
        }
        return ScaffoldManifestIo.read(manifestPath);
    }

    /**
     * Resolve the project lockfile path, creating nothing.
     *
     * @param projectRoot project base directory; may be {@code null}
     *                    when no project is in scope
     * @return absolute path to {@code {projectRoot}/.ike/scaffold.lock},
     *         or {@code null} if {@code projectRoot} is null
     */
    public static Path projectLockfilePath(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        return projectRoot.resolve(PROJECT_LOCKFILE_REL);
    }

    /**
     * Resolve the user lockfile path, creating nothing.
     *
     * @param userHome the user's home directory; required
     * @return absolute path to {@code {userHome}/.ike/scaffold.lock}
     */
    public static Path userLockfilePath(Path userHome) {
        return userHome.resolve(USER_LOCKFILE_REL);
    }

    /**
     * Load a lockfile from disk, returning
     * {@link ScaffoldLockfile#empty()} when the file is absent.
     *
     * @param lockfilePath path to the lockfile; may be {@code null}
     *                     (returns empty)
     * @return the loaded lockfile, or an empty lockfile
     */
    public static ScaffoldLockfile loadLockfileOrEmpty(Path lockfilePath) {
        if (lockfilePath == null) {
            return ScaffoldLockfile.empty();
        }
        if (!Files.isRegularFile(lockfilePath)) {
            return ScaffoldLockfile.empty();
        }
        return ScaffoldLockfileIo.read(lockfilePath);
    }

    /**
     * Render a draft-style listing of a plan suitable for log output.
     *
     * <p>Lines look like:
     * <pre>
     * [INSTALL]  mvnw                         -&gt; /path/to/mvnw
     * [UPDATE]   .mvn/maven.config            -&gt; refresh
     * [SKIP]     .gitignore                   user-edited; +3/-1
     * [OK]       ~/.m2/settings.xml           up-to-date
     * [USER]     ~/.gitconfig                 deferred to user value for [core].hooksPath
     * </pre>
     *
     * @param plan  the plan to render
     * @param scope the scope the plan was built for (used as a header)
     * @return multi-line human-readable report (no trailing newline)
     */
    public static String renderPlanReport(
            ScaffoldPlan plan, ScaffoldScope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("─── ").append(scope.manifestValue())
                .append(" scope (").append(plan.entries().size())
                .append(" entries) ───");
        if (plan.entries().isEmpty()) {
            sb.append("\n  (nothing in this scope)");
            return sb.toString();
        }
        for (PlannedEntry pe : plan.entries()) {
            sb.append('\n').append(formatLine(pe));
        }
        return sb.toString();
    }

    /**
     * Render an orphan listing suitable for log output. Orphans are
     * lockfile entries the current manifest no longer ships.
     *
     * @param orphans the orphans found by {@link OrphanScanner}
     * @param scope   the scope the scan targeted (used as a header)
     * @return multi-line human-readable report (no trailing newline)
     */
    public static String renderOrphanReport(
            List<OrphanEntry> orphans, ScaffoldScope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("─── ").append(scope.manifestValue())
                .append(" scope — ").append(orphans.size())
                .append(" orphan(s) ───");
        for (OrphanEntry o : orphans) {
            sb.append('\n').append("  [ORPHAN]  ")
                    .append(padRight(o.dest(), 36))
                    .append("  ").append(o.reason());
        }
        return sb.toString();
    }

    /**
     * One-line summary of what the plan would change, for post-publish
     * telemetry and draft headers.
     *
     * @param plan the plan to summarise
     * @return counts of each action type
     */
    public static Counts countActions(ScaffoldPlan plan) {
        int install = 0, update = 0, skip = 0;
        int upToDate = 0, userManaged = 0;
        for (PlannedEntry pe : plan.entries()) {
            TierAction a = pe.action();
            if (a instanceof TierAction.Write w) {
                if (w.kind() == TierAction.Write.Kind.INSTALL) {
                    install++;
                } else {
                    update++;
                }
            } else if (a instanceof TierAction.Skip) {
                skip++;
            } else if (a instanceof TierAction.UpToDate) {
                upToDate++;
            } else if (a instanceof TierAction.UserManaged) {
                userManaged++;
            }
        }
        return new Counts(install, update, skip, upToDate, userManaged);
    }

    /**
     * Render a revert-outcome listing suitable for log output.
     *
     * @param result the revert result
     * @param scope  the scope the revert targeted
     * @return multi-line human-readable report (no trailing newline)
     */
    public static String renderRevertReport(
            ScaffoldReverter.RevertResult result, ScaffoldScope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("─── ").append(scope.manifestValue())
                .append(" scope (").append(result.outcomes().size())
                .append(" entries) ───");
        if (result.outcomes().isEmpty()) {
            sb.append("\n  (nothing to revert)");
            return sb.toString();
        }
        for (ScaffoldReverter.Outcome o : result.outcomes()) {
            sb.append('\n').append(formatOutcome(o));
        }
        return sb.toString();
    }

    /**
     * Write a batch of plan-report lines through the supplied log.
     * Each {@code \n}-separated segment becomes a separate
     * {@link Log#info(CharSequence) log.info} call so Maven's
     * level/colour formatting works on each line.
     *
     * @param log       the mojo log
     * @param multiLine multi-line text
     */
    public static void logLines(Log log, String multiLine) {
        for (String line : multiLine.split("\n")) {
            log.info(line);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String formatLine(PlannedEntry pe) {
        String dest = pe.manifest().dest();
        TierAction a = pe.action();
        if (a instanceof TierAction.Write w) {
            String label = switch (w.kind()) {
                case INSTALL -> "[INSTALL]";
                case UPDATE -> "[UPDATE] ";
                case REVERT -> "[REVERT] ";
            };
            return "  " + label + "  " + padRight(dest, 36)
                    + "  " + w.reason();
        }
        if (a instanceof TierAction.Skip s) {
            StringBuilder line = new StringBuilder();
            line.append("  [SKIP]    ").append(padRight(dest, 36))
                    .append("  ").append(s.reason());
            if (!s.diff().isBlank()) {
                for (String diffLine : s.diff().split("\n")) {
                    line.append('\n').append("        ").append(diffLine);
                }
            }
            return line.toString();
        }
        if (a instanceof TierAction.UpToDate u) {
            return "  [OK]      " + padRight(dest, 36)
                    + "  " + u.reason();
        }
        if (a instanceof TierAction.UserManaged m) {
            return "  [USER]    " + padRight(dest, 36)
                    + "  " + m.reason();
        }
        return "  [?]       " + dest;
    }

    private static String formatOutcome(ScaffoldReverter.Outcome o) {
        String label = switch (o.kind()) {
            case DELETED -> "[DELETED]";
            case REMOVED_FROM_LOCKFILE -> "[CLEARED]";
            case SKIPPED -> "[SKIP]   ";
        };
        return "  " + label + "  " + padRight(o.dest(), 36)
                + "  " + o.message();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Aggregate counts of each action kind in a plan.
     *
     * @param install     number of {@link TierAction.Write} with
     *                    {@link TierAction.Write.Kind#INSTALL}
     * @param update      number of {@link TierAction.Write} with
     *                    {@link TierAction.Write.Kind#UPDATE}
     * @param skip        number of {@link TierAction.Skip}
     * @param upToDate    number of {@link TierAction.UpToDate}
     * @param userManaged number of {@link TierAction.UserManaged}
     */
    public record Counts(
            int install, int update, int skip,
            int upToDate, int userManaged) {

        /**
         * Total number of entries across every action kind.
         *
         * @return sum of {@code install + update + skip + upToDate +
         *         userManaged}
         */
        public int total() {
            return install + update + skip + upToDate + userManaged;
        }

        /**
         * Whether any write action is planned.
         *
         * @return {@code true} if {@code install + update > 0}
         */
        public boolean hasWrites() {
            return install + update > 0;
        }

        /**
         * One-line summary like
         * {@code "2 install, 1 update, 0 skip, 0 ok, 1 user"}.
         *
         * @return summary line suitable for draft-output display
         */
        public String summary() {
            return install + " install, " + update + " update, "
                    + skip + " skip, " + upToDate + " ok, "
                    + userManaged + " user";
        }
    }

    /**
     * Scopes the mojos should run for given a project-required
     * context: in a project, both scopes apply; standalone, only
     * user.
     *
     * @param inProject whether the mojo invocation has a project
     * @return scopes to process, in order
     */
    public static List<ScaffoldScope> scopesToProcess(boolean inProject) {
        List<ScaffoldScope> scopes = new ArrayList<>();
        scopes.add(ScaffoldScope.USER);
        if (inProject) {
            scopes.add(ScaffoldScope.PROJECT);
        }
        return scopes;
    }
}
