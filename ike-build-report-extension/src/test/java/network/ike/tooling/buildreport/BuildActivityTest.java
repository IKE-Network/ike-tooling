package network.ike.tooling.buildreport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build activity collection on a hand-driven clock.
 */
class BuildActivityTest {

    private final AtomicLong nanos = new AtomicLong();
    private final BuildActivity activity = new BuildActivity(nanos::get);

    @Test
    void modulesCarryResultAndTimeInReactorOrder() {
        activity.sessionStarted(List.of("alpha", "beta", "gamma", "delta"),
                List.of("clean", "install"), List.of(), "4.0.0-rc-7", 1);
        build("alpha", 2, BuildActivity.ModuleResult.BUILT);
        build("beta", 5, BuildActivity.ModuleResult.FAILED);
        activity.projectFinished("gamma", BuildActivity.ModuleResult.SKIPPED);

        BuildActivity.Overview overview = activity.snapshot();

        assertThat(overview.modules()).containsExactly(
                new BuildActivity.Module("alpha", BuildActivity.ModuleResult.BUILT, Duration.ofSeconds(2)),
                new BuildActivity.Module("beta", BuildActivity.ModuleResult.FAILED, Duration.ofSeconds(5)),
                new BuildActivity.Module("gamma", BuildActivity.ModuleResult.SKIPPED, Duration.ZERO),
                new BuildActivity.Module("delta", BuildActivity.ModuleResult.NOT_BUILT, Duration.ZERO));
        assertThat(overview.wallTime()).isEqualTo(Duration.ofSeconds(7));
        assertThat(overview.goals()).containsExactly("clean", "install");
        assertThat(overview.incomplete()).isTrue();
    }

    @Test
    void aModuleInFlightReportsItsGoalAndTimeSoFar() {
        activity.sessionStarted(List.of("alpha", "beta"), List.of("verify"), List.of(), null, 1);
        activity.projectStarted("alpha");
        activity.mojoStarted("alpha", "maven-surefire-plugin:test");
        advance(3);

        assertThat(activity.snapshot().modules()).containsExactly(
                new BuildActivity.Module("alpha", BuildActivity.ModuleResult.BUILDING,
                        Duration.ofSeconds(3), "maven-surefire-plugin:test"),
                new BuildActivity.Module("beta", BuildActivity.ModuleResult.NOT_BUILT, Duration.ZERO));
    }

    @Test
    void goalTimeSumsAcrossModulesSlowestFirst() {
        activity.sessionStarted(List.of("alpha", "beta"), List.of("verify"), List.of(), null, 1);
        for (String module : List.of("alpha", "beta")) {
            activity.projectStarted(module);
            run(module, "maven-compiler-plugin:compile", 1);
            run(module, "maven-surefire-plugin:test", 4);
            activity.projectFinished(module, BuildActivity.ModuleResult.BUILT);
        }

        assertThat(activity.snapshot().goalTimes()).containsExactly(
                new BuildActivity.GoalTime("maven-surefire-plugin:test", 2, Duration.ofSeconds(8)),
                new BuildActivity.GoalTime("maven-compiler-plugin:compile", 2, Duration.ofSeconds(2)));
    }

    @Test
    void currentNamesTheModuleAndGoalOnThisThread() {
        assertThat(activity.current()).isEqualTo(BuildActivity.Context.NONE);

        activity.projectStarted("alpha");
        activity.mojoStarted("alpha", "maven-surefire-plugin:test");
        assertThat(activity.current())
                .isEqualTo(new BuildActivity.Context("alpha", "maven-surefire-plugin:test"));

        activity.mojoFinished();
        assertThat(activity.current()).isEqualTo(new BuildActivity.Context("alpha", ""));
    }

    @Test
    void aForeignThreadIsAttributedToTheOnlyModuleInFlight() throws InterruptedException {
        activity.projectStarted("alpha");
        activity.mojoStarted("alpha", "maven-surefire-plugin:test");
        BuildActivity.Context[] seen = new BuildActivity.Context[1];

        Thread pump = new Thread(() -> seen[0] = activity.current());
        pump.start();
        pump.join();

        assertThat(seen[0]).isEqualTo(new BuildActivity.Context("alpha", "maven-surefire-plugin:test"));
    }

    private void build(String module, int seconds, BuildActivity.ModuleResult result) {
        activity.projectStarted(module);
        advance(seconds);
        activity.projectFinished(module, result);
    }

    private void run(String module, String goal, int seconds) {
        activity.mojoStarted(module, goal);
        advance(seconds);
        activity.mojoFinished();
    }

    private void advance(int seconds) {
        nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }
}
