package network.ike.plugin;

import network.ike.plugin.scaffold.ModelAdapters;
import network.ike.plugin.scaffold.PathResolver;
import network.ike.plugin.scaffold.ScaffoldException;
import network.ike.plugin.scaffold.ScaffoldLockfile;
import network.ike.plugin.scaffold.ScaffoldLockfileIo;
import network.ike.plugin.scaffold.ScaffoldManifest;
import network.ike.plugin.scaffold.ScaffoldMojoSupport;
import network.ike.plugin.scaffold.ScaffoldReverter;
import network.ike.plugin.scaffold.ScaffoldScope;
import network.ike.plugin.scaffold.TierHandlers;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;

/**
 * Undo a previous {@code ike:scaffold-publish}.
 *
 * <p>Reads the scaffold manifest to learn the tier policy for every
 * entry, then for each entry recorded in the appropriate lockfile:
 *
 * <ul>
 *   <li>{@code tool-owned} / {@code tracked} — delete the file if
 *       its on-disk hash still matches what was applied; otherwise
 *       leave it and report a skip.</li>
 *   <li>{@code tracked-block} and {@code model-managed} — not yet
 *       implemented; the goal reports these as skipped so the user
 *       can clean them up manually.</li>
 * </ul>
 *
 * <p>Conservative by design — anything the user may have touched is
 * left alone. The updated lockfiles (with reverted entries removed)
 * are written back to disk.
 *
 * <p>Runs with {@code projectRequired = false} so it can clear
 * user-scope state on a fresh machine.
 *
 * <p>Modelled after ModelAdapters + TierHandlers registries; uses
 * {@link ScaffoldReverter} under the hood. Template resolution is
 * not needed for revert (only manifest + lockfile + disk), but the
 * manifest must still be available so scope and tier information
 * are known per entry.
 *
 * @see ScaffoldDraftMojo
 * @see ScaffoldPublishMojo
 */
@Mojo(name = "scaffold-revert", projectRequired = false,
      aggregator = true)
public class ScaffoldRevertMojo
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
     * {@code scaffold-manifest.yaml}. Only the manifest is needed
     * for revert (templates aren't used); the parameter is kept
     * consistent with {@code ike:scaffold-draft} and
     * {@code ike:scaffold-publish} for symmetry.
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
    public ScaffoldRevertMojo() {}

    @Override
    public void execute() throws MojoException {
        try {
            runRevert();
        } catch (ScaffoldException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private void runRevert() {
        Path scaffoldRoot = Path.of(scaffoldDir);
        ScaffoldManifest manifest =
                ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        Path home = Path.of(userHome);
        Path projRoot = (projectRoot == null || projectRoot.isBlank())
                ? null
                : Path.of(projectRoot);
        PathResolver resolver = new PathResolver(home, projRoot);

        // Instantiate the registries so adapter-specific revert
        // logic is available when (later) tracked-block and
        // model-managed revert are implemented.
        @SuppressWarnings("unused")
        TierHandlers tierHandlers = new TierHandlers();
        @SuppressWarnings("unused")
        ModelAdapters modelAdapters = new ModelAdapters();
        ScaffoldReverter reverter = new ScaffoldReverter();

        getLog().info("");
        getLog().info("ike:scaffold-revert");
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
        ScaffoldReverter.RevertResult userResult = reverter.revert(
                userLockfile, manifest, ScaffoldScope.USER, resolver);
        ScaffoldMojoSupport.logLines(getLog(),
                ScaffoldMojoSupport.renderRevertReport(
                        userResult, ScaffoldScope.USER));
        ScaffoldLockfileIo.write(
                userResult.updatedLockfile(), userLock);
        getLog().info("  → " + userLock);

        // Project scope (only when we have a project)
        int projectDeleted = 0, projectSkipped = 0;
        if (projRoot != null) {
            Path projLock =
                    ScaffoldMojoSupport.projectLockfilePath(projRoot);
            ScaffoldLockfile projLockfile =
                    ScaffoldMojoSupport.loadLockfileOrEmpty(projLock);
            ScaffoldReverter.RevertResult projectResult =
                    reverter.revert(
                            projLockfile, manifest,
                            ScaffoldScope.PROJECT, resolver);
            ScaffoldMojoSupport.logLines(getLog(),
                    ScaffoldMojoSupport.renderRevertReport(
                            projectResult, ScaffoldScope.PROJECT));
            ScaffoldLockfileIo.write(
                    projectResult.updatedLockfile(), projLock);
            getLog().info("  → " + projLock);
            for (ScaffoldReverter.Outcome o
                    : projectResult.outcomes()) {
                if (o.kind()
                        == ScaffoldReverter.Outcome.Kind.DELETED) {
                    projectDeleted++;
                } else if (o.kind()
                        == ScaffoldReverter.Outcome.Kind.SKIPPED) {
                    projectSkipped++;
                }
            }
        }

        int userDeleted = 0, userSkipped = 0;
        for (ScaffoldReverter.Outcome o : userResult.outcomes()) {
            if (o.kind() == ScaffoldReverter.Outcome.Kind.DELETED) {
                userDeleted++;
            } else if (o.kind()
                    == ScaffoldReverter.Outcome.Kind.SKIPPED) {
                userSkipped++;
            }
        }

        getLog().info("");
        getLog().info("Revert summary:");
        getLog().info("  user:    " + userDeleted + " deleted, "
                + userSkipped + " skipped");
        if (projRoot != null) {
            getLog().info("  project: " + projectDeleted
                    + " deleted, " + projectSkipped + " skipped");
        }
    }
}
