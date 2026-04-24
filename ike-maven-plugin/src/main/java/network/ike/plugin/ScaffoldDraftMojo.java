package network.ike.plugin;

import network.ike.plugin.scaffold.DirectoryTemplateSource;
import network.ike.plugin.scaffold.ModelAdapters;
import network.ike.plugin.scaffold.PathResolver;
import network.ike.plugin.scaffold.ScaffoldException;
import network.ike.plugin.scaffold.ScaffoldLockfile;
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
 * Preview the changes {@code ike:scaffold-publish} would make.
 *
 * <p>Reads the scaffold manifest shipped by
 * {@code ike-build-standards}, compares each entry to the current
 * disk state and scaffold lockfile, and prints a per-entry summary:
 *
 * <ul>
 *   <li>{@code [INSTALL]} — file does not yet exist, will be created</li>
 *   <li>{@code [UPDATE]} — file exists, will be refreshed</li>
 *   <li>{@code [SKIP]} — file has been user-edited and will be left
 *       alone (a unified diff is included for tracked/tracked-block
 *       entries)</li>
 *   <li>{@code [OK]} — file already matches the current template</li>
 * </ul>
 *
 * <p>The goal runs with {@code projectRequired = false} so it can
 * preview user-scope changes (git hooks, {@code ~/.m2/settings.xml})
 * on a fresh machine. In a project it previews both scopes.
 *
 * <p>This goal never touches disk — it's safe to run anytime.
 *
 * <p>Usage:
 * <pre>
 * # From inside a project (previews project + user scopes):
 * mvn ike:scaffold-draft -DscaffoldDir=/path/to/unpacked/scaffold
 *
 * # Fresh-machine bootstrap (user scope only):
 * mvn ike:scaffold-draft -DscaffoldDir=/path/to/unpacked/scaffold
 * </pre>
 *
 * @see ScaffoldPublishMojo
 * @see ScaffoldRevertMojo
 */
@Mojo(name = "scaffold-draft", projectRequired = false,
      aggregator = true)
public class ScaffoldDraftMojo
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
     * references. Typically the result of unpacking the
     * {@code ike-build-standards} scaffold zip.
     */
    @Parameter(property = "scaffoldDir", required = true)
    String scaffoldDir;

    /**
     * Override for the project root. Defaults to the running
     * Maven project's base directory; null when the goal is invoked
     * outside a project (fresh-machine bootstrap).
     */
    @Parameter(property = "projectRoot",
               defaultValue = "${project.basedir}")
    String projectRoot;

    /**
     * Override for the user home. Defaults to {@code user.home}
     * system property.
     */
    @Parameter(property = "userHome",
               defaultValue = "${user.home}")
    String userHome;

    /** Creates this goal instance. */
    public ScaffoldDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        try {
            runDraft();
        } catch (ScaffoldException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private void runDraft() {
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

        getLog().info("");
        getLog().info("ike:scaffold-draft");
        getLog().info("  scaffold dir:      " + scaffoldRoot);
        getLog().info("  standards version: "
                + manifest.standardsVersion());
        getLog().info("  user home:         " + home);
        getLog().info("  project root:      "
                + (projRoot == null ? "(none — fresh machine)"
                        : projRoot));
        getLog().info("");

        // User scope — always planned.
        Path userLock =
                ScaffoldMojoSupport.userLockfilePath(home);
        ScaffoldLockfile userLockfile =
                ScaffoldMojoSupport.loadLockfileOrEmpty(userLock);
        ScaffoldPlan userPlan = planner.plan(
                manifest, userLockfile, ScaffoldScope.USER,
                resolver, templates);
        logPlan(userPlan, ScaffoldScope.USER);

        // Project scope — only when invoked inside a project.
        Counts projectCounts = null;
        if (projRoot != null) {
            Path projLock =
                    ScaffoldMojoSupport.projectLockfilePath(projRoot);
            ScaffoldLockfile projLockfile =
                    ScaffoldMojoSupport.loadLockfileOrEmpty(projLock);
            ScaffoldPlan projectPlan = planner.plan(
                    manifest, projLockfile, ScaffoldScope.PROJECT,
                    resolver, templates);
            logPlan(projectPlan, ScaffoldScope.PROJECT);
            projectCounts =
                    ScaffoldMojoSupport.countActions(projectPlan);
        }

        Counts userCounts =
                ScaffoldMojoSupport.countActions(userPlan);
        getLog().info("");
        getLog().info("Summary:");
        getLog().info("  user:    " + userCounts.summary());
        if (projectCounts != null) {
            getLog().info("  project: " + projectCounts.summary());
        }
        getLog().info("");
        getLog().info(
                "Run ike:scaffold-publish to apply these changes.");
    }

    private void logPlan(ScaffoldPlan plan, ScaffoldScope scope) {
        ScaffoldMojoSupport.logLines(getLog(),
                ScaffoldMojoSupport.renderPlanReport(plan, scope));
    }
}
