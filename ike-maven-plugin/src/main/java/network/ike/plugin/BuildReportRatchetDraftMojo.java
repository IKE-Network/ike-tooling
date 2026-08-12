package network.ike.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.tooling.buildreport.Ledger;
import network.ike.tooling.buildreport.LedgerWriter;
import network.ike.tooling.buildreport.ObservationsFile;
import network.ike.tooling.buildreport.RatchetPlanner;
import network.ike.tooling.buildreport.ReportSession;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Preview tightening the build-report acceptance ledger against the
 * latest session's observations (ike-issues#989).
 *
 * <p>Reads {@code .mvn/build-report.yaml} and the machine-readable
 * sidecar the build-report extension writes at session end
 * ({@code target/build-report-observations.yaml} — data, not scraped
 * receipt Markdown), and reports which accepted counts would move
 * <em>down</em>. Counts never loosen mechanically: accepting new or
 * grown findings is a deliberate human edit. Side-effect-free; the
 * {@code -publish} counterpart applies the plan.
 *
 * @see BuildReportRatchetPublishMojo
 */
@Mojo(name = IkeGoal.NAME_BUILD_REPORT_RATCHET_DRAFT, projectRequired = false,
      aggregator = true)
public class BuildReportRatchetDraftMojo extends AbstractGoalMojo {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * When {@code true}, the plan is applied and the ledger rewritten
     * canonically; set by {@link BuildReportRatchetPublishMojo}.
     */
    protected boolean publish;

    /** Creates this goal instance. */
    public BuildReportRatchetDraftMojo() {}

    /**
     * Computes (and in publish mode applies) the downward ratchet plan.
     *
     * @return the goal report
     * @throws MojoException when the ledger or sidecar cannot be read,
     *                       or the rewritten ledger cannot be written
     */
    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        IkeGoal goal = publish
                ? IkeGoal.BUILD_REPORT_RATCHET_PUBLISH
                : IkeGoal.BUILD_REPORT_RATCHET_DRAFT;
        Path root = getSession().getTopDirectory();
        Path ledgerPath = root.resolve(ReportSession.LEDGER_RELATIVE_PATH);
        Path sidecarPath = root.resolve(ReportSession.OBSERVATIONS_RELATIVE_PATH);

        StringBuilder body = new StringBuilder(512);
        body.append('_').append(STAMP.format(LocalDateTime.now())).append("_\n\n");

        Ledger ledger;
        try {
            ledger = Ledger.load(ledgerPath);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoException("Cannot read " + ledgerPath + ": " + e.getMessage(), e);
        }
        if (ledger.entries().isEmpty()) {
            body.append("No accepted entries in `")
                    .append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` — nothing to ratchet.\n");
            getLog().info("build-report ratchet: no accepted entries — nothing to do");
            return new GoalReportSpec(goal, root, body.toString());
        }

        Map<String, Long> observed;
        try {
            observed = ObservationsFile.readObserved(sidecarPath);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoException("Cannot read " + sidecarPath + ": " + e.getMessage(), e);
        }
        if (observed.isEmpty()) {
            body.append("No observations sidecar at `")
                    .append(ReportSession.OBSERVATIONS_RELATIVE_PATH)
                    .append("` — run a build with the build-report extension "
                            + "active, then re-run this goal.\n");
            getLog().warn("build-report ratchet: no observations sidecar — run a build first");
            return new GoalReportSpec(goal, root, body.toString());
        }

        RatchetPlanner.Plan plan = RatchetPlanner.plan(ledger, observed);
        if (!plan.changesAnything()) {
            body.append("Ledger is already tight: every accepted count is at "
                    + "or below its latest observation.\n");
            getLog().info("build-report ratchet: ledger already tight");
            return new GoalReportSpec(goal, root, body.toString());
        }

        body.append(publish ? "## Tightened\n\n" : "## Would tighten\n\n");
        for (RatchetPlanner.Tightening tightening : plan.tightenings()) {
            body.append("- `").append(tightening.entry().key()).append("` — count ")
                    .append(tightening.entry().count()).append(" → ")
                    .append(tightening.observed());
            if (tightening.observed() == 0) {
                body.append(" (kept at 0 as a documented tripwire)");
            }
            body.append('\n');
            getLog().info("  " + (publish ? "tightened " : "would tighten ")
                    + tightening.entry().key() + ": " + tightening.entry().count()
                    + " -> " + tightening.observed());
        }
        body.append('\n');

        if (publish) {
            try {
                LedgerWriter.write(ledgerPath, plan.result());
            } catch (IOException e) {
                throw new MojoException("Cannot write " + ledgerPath + ": " + e.getMessage(), e);
            }
            body.append("Ledger rewritten canonically at `")
                    .append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("`. Counts only move down; loosening is a human edit.\n");
        } else {
            body.append("Apply with `mvn ike:")
                    .append(IkeGoal.NAME_BUILD_REPORT_RATCHET_PUBLISH).append("`.\n");
        }
        return new GoalReportSpec(goal, root, body.toString());
    }
}
