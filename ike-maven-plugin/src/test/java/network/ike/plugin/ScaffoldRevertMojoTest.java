package network.ike.plugin;

import network.ike.plugin.scaffold.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static network.ike.plugin.ScaffoldDraftMojoTest.RecordingLog;
import static network.ike.plugin.ScaffoldDraftMojoTest.inject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link ScaffoldRevertMojo}.
 *
 * <p>Verifies that running the goal against a known-applied state
 * deletes the tracked file, updates the lockfile, and writes a
 * revert summary. Deeper per-tier revert logic is covered by
 * {@code ScaffoldReverterTest}.
 */
class ScaffoldRevertMojoTest {

    @Test
    void deletesToolOwnedFileAndRewritesLockfile(@TempDir Path tmp)
            throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path project = tmp.resolve("proj");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(project);
        Files.createDirectories(userHome);
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

        byte[] mvnw = "#!/bin/sh\nmvnw body\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(project.resolve("mvnw"), mvnw);
        Files.createDirectories(project.resolve(".ike"));
        String sha = Sha256.of(mvnw);
        Files.writeString(
                project.resolve(".ike/scaffold.lock"),
                """
                        schema: 1
                        standards-version: "7"
                        applied: "2026-04-23T12:00:00Z"
                        files:
                          mvnw:
                            tier: tool-owned
                            template-sha: "%s"
                            applied-sha: "%s"
                        """.formatted(sha, sha));

        ScaffoldRevertMojo mojo = new ScaffoldRevertMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        // File is gone.
        assertThat(project.resolve("mvnw")).doesNotExist();
        // Lockfile still exists, entry removed.
        Path projLock = project.resolve(".ike/scaffold.lock");
        assertThat(projLock).exists();
        assertThat(Files.readString(projLock))
                .doesNotContain("mvnw:");
        assertThat(log.infos).anyMatch(
                s -> s.contains("Revert summary"));
        assertThat(log.infos).anyMatch(
                s -> s.contains("[DELETED]"));
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

        ScaffoldRevertMojo mojo = new ScaffoldRevertMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", "");
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        assertThat(log.infos).anyMatch(
                s -> s.contains("Revert summary"));
        assertThat(log.infos).noneMatch(
                s -> s.contains("project:"));
    }

    @Test
    void scaffoldExceptionIsWrappedInMojoException(
            @TempDir Path tmp) throws Exception {
        ScaffoldRevertMojo mojo = new ScaffoldRevertMojo();
        inject(mojo, "log", new RecordingLog());
        inject(mojo, "scaffoldDir", tmp.resolve("missing").toString());
        inject(mojo, "projectRoot", tmp.toString());
        inject(mojo, "userHome", tmp.toString());

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(
                        org.apache.maven.api.plugin.MojoException.class);
    }

    // ─── #349: foundation-apply backup restore ─────────────────────

    @Test
    void foundationRevert_restoresPomFromBackup(@TempDir Path tmp)
            throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path project = tmp.resolve("proj");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(project);
        Files.createDirectories(userHome);
        Files.writeString(
                scaffold.resolve("scaffold-manifest.yaml"),
                """
                        schema: 1
                        standards-version: "7"
                        files: []
                        """);

        // Apply already happened: pom.xml has the post-apply content;
        // .ike/foundation-revert.pom.xml has the pre-apply content.
        String preApply = """
                <?xml version="1.0"?>
                <project>
                    <parent>
                        <version>35</version>
                    </parent>
                    <artifactId>x</artifactId>
                </project>
                """;
        String postApply = preApply.replace("35", "36");
        Files.writeString(project.resolve("pom.xml"), postApply);
        Files.createDirectories(project.resolve(".ike"));
        Path backup = project.resolve(".ike")
                .resolve("foundation-revert.pom.xml");
        Files.writeString(backup, preApply);

        ScaffoldRevertMojo mojo = new ScaffoldRevertMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        // POM restored to pre-apply content.
        assertThat(Files.readString(project.resolve("pom.xml")))
                .isEqualTo(preApply);
        // Backup deleted (one-shot revert).
        assertThat(backup).doesNotExist();
        assertThat(log.infos).anyMatch(
                s -> s.contains("Foundation: restored"));
    }

    @Test
    void foundationRevert_noBackupPresent_isNoOp(@TempDir Path tmp)
            throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path project = tmp.resolve("proj");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(project);
        Files.createDirectories(userHome);
        Files.writeString(
                scaffold.resolve("scaffold-manifest.yaml"),
                """
                        schema: 1
                        standards-version: "7"
                        files: []
                        """);
        String pom = "<project><artifactId>x</artifactId></project>";
        Files.writeString(project.resolve("pom.xml"), pom);

        ScaffoldRevertMojo mojo = new ScaffoldRevertMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());

        mojo.execute();

        // POM unchanged.
        assertThat(Files.readString(project.resolve("pom.xml")))
                .isEqualTo(pom);
        assertThat(log.infos).noneMatch(
                s -> s.contains("Foundation: restored"));
    }
}
