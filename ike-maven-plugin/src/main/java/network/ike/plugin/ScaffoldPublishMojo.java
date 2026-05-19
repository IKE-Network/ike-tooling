package network.ike.plugin;

import network.ike.plugin.scaffold.DirectoryTemplateSource;
import network.ike.plugin.scaffold.FoundationBaker;
import network.ike.plugin.scaffold.FoundationDriftChecker;
import network.ike.plugin.scaffold.ModelAdapters;
import network.ike.plugin.scaffold.OrphanEntry;
import network.ike.plugin.scaffold.OrphanScanner;
import network.ike.plugin.scaffold.PathResolver;
import network.ike.plugin.scaffold.ScaffoldApplier;
import network.ike.plugin.scaffold.ScaffoldException;
import network.ike.plugin.scaffold.ScaffoldLockfile;
import network.ike.plugin.scaffold.ScaffoldLockfileIo;
import network.ike.plugin.scaffold.ScaffoldManifest;
import network.ike.plugin.scaffold.ScaffoldMojoSupport;
import network.ike.plugin.scaffold.ScaffoldMojoSupport.Counts;
import network.ike.plugin.scaffold.ScaffoldPlan;
import network.ike.plugin.scaffold.ScaffoldPlanner;
import network.ike.plugin.scaffold.ScaffoldScope;
import network.ike.plugin.scaffold.TemplateSource;
import network.ike.plugin.scaffold.TierHandlers;
import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.plugin.support.version.SessionCandidateVersionResolver;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Apply the scaffold manifest to disk and update the lockfiles.
 *
 * <p>Runs {@link ScaffoldPlanner} followed by {@link ScaffoldApplier}
 * in each applicable scope. Writes the per-file results into:
 *
 * <ul>
 *   <li>{@code {projectRoot}/.ike/scaffold.lock} — commits to the
 *       project's git history</li>
 *   <li>{@code {userHome}/.ike/scaffold.lock} — tracks user-home
 *       state on this machine</li>
 * </ul>
 *
 * <p>Write-actions land atomically (tmp file + move with
 * {@code ATOMIC_MOVE + REPLACE_EXISTING}). Skip actions — tracked
 * files the user has edited — are left alone; they are reported
 * but never overwritten.
 *
 * <p>Running with {@code projectRequired = false} means this goal
 * works both inside a project (project + user scope) and on a fresh
 * machine (user scope only, for bootstrap of git hooks,
 * {@code ~/.m2/settings.xml}, etc.).
 *
 * <p>Use {@code ike:scaffold-draft} first to preview changes.
 *
 * @see ScaffoldDraftMojo
 * @see ScaffoldRevertMojo
 */
@Mojo(name = IkeGoal.NAME_SCAFFOLD_PUBLISH, projectRequired = false,
      aggregator = true)
public class ScaffoldPublishMojo extends AbstractGoalMojo {

    /**
     * Path to an unpacked scaffold tree containing
     * {@code scaffold-manifest.yaml} and the template files it
     * references.
     *
     * <p>Defaults to {@code ${project.build.directory}/scaffold},
     * matching the unpack location wired into {@code ike-parent}'s
     * {@code unpack-scaffold-templates} execution (#243). Override
     * with {@code -DscaffoldDir=...} for ad-hoc invocations against
     * a custom scaffold tree.
     */
    @Parameter(property = "scaffoldDir",
               defaultValue = "${project.build.directory}/scaffold")
    String scaffoldDir;

    /**
     * Explicit override for the project root. When omitted, the goal
     * uses {@link Session#getTopDirectory()} (the directory Maven was
     * invoked from); a missing {@code pom.xml} at that location signals
     * fresh-machine mode and the project scope is skipped.
     */
    @Parameter(property = "projectRoot")
    String projectRoot;

    /**
     * Override for the user home.
     */
    @Parameter(property = "userHome",
               defaultValue = "${user.home}")
    String userHome;

