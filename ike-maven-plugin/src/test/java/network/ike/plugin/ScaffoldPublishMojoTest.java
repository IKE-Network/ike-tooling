package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static network.ike.plugin.ScaffoldDraftMojoTest.RecordingLog;
import static network.ike.plugin.ScaffoldDraftMojoTest.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link ScaffoldPublishMojo}.
 *
 * <p>Verifies that running the goal against a minimal scaffold tree
 * produces the expected on-disk file plus a fresh lockfile. Deeper
 * per-tier apply/revert paths are tested in
 * {@code ScaffoldApplierTest}.
 */
class ScaffoldPublishMojoTest {

    @Test
    void installsToolOwnedFileAndWritesLockfile(@TempDir Path tmp)
            throws Exception {
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

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        // The file should land in the project.
        assertThat(project.resolve("mvnw"))
                .exists()
                .hasContent("#!/bin/sh\nmvnw body\n");
        // And a lockfile should appear.
        Path projLock = project.resolve(".ike/scaffold.lock");
        assertThat(projLock).exists();
        assertThat(Files.readString(projLock))
                .contains("mvnw")
                .contains("tool-owned");
        // User-scope lockfile is also written (empty but stamped).
        assertThat(userHome.resolve(".ike/scaffold.lock")).exists();
        assertThat(log.infos).anyMatch(
                s -> s.contains("Publish summary"));
    }

    @Test
    void freshMachineWithoutProjectRootOnlyTouchesUserScope(
            @TempDir Path tmp) throws Exception {
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

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", "");
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        assertThat(userHome.resolve(".ike/scaffold.lock")).exists();
        assertThat(log.infos).noneMatch(
                s -> s.contains("project:"));
    }

    @Test
    void scaffoldExceptionIsWrappedInMojoException(
            @TempDir Path tmp) throws Exception {
        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        inject(mojo, "log", new RecordingLog());
        inject(mojo, "scaffoldDir", tmp.resolve("missing").toString());
        inject(mojo, "projectRoot", tmp.toString());
        inject(mojo, "userHome", tmp.toString());

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(
                        org.apache.maven.api.plugin.MojoException.class);
    }
}
