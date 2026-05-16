package network.ike.plugin.support;

import java.nio.file.Path;

/**
 * The report a goal produces — which goal it is, where its report
 * file lands, and the Markdown body.
 *
 * <p>Returned by {@link AbstractGoalMojo#runGoal()}; the base class
 * writes it via {@link GoalReport}. Making the report a <em>required
 * return value</em> — rather than an optional {@code writeReport(...)}
 * call a goal author can forget — means a goal cannot compile without
 * producing one. That is the structural fix for the missing-report
 * bug class (IKE-Network/ike-issues#413, motivated by #407).
 *
 * @param goal        the goal that produced this report
 * @param projectRoot directory the report file is written into
 *                    (alongside the invoking {@code pom.xml})
 * @param content     the Markdown report body
 */
public record GoalReportSpec(GoalRef goal, Path projectRoot,
                             String content) {
}