    /**
     * When {@code true}, apply foundation-drift bumps to the project's
     * {@code pom.xml} (parent version + standard properties baked
     * into the scaffold manifest's {@code foundation:} section). When
     * {@code false} (default for this initial v153 ship), the
     * foundation drift is reported only — same as
     * {@code ike:scaffold-draft}. Opt-in so the apply behavior can
     * be validated over a few cascade cycles before flipping the
     * default. See {@code IKE-Network/ike-issues#348}.
     */
    @Parameter(property = "ike.scaffold.apply-foundation",
               defaultValue = "false")
    boolean applyFoundation;

    /**
     * When {@code true}, resolve the <em>latest released</em> foundation
     * versions from the remote repository and apply those, instead of
     * the snapshot baked into the scaffold zip's {@code foundation:}
     * block.
     *
     * <p>This is the escape hatch for the scaffold bootstrap loop: a
     * consumer's scaffold plugin and scaffold zip are both pinned by the
     * very {@code ike-parent} the foundation apply is meant to bump, so
     * the baked snapshot can only ever advance a consumer one
     * tested-together cycle per run. With this flag a stale consumer
     * jumps straight to the current foundation in a single run — at the
     * cost of the "tested-together snapshot" guarantee the baked block
     * carries.
     *
     * <p>Only meaningful together with
     * {@code -Dike.scaffold.apply-foundation=true}; on its own it just
     * changes what the dry-run reports. A transient resolver failure is
     * non-fatal — the goal falls back to the baked snapshot.
     */
    @Parameter(property = "ike.scaffold.resolve-foundation",
               defaultValue = "false")
    boolean resolveFoundation;

    /**
     * When {@code true}, the foundation-apply skips the {@code <parent>}
     * pin and rewrites only the property pins ({@code ike-tooling.version},
     * {@code ike-docs.version}, {@code ike-platform.version}).
     *
     * <p>{@code ws:scaffold-publish} forwards this: in a workspace the
     * {@code <parent>} version is owned by the workspace's
     * {@code ParentVersionReconciler}, which cascades one coherent
     * version across the whole reactor. Without this flag the
     * per-subproject foundation-apply would run after the reconciler
     * and overwrite its cascade with the baked snapshot's parent
     * version (IKE-Network/ike-issues#418). Standalone
     * {@code ike:scaffold-publish} leaves it {@code false} and keeps
     * writing {@code <parent>} from the snapshot.
     */
    @Parameter(property = "ike.scaffold.skip-parent",
               defaultValue = "false")
    boolean skipParent;

