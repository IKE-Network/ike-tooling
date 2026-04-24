package network.ike.plugin;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link ScaffoldDraftMojo}.
 *
 * <p>Sets up a minimal scaffold tree and exercises the preview
 * code path — no disk mutation, just the plan report. These tests
 * verify the mojo wires {@code ScaffoldMojoSupport} into the
 * planner correctly; per-tier planning logic is covered by
 * {@code ScaffoldPlannerTest}.
 */
class ScaffoldDraftMojoTest {

    @Test
    void runsAgainstMinimalScaffoldAndLogsPlan(
            @TempDir Path tmp) throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path project = tmp.resolve("proj");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(project);
        Files.createDirectories(userHome);
        Files.writeString(scaffold.resolve("mvnw"),
                "#!/bin/sh\nmvnw body\n");
        Files.writeString(
                scaffold.resolve("scaffold-manifest.yaml"),
                """
                        schema: 1
                        standards-version: "7"
                        files:
                          - dest: mvnw
                            scope: project
                            tier: tool-owned
                            source: mvnw
                        """);

        ScaffoldDraftMojo mojo = new ScaffoldDraftMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        // Draft must never touch disk.
        assertThat(project.resolve("mvnw")).doesNotExist();
        assertThat(project.resolve(".ike")).doesNotExist();
        assertThat(userHome.resolve(".ike")).doesNotExist();
        // But it should have logged the plan.
        assertThat(log.infos).anyMatch(
                s -> s.contains("ike:scaffold-draft"));
        assertThat(log.infos).anyMatch(
                s -> s.contains("[INSTALL]"));
        assertThat(log.infos).anyMatch(
                s -> s.contains("Run ike:scaffold-publish"));
    }

    @Test
    void runsOnFreshMachineWithoutProjectRoot(@TempDir Path tmp)
            throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(userHome);
        Files.writeString(
                scaffold.resolve("scaffold-manifest.yaml"),
                """
                        schema: 1
                        standards-version: "7"
                        files: []
                        """);

        ScaffoldDraftMojo mojo = new ScaffoldDraftMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", "");
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        assertThat(log.infos).anyMatch(
                s -> s.contains("fresh machine"));
    }

    @Test
    void scaffoldExceptionIsWrappedInMojoException(
            @TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("nope");
        ScaffoldDraftMojo mojo = new ScaffoldDraftMojo();
        inject(mojo, "log", new RecordingLog());
        inject(mojo, "scaffoldDir", missing.toString());
        inject(mojo, "projectRoot", tmp.toString());
        inject(mojo, "userHome", tmp.toString());

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(
                        org.apache.maven.api.plugin.MojoException.class)
                .hasMessageContaining("not a directory");
    }

    // ── test helpers ────────────────────────────────────────────────

    static void inject(Object target, String fieldName, Object value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    static final class RecordingLog implements Log {
        final List<String> infos = new ArrayList<>();
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence c) {}
        @Override public void debug(CharSequence c, Throwable e) {}
        @Override public void debug(Throwable e) {}
        @Override public void debug(Supplier<String> c) {}
        @Override public void debug(Supplier<String> c, Throwable e) {}
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence c) {
            infos.add(c.toString());
        }
        @Override public void info(CharSequence c, Throwable e) {
            infos.add(c.toString());
        }
        @Override public void info(Throwable e) {}
        @Override public void info(Supplier<String> c) {
            infos.add(c.get());
        }
        @Override public void info(Supplier<String> c, Throwable e) {
            infos.add(c.get());
        }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence c) {}
        @Override public void warn(CharSequence c, Throwable e) {}
        @Override public void warn(Throwable e) {}
        @Override public void warn(Supplier<String> c) {}
        @Override public void warn(Supplier<String> c, Throwable e) {}
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence c) {}
        @Override public void error(CharSequence c, Throwable e) {}
        @Override public void error(Throwable e) {}
        @Override public void error(Supplier<String> c) {}
        @Override public void error(Supplier<String> c, Throwable e) {}
    }
}
