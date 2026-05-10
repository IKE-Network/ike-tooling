package network.ike.plugin;

import network.ike.plugin.scaffold.DirectoryTemplateSource;
import network.ike.plugin.scaffold.FoundationDriftChecker;
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
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
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

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Maven 4 session — used to derive the project root from
     * {@link Session#getTopDirectory()} when {@code projectRoot} is
     * not supplied. Maven's parameter-default expansion does not
     * interpolate {@code ${project.basedir}} for goals annotated
     * {@code projectRequired = false}, so we resolve it here.
     */
    @Inject
    private Session session;

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
     * fresh-machine bootstrap and the project scope is skipped.
     */
    @Parameter(property = "projectRoot")
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
        Path projRoot = ScaffoldMojoSupport.resolveProjectRoot(
                projectRoot, session);
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

        // #345: report IKE-foundation drift when the manifest carries
        // a foundation: section AND we're in a project context. The
        // scaffold zip's foundation pins represent the tested-together
        // compatibility snapshot of ike-parent + standard properties
        // at the moment this ike-tooling version was released.
        if (projRoot != null && manifest.foundation() != null) {
            reportFoundationDrift(projRoot, manifest.foundation());
        }

        getLog().info("");
        getLog().info(
                "Run ike:scaffold-publish to apply these changes.");
    }

    /**
     * Compute and log foundation drift for the project at
     * {@code projRoot}.
     *
     * @param projRoot   the project root
     * @param foundation manifest's foundation section
     */
    private void reportFoundationDrift(
            Path projRoot,
            ScaffoldManifest.Foundation foundation) {
        java.util.List<FoundationDriftChecker.Entry> entries;
        try {
            entries = FoundationDriftChecker.checkPomFile(
                    projRoot.resolve("pom.xml"), foundation);
        } catch (java.io.IOException e) {
            getLog().debug("Could not check foundation drift: " + e.getMessage());
            return;
        }
        if (entries.isEmpty()) return;

        long drifted = entries.stream()
                .filter(FoundationDriftChecker.Entry::isDrifted)
                .count();

        getLog().info("");
        getLog().info("IKE Foundation Drift:");
        for (FoundationDriftChecker.Entry e : entries) {
            String label = e.kind() == FoundationDriftChecker.Kind.PARENT
                    ? "<parent> " + e.name()
                    : "${" + e.name() + "}";
            String line;
            switch (e.state()) {
                case ALIGNED -> line = "  ✓ " + label + ": " + e.actual();
                case ABSENT -> line = "  · " + label + ": (absent) → "
                        + e.expected() + "  [inherited from parent?]";
                case DIFFERS -> line = "  ✗ " + label + ": " + e.actual()
                        + " → " + e.expected();
                default -> line = "  ? " + label;
            }
            getLog().info(line);
        }
        if (drifted > 0) {
            getLog().info("");
            getLog().info("  " + drifted + " value(s) behind the "
                    + "compatibility snapshot baked into "
                    + "ike-build-standards " + foundation.parent());
            getLog().info("  Foundation apply is not yet wired into "
                    + "ike:scaffold-publish (#345 follow-up); for now,");
            getLog().info("  update these values via "
                    + "ws:versions-upgrade-publish or manual edit.");
        }
    }

    private void logPlan(ScaffoldPlan plan, ScaffoldScope scope) {
        ScaffoldMojoSupport.logLines(getLog(),
                ScaffoldMojoSupport.renderPlanReport(plan, scope));
    }
}