    /** Creates this goal instance. */
    public ScaffoldPublishMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        try {
            return runPublish();
        } catch (ScaffoldException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private GoalReportSpec runPublish() {
        Path scaffoldRoot = Path.of(scaffoldDir);
        ScaffoldManifest manifest =
                ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        TemplateSource templates =
                new DirectoryTemplateSource(scaffoldRoot);
        Path home = Path.of(userHome);
        Path projRoot = ScaffoldMojoSupport.resolveProjectRoot(
                projectRoot, getSession());
        PathResolver resolver = new PathResolver(home, projRoot);
        ScaffoldPlanner planner = new ScaffoldPlanner(
                new TierHandlers(), new ModelAdapters());
        ScaffoldApplier applier = new ScaffoldApplier();

        getLog().info("");
        getLog().info(IkeGoal.SCAFFOLD_PUBLISH.qualified());
        getLog().info("  scaffold dir:      " + scaffoldRoot);
        getLog().info("  standards version: "
                + manifest.standardsVersion());
        getLog().info("  user home:         " + home);
        getLog().info("  project root:      "
                + (projRoot == null ? "(none — fresh machine)"
                        : projRoot));
        getLog().info("");

        // User scope
        Path userLock = ScaffoldMojoSupport.userLockfilePath(home);
        ScaffoldLockfile userLockfile =
                ScaffoldMojoSupport.loadLockfileOrEmpty(userLock);
        ScaffoldPlan userPlan = planner.plan(
                manifest, userLockfile, ScaffoldScope.USER,
                resolver, templates);
        ScaffoldMojoSupport.logLines(getLog(),
                ScaffoldMojoSupport.renderPlanReport(
                        userPlan, ScaffoldScope.USER));
        ScaffoldLockfile updatedUser = applier.apply(
                userPlan, userLockfile);
        List<OrphanEntry> orphans = new ArrayList<>();
        updatedUser = removeOrphans(applier, manifest, userLockfile,
                updatedUser, ScaffoldScope.USER, resolver, orphans);
        ScaffoldLockfileIo.write(updatedUser, userLock);
        getLog().info("  → " + userLock);

        // Project scope (only if we have a project)
        Counts projectCounts = null;
        if (projRoot != null) {
            Path projLock =
                    ScaffoldMojoSupport.projectLockfilePath(projRoot);
            ScaffoldLockfile projLockfile =
                    ScaffoldMojoSupport.loadLockfileOrEmpty(projLock);
            ScaffoldPlan projectPlan = planner.plan(
                    manifest, projLockfile, ScaffoldScope.PROJECT,
                    resolver, templates);
            ScaffoldMojoSupport.logLines(getLog(),
                    ScaffoldMojoSupport.renderPlanReport(
                            projectPlan, ScaffoldScope.PROJECT));
            ScaffoldLockfile updatedProj = applier.apply(
                    projectPlan, projLockfile);
            updatedProj = removeOrphans(applier, manifest, projLockfile,
                    updatedProj, ScaffoldScope.PROJECT, resolver,
                    orphans);
            ScaffoldLockfileIo.write(updatedProj, projLock);
            getLog().info("  → " + projLock);
            projectCounts = ScaffoldMojoSupport
                    .countActions(projectPlan);
        }

        Counts userCounts = ScaffoldMojoSupport.countActions(userPlan);
        getLog().info("");
        getLog().info("Publish summary:");
        getLog().info("  user:    " + userCounts.summary());
        if (projectCounts != null) {
            getLog().info("  project: " + projectCounts.summary());
        }
        getLog().info("");
        int totalSkipped = userCounts.skip()
                + (projectCounts != null ? projectCounts.skip() : 0);
        if (totalSkipped > 0) {
            getLog().info(
                    totalSkipped + " entry(ies) were skipped "
                            + "(user-edited). "
                            + "Run " + IkeGoal.SCAFFOLD_DRAFT.qualified()
                            + " for details.");
        }
        if (!orphans.isEmpty()) {
            long removed = orphanCount(orphans,
                    OrphanEntry.Disposition.REMOVE);
            long kept = orphanCount(orphans,
                    OrphanEntry.Disposition.SKIP_USER_EDITED);
            long cleared = orphanCount(orphans,
                    OrphanEntry.Disposition.ALREADY_ABSENT);
            getLog().info(orphans.size() + " orphan(s) — files the "
                    + "scaffold no longer ships: " + removed
                    + " removed, " + kept + " kept (user-edited), "
                    + cleared + " lockfile-only.");
        }

        // #348: foundation-drift apply. Detection landed in #345's
        // scaffold-draft; this is the matching apply step. Opt-in via
        // -Dike.scaffold.apply-foundation=true for the initial v153
        // ship. The scaffold zip's foundation pins represent the
        // tested-together compatibility snapshot of ike-parent +
        // standard properties at the moment this ike-tooling version
        // was released, so applying them is a single-command "bump
        // foundation to current" operation that subsumes the routine
        // use case of ws:scaffold-publish's parent + version-upgrade
        // reconcilers.
        if (projRoot != null && manifest.foundation() != null) {
            applyFoundationDrift(projRoot, manifest.foundation());
        }

        return new GoalReportSpec(IkeGoal.SCAFFOLD_PUBLISH,
                projRoot != null ? projRoot : home,
                buildReport(manifest, userCounts, projectCounts,
                        orphans));
    }

    /**
     * Scan one scope for orphaned scaffold files — lockfile entries
     * the current manifest no longer ships — log them, and remove the
     * unedited ones from disk and the lockfile.
     *
     * @param applier   the scaffold applier
     * @param manifest  the scaffold manifest
     * @param priorLock the scope's lockfile before this publish
     * @param updated   the post-apply lockfile to prune
     * @param scope     the scope to scan
     * @param resolver  path resolver
     * @param collected accumulator the caller uses for the summary —
     *                  every orphan found is added here
     * @return the lockfile with removed/absent orphan entries dropped
     */
    private ScaffoldLockfile removeOrphans(
            ScaffoldApplier applier,
            ScaffoldManifest manifest,
            ScaffoldLockfile priorLock,
            ScaffoldLockfile updated,
            ScaffoldScope scope,
            PathResolver resolver,
            List<OrphanEntry> collected) {
        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifest, priorLock, scope, resolver);
        if (orphans.isEmpty()) {
            return updated;
        }
        ScaffoldMojoSupport.logLines(getLog(),
                ScaffoldMojoSupport.renderOrphanReport(orphans, scope));
        collected.addAll(orphans);
        return applier.removeOrphans(orphans, updated);
    }

