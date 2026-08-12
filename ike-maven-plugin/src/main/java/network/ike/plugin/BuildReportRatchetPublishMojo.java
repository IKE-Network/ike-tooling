package network.ike.plugin;

import network.ike.plugin.support.GoalReportSpec;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply the downward ratchet: tighten the build-report acceptance
 * ledger to the latest session's observations and rewrite it
 * canonically (ike-issues#989).
 *
 * <p>The {@code -publish} counterpart of
 * {@link BuildReportRatchetDraftMojo ike:build-report-ratchet-draft};
 * all semantics live there.</p>
 */
@Mojo(name = IkeGoal.NAME_BUILD_REPORT_RATCHET_PUBLISH, projectRequired = false,
      aggregator = true)
public class BuildReportRatchetPublishMojo extends BuildReportRatchetDraftMojo {

    /** Creates this goal instance. */
    public BuildReportRatchetPublishMojo() {}

    /**
     * Runs the draft computation in apply mode.
     *
     * @return the goal report
     * @throws MojoException when the ledger or sidecar cannot be read
     *                       or the rewrite fails
     */
    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        publish = true;
        return super.runGoal();
    }
}
