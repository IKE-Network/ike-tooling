package network.ike.plugin;

import network.ike.plugin.scaffold.DirectoryTemplateSource;
import network.ike.plugin.scaffold.ModelAdapters;
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
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;

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
@Mojo(name = "scaffold-publish", projectRequired = false,
      aggregator = true)
public class ScaffoldPublishMojo
        implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Path to an unpacked scaffold tree containing
     * {@code scaffold-manifest.yaml} and the template files it
     * references.
     */
    @Parameter(property = "scaffoldDir", required = true)
    String scaffoldDir;

    /**
     * Override for the project root. Defaults to the running
     * Maven project's base directory; null when invoked outside
     * a project.
     */
    @Parameter(property = "projectRoot",
               defaultValue = "${project.basedir}")
    String projectRoot;

    /**
     * Override for the user home.
     */
    @Parameter(property = "userHome",
               defaultValue = "${user.home}")
    String userHome;

    /** Creates this goal instance. */
    public ScaffoldPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        try {
            runPublish();
        } catch (ScaffoldException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private void runPublish() {
        Path scaffoldRoot = Path.of(scaffoldDir);
        ScaffoldManifest manifest =
                ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        TemplateSource templates =
                new DirectoryTemplateSource(scaffoldRoot);
        Path home = Path.of(userHome);
        Path projRoot = (projectRoot == null || projectRoot.isBlank())
                ? null
                : Path.of(projectRoot);
        PathResolver resolver = new PathResolver(home, projRoot);
        ScaffoldPlanner planner = new ScaffoldPlanner(
                new TierHandlers(), new ModelAdapters());
        ScaffoldApplier applier = new ScaffoldApplier();

        getLog().info("");
        getLog().info("ike:scaffold-publish");
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
                            + "Run ike:scaffold-draft for details.");
        }
    }
}