    private static long orphanCount(
            List<OrphanEntry> orphans,
            OrphanEntry.Disposition disposition) {
        return orphans.stream()
                .filter(o -> o.disposition() == disposition)
                .count();
    }

    /**
     * Build the Markdown report body for {@code ike:scaffold-publish}.
     *
     * @param manifest      the scaffold manifest
     * @param userCounts    applied-action counts for the user scope
     * @param projectCounts applied-action counts for the project
     *                      scope, or {@code null} on a fresh machine
     * @param orphans       orphaned files the manifest no longer ships
     * @return the report body
     */
    private static String buildReport(ScaffoldManifest manifest,
                                       Counts userCounts,
                                       Counts projectCounts,
                                       List<OrphanEntry> orphans) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("Applied the scaffold manifest to disk.");
        report.bullet("standards version: `"
                + manifest.standardsVersion() + "`");
        report.bullet("user scope: " + userCounts.summary());
        if (projectCounts != null) {
            report.bullet("project scope: " + projectCounts.summary());
        } else {
            report.bullet("project scope: (none — fresh machine)");
        }
        if (!orphans.isEmpty()) {
            report.bullet("orphans: " + orphanCount(orphans,
                    OrphanEntry.Disposition.REMOVE) + " removed, "
                    + orphanCount(orphans,
                    OrphanEntry.Disposition.SKIP_USER_EDITED)
                    + " kept (user-edited)");
        }
        int totalSkipped = userCounts.skip()
                + (projectCounts != null ? projectCounts.skip() : 0);
        if (totalSkipped > 0) {
            report.paragraph(totalSkipped
                    + " entry(ies) skipped (user-edited) — run"
                    + " `" + IkeGoal.SCAFFOLD_DRAFT.qualified()
                    + "` for details.");
        }
        return report.build();
    }

    /**
     * Apply foundation-drift bumps to the project's {@code pom.xml}.
     *
     * <p>Reads the POM, computes drift via
     * {@link FoundationDriftChecker}, and for each
     * {@link FoundationDriftChecker.State#DIFFERS} entry, rewrites
     * the POM via {@link PomRewriter}:
     * <ul>
     *   <li>{@link FoundationDriftChecker.Kind#PARENT} —
     *       {@code PomRewriter.updateParentVersion}</li>
     *   <li>{@link FoundationDriftChecker.Kind#PROPERTY} —
     *       {@code PomRewriter.updateProperty}</li>
     * </ul>
     *
     * <p>{@code ABSENT} entries are left alone — the project inherits
     * the value from a parent POM (or simply doesn't carry it), and
     * force-declaring it here would change the structural shape of
     * the consumer's POM beyond a drift bump. {@code ALIGNED}
     * entries are no-ops.
     *
     * <p>When {@code applyFoundation} is {@code false} (the default),
     * this method just logs what would be applied without mutating
     * the POM — matching {@code ike:scaffold-draft}'s report.
     *
     * @param projRoot   the project root directory
     * @param foundation the scaffold manifest's foundation pins
     */
    private void applyFoundationDrift(
            Path projRoot,
            ScaffoldManifest.Foundation foundation) {
        if (resolveFoundation) {
            getLog().info("");
            try {
                foundation = FoundationBaker.latestFoundation(foundation,
                        new SessionCandidateVersionResolver(getSession()));
                getLog().info("Foundation: resolved latest released "
                        + "versions (resolve-foundation mode).");
            } catch (RuntimeException e) {
                getLog().warn("Foundation: could not resolve latest "
                        + "versions (" + e.getMessage() + ") — falling "
                        + "back to the baked scaffold snapshot.");
            }
        }

        Path pomPath = projRoot.resolve("pom.xml");
        List<FoundationDriftChecker.Entry> entries;
        try {
            entries = FoundationDriftChecker.checkPomFile(
                    pomPath, foundation);
        } catch (IOException e) {
            getLog().warn("Could not read POM for foundation drift "
                    + "apply: " + e.getMessage());
            return;
        }

        List<FoundationDriftChecker.Entry> toApply = new ArrayList<>();
        int parentSkipped = 0;
        for (FoundationDriftChecker.Entry e : entries) {
            if (e.state() != FoundationDriftChecker.State.DIFFERS) {
                continue;
            }
            // #418: in a workspace the <parent> cascade is owned by
            // ParentVersionReconciler; skip the parent pin so this
            // per-subproject apply does not overwrite it.
            if (skipParent
                    && e.kind() == FoundationDriftChecker.Kind.PARENT) {
                parentSkipped++;
                continue;
            }
            toApply.add(e);
        }
        if (parentSkipped > 0) {
            getLog().info("");
            getLog().info("Foundation: <parent> left to the workspace "
                    + "(ParentVersionReconciler owns the parent cascade).");
        }
        if (toApply.isEmpty()) {
            getLog().info("");
            getLog().info("Foundation: aligned with scaffold "
                    + "(no drift to apply).");
            return;
        }

        getLog().info("");
        getLog().info("IKE Foundation Apply:");
        if (!applyFoundation) {
            getLog().info("  (dry-run — pass -Dike.scaffold.apply-foundation=true to apply)");
        }
        String content;
        try {
            content = Files.readString(pomPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("Could not read " + pomPath + ": "
                    + e.getMessage());
            return;
        }

        String updated = content;
        for (FoundationDriftChecker.Entry e : toApply) {
            String label;
            if (e.kind() == FoundationDriftChecker.Kind.PARENT) {
                String[] ga = e.name().split(":", 2);
                if (ga.length == 2) {
                    updated = PomRewriter.updateParentVersion(
                            updated, ga[0], ga[1], e.expected());
                }
                label = "<parent> " + e.name();
            } else {
                updated = PomRewriter.updateProperty(
                        updated, e.name(), e.expected());
                label = "${" + e.name() + "}";
            }
            getLog().info("  " + (applyFoundation ? "✓ " : "→ ")
                    + label + ": " + e.actual() + " → " + e.expected());
        }

        if (!applyFoundation) {
            return;
        }
        if (updated.equals(content)) {
            getLog().info("  (no textual change — values already "
                    + "matched at the LST level)");
            return;
        }
        // #349: capture pre-apply POM as a backup so
        // ike:scaffold-revert can restore. One-shot — the next
        // foundation apply overwrites this, and revert deletes
        // it on success.
        Path backup = projRoot.resolve(".ike")
                .resolve("foundation-revert.pom.xml");
        try {
            Files.createDirectories(backup.getParent());
            Files.writeString(backup, content, StandardCharsets.UTF_8);
            getLog().info("  → backup: " + backup);
        } catch (IOException e) {
            getLog().warn("Could not write foundation revert backup: "
                    + e.getMessage());
            // Still attempt the apply — losing revert capability is
            // worse than no foundation update.
        }
        try {
            Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
            getLog().info("  → wrote " + pomPath);
        } catch (IOException e) {
            getLog().warn("Could not write updated POM: "
                    + e.getMessage());
        }
    }
}
