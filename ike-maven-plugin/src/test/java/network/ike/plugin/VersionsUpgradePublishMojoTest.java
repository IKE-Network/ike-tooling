package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import org.apache.maven.api.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VersionsUpgradePublishMojo}.
 *
 * <p>Covers the "no plan file" → success path: when a sibling reactor
 * module had nothing to upgrade in the prior {@code -draft} run, its
 * plan file is absent. Publish must treat this as a clean no-op rather
 * than failing the cascade.
 */
class VersionsUpgradePublishMojoTest {

    @Test
    void missingPlanFileExitsCleanlyWithInfoLog(@TempDir Path tmp)
            throws Exception {
        Path basedir = tmp.resolve("its");
        Path pom = basedir.resolve("pom.xml");
        Path planPath = basedir.resolve("versions-upgrade-plan.yaml");
        // Note: directory and pom intentionally not created — the goal
        // must not access either before checking the plan path.

        VersionsUpgradePublishMojo mojo = new VersionsUpgradePublishMojo();
        RecordingLog log = new RecordingLog();
        injectLog(mojo, log);
        injectProject(mojo, basedir, pom, "its");
        mojo.planFile = planPath.toString();

        // Must not throw — a missing plan is a clean no-op.
        mojo.execute();

        assertThat(log.infos)
                .anyMatch(s -> s.contains("no plan for its"));
        // No plan file was created and no exception bubbled up.
        assertThat(planPath).doesNotExist();
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static void injectLog(Object target, Object value)
            throws Exception {
        Field f = AbstractGoalMojo.class.getDeclaredField("log");
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void injectProject(Object target, Path basedir,
                                      Path pomPath, String artifactId)
            throws Exception {
        Project project = stubProject(basedir, pomPath, artifactId);
        Field f = target.getClass().getDeclaredField("project");
        f.setAccessible(true);
        f.set(target, project);
    }

    private static Project stubProject(Path basedir, Path pomPath,
                                       String artifactId) {
        Map<String, Object> overrides = Map.of(
                "getBasedir", basedir,
                "getPomPath", pomPath,
                "getArtifactId", artifactId);
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    Object override = overrides.get(method.getName());
                    if (override != null) return override;
                    return defaultReturn(method);
                });
    }

    private static Object defaultReturn(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) return false;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == java.util.List.class) return List.of();
        if (ret == Map.class) return Map.of();
        if (ret == java.util.Optional.class) return java.util.Optional.empty();
        return null;
    }

    static final class RecordingLog
            implements org.apache.maven.api.plugin.Log {
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
