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
import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportSpec;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
public class ScaffoldRevertMojo extends AbstractGoalMojo {

    /**
     * Path to an unpacked scaffold tree containing
     * {@code scaffold-manifest.yaml}. Only the manifest is needed
     * for revert (templates aren't used); the parameter is kept
     * consistent with {@code ike:scaffold-draft} and
     * {@code ike:scaffold-publish} for symmetry.
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
     * Override for the user home.
     */
    @Parameter(property = "userHome",
               defaultValue = "${user.home}")
    String userHome;

    /** Creates this goal instance. */
    public ScaffoldRevertMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        try {
            return runRevert();
        } catch (ScaffoldException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private GoalReportSpec runRevert() {
        Path scaffoldRoot = Path.of(scaffoldDir);
        ScaffoldManifest manifest =
                ScaffoldMojoSupport.loadManifest(scaffoldRoot);
        Path home = Path.of(userHome);
        Path projRoot = ScaffoldMojoSupport.resolveProjectRoot(
                projectRoot, getSession());
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

        // #349: restore POM from foundation-apply backup, if present.
        // scaffold-publish wrote .ike/foundation-revert.pom.xml with
        // the pre-apply content; replaying it restores the parent
        // version + standard properties that were rewritten.
        if (projRoot != null) {
            restoreFoundationBackup(projRoot);
        }

        return new GoalReportSpec(IkeGoal.SCAFFOLD_REVERT,
                projRoot != null ? projRoot : home,
                buildReport(manifest, userDeleted, userSkipped,
                        projRoot != null, projectDeleted,
                        projectSkipped));
    }

    /**
     * Build the Markdown report body for {@code ike:scaffold-revert}.
     *
     * @param manifest        the scaffold manifest
     * @param userDeleted     files deleted in the user scope
     * @param userSkipped     entries skipped in the user scope
     * @param hasProject      whether a project scope was processed
     * @param projectDeleted  files deleted in the project scope
     * @param projectSkipped  entries skipped in the project scope
     * @return the report body
     */
    private static String buildReport(ScaffoldManifest manifest,
                                       int userDeleted, int userSkipped,
                                       boolean hasProject,
                                       int projectDeleted,
                                       int projectSkipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reverted a previous `ike:scaffold-publish`.\n\n");
        sb.append("- standards version: `")
          .append(manifest.standardsVersion()).append("`\n");
        sb.append("- user scope: ").append(userDeleted)
          .append(" deleted, ").append(userSkipped)
          .append(" skipped\n");
        if (hasProject) {
            sb.append("- project scope: ").append(projectDeleted)
              .append(" deleted, ").append(projectSkipped)
              .append(" skipped\n");
        } else {
            sb.append("- project scope: (none — fresh machine)\n");
        }
        return sb.toString();
    }

    /**
     * Restore the project's {@code pom.xml} from the foundation
     * backup written by the last {@code ike:scaffold-publish} apply
     * (#349). One-shot — the backup is deleted on successful restore
     * so a second {@code scaffold-revert} is a no-op.
     *
     * @param projRoot the project root
     */
    private void restoreFoundationBackup(Path projRoot) {
        Path backup = projRoot.resolve(".ike")
                .resolve("foundation-revert.pom.xml");
        if (!Files.isRegularFile(backup)) return;

        Path pomPath = projRoot.resolve("pom.xml");
        try {
            String content = Files.readString(backup,
                    StandardCharsets.UTF_8);
            Files.writeString(pomPath, content,
                    StandardCharsets.UTF_8);
            Files.delete(backup);
            getLog().info("");
            getLog().info("Foundation: restored " + pomPath
                    + " from backup");
            getLog().info("  → removed " + backup);
        } catch (IOException e) {
            getLog().warn("Could not restore foundation backup: "
                    + e.getMessage());
        }
    }
}
