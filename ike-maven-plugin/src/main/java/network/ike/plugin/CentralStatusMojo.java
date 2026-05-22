package network.ike.plugin;

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Report the status of asynchronous Maven Central deploys spawned
 * by {@code ike:release-publish -Dike.deploy.central.async=true}
 * (IKE-Network/ike-issues#484).
 *
 * <p>Walks the sentinel directory ({@code ~/.cache/ike-release/}
 * by default) and prints one line per recorded deploy with state,
 * retry cycles, elapsed time, and the log-file path. Exits non-zero
 * when any sentinel is in {@link CentralDeploySentinel.State#FAILURE}
 * — so a shell pipeline can branch on it — but treats
 * {@link CentralDeploySentinel.State#PENDING} as informational
 * (running deploys are normal mid-cascade).
 *
 * <p>Operator-friendly: read-only, no credentials, safe to run any
 * time. Composes into the cascade goal's tail when
 * {@code ike.cascade.waitForCentral} is enabled.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:central-status
 * mvn ike:central-status -Dike.central.sentinelDir=/custom/path
 * mvn ike:central-status -Dike.central.failOnPending=true
 * </pre>
 */
@org.apache.maven.api.plugin.annotations.Mojo(
        name = IkeGoal.NAME_CENTRAL_STATUS, projectRequired = false)
public class CentralStatusMojo implements Mojo {

    /** Maven logger, injected by the plugin runtime. */
    @Inject
    Log log;

    /** @return the injected Maven logger */
    Log getLog() { return log; }

    /**
     * Override the sentinel directory. Defaults to
     * {@code ~/.cache/ike-release/} — the cache location where
     * {@code ike:release-publish} writes status records for
     * async Central deploys.
     */
    @Parameter(property = "ike.central.sentinelDir")
    String sentinelDir;

    /**
     * Exit non-zero when any sentinel is still {@code PENDING}.
     * Defaults to false: a pending deploy mid-cascade is normal
     * informational state, not a failure. Set true when wiring
     * {@code ike:central-status} into a wait-for-completion check.
     */
    @Parameter(property = "ike.central.failOnPending",
            defaultValue = "false")
    boolean failOnPending;

    /** Creates this goal instance. */
    public CentralStatusMojo() {}

    @Override
    public void execute() {
        Path dir = sentinelDir == null || sentinelDir.isBlank()
                ? CentralDeploySentinel.DEFAULT_DIR
                : Paths.get(sentinelDir);
        List<CentralDeploySentinel> sentinels =
                CentralDeploySentinel.listAll(dir);

        getLog().info("Maven Central deploy status — " + dir);
        if (sentinels.isEmpty()) {
            getLog().info("  (no sentinel files found)");
            return;
        }

        int pending = 0, succeeded = 0, failed = 0;
        Instant now = Instant.now();
        for (CentralDeploySentinel s : sentinels) {
            switch (s.state()) {
                case PENDING -> pending++;
                case SUCCESS -> succeeded++;
                case FAILURE -> failed++;
            }
            getLog().info("  " + formatRow(s, now));
            if (s.state() == CentralDeploySentinel.State.FAILURE
                    && s.lastError() != null) {
                getLog().info("      error: " + s.lastError());
            }
            if (s.note() != null) {
                getLog().info("      note:  " + s.note());
            }
            if (s.logFile() != null) {
                getLog().info("      log:   " + s.logFile());
            }
        }
        getLog().info("Total: " + sentinels.size()
                + " (" + pending + " pending, "
                + succeeded + " succeeded, "
                + failed + " failed)");

        if (failed > 0) {
            throw new MojoException(failed
                    + " Central deploy(s) in FAILURE state — "
                    + "see error/log entries above. To retry one: "
                    + "check out the v<version> tag and run "
                    + "`mvn jreleaser:deploy`.");
        }
        if (failOnPending && pending > 0) {
            throw new MojoException(pending
                    + " Central deploy(s) still PENDING and "
                    + "ike.central.failOnPending=true.");
        }
    }

    /**
     * Format a one-line status row for the report.
     *
     * @param s   the sentinel
     * @param now reference instant for elapsed-time math
     * @return the formatted row
     */
    static String formatRow(CentralDeploySentinel s, Instant now) {
        String icon = switch (s.state()) {
            case PENDING -> "⏳";
            case SUCCESS -> "✅";
            case FAILURE -> "❌";
        };
        String coord = s.artifactId() + "-" + s.version();
        String cycles = s.attempts() + "/" + s.maxAttempts();
        String elapsed;
        if (s.state() == CentralDeploySentinel.State.PENDING) {
            elapsed = "running for "
                    + formatDuration(Duration.between(s.started(), now));
        } else if (s.finished() != null) {
            elapsed = "took "
                    + formatDuration(Duration.between(
                            s.started(), s.finished()));
        } else {
            elapsed = "(no finish time)";
        }
        return String.format("%s %-40s %-8s cycle %s, %s",
                icon, coord, s.state(), cycles, elapsed);
    }

    /**
     * Format a {@link Duration} as a compact human string —
     * {@code "12s"}, {@code "3m04s"}, {@code "1h12m"}. Used only
     * for log output, so loss of sub-second precision is fine.
     *
     * @param d duration to format
     * @return compact string
     */
    static String formatDuration(Duration d) {
        long s = d.toSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + String.format("%02ds", s % 60);
        return (s / 3600) + "h" + String.format("%02dm", (s % 3600) / 60);
    }
}
