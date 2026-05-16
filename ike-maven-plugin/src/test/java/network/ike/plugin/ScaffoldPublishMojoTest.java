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

    // ─── #348 foundation-drift apply ──────────────────────────────

    @Test
    void foundationApply_optInTrue_rewritesParentAndProperty(
            @TempDir Path tmp) throws Exception {
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
                        foundation:
                          parent:
                            groupId: network.ike.platform
                            artifactId: ike-parent
                            version: "36"
                          properties:
                            ike-tooling.version: "152"
                            ike-docs.version: "14"
                        """);
        Files.writeString(project.resolve("pom.xml"),
                """
                        <?xml version="1.0"?>
                        <project>
                            <parent>
                                <groupId>network.ike.platform</groupId>
                                <artifactId>ike-parent</artifactId>
                                <version>35</version>
                            </parent>
                            <artifactId>some-consumer</artifactId>
                            <version>1-SNAPSHOT</version>
                            <properties>
                                <ike-tooling.version>151</ike-tooling.version>
                                <ike-docs.version>13</ike-docs.version>
                            </properties>
                        </project>
                        """);

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        inject(mojo, "applyFoundation", true);

        mojo.execute();

        String updated = Files.readString(project.resolve("pom.xml"));
        assertThat(updated)
                .contains("<version>36</version>")
                .doesNotContain("<version>35</version>")
                .contains("<ike-tooling.version>152</ike-tooling.version>")
                .doesNotContain("<ike-tooling.version>151</ike-tooling.version>")
                .contains("<ike-docs.version>14</ike-docs.version>")
                .doesNotContain("<ike-docs.version>13</ike-docs.version>");
        assertThat(log.infos)
                .anyMatch(s -> s.contains("IKE Foundation Apply"))
                .anyMatch(s -> s.contains("wrote"));
    }

    @Test
    void foundationApply_skipParent_rewritesPropertyNotParent(
            @TempDir Path tmp) throws Exception {
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
                        foundation:
                          parent:
                            groupId: network.ike.platform
                            artifactId: ike-parent
                            version: "36"
                          properties:
                            ike-tooling.version: "152"
                            ike-docs.version: "14"
                        """);
        Files.writeString(project.resolve("pom.xml"),
                """
                        <?xml version="1.0"?>
                        <project>
                            <parent>
                                <groupId>network.ike.platform</groupId>
                                <artifactId>ike-parent</artifactId>
                                <version>35</version>
                            </parent>
                            <artifactId>some-consumer</artifactId>
                            <version>1-SNAPSHOT</version>
                            <properties>
                                <ike-tooling.version>151</ike-tooling.version>
                                <ike-docs.version>13</ike-docs.version>
                            </properties>
                        </project>
                        """);

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        inject(mojo, "applyFoundation", true);
        inject(mojo, "skipParent", true);

        mojo.execute();

        String updated = Files.readString(project.resolve("pom.xml"));
        // Property pins are applied …
        assertThat(updated)
                .contains("<ike-tooling.version>152</ike-tooling.version>")
                .contains("<ike-docs.version>14</ike-docs.version>");
        // … but <parent> is left untouched — the workspace owns it (#418).
        assertThat(updated)
                .contains("<version>35</version>")
                .doesNotContain("<version>36</version>");
        assertThat(log.infos)
                .anyMatch(s -> s.contains("left to the workspace"));
    }

    @Test
    void foundationApply_dryRunByDefault_doesNotMutatePom(
            @TempDir Path tmp) throws Exception {
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
                        foundation:
                          parent:
                            groupId: network.ike.platform
                            artifactId: ike-parent
                            version: "36"
                          properties: {}
                        """);
        String originalPom = """
                <?xml version="1.0"?>
                <project>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>35</version>
                    </parent>
                    <artifactId>some-consumer</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(project.resolve("pom.xml"), originalPom);

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        // applyFoundation defaults to false — leave unset

        mojo.execute();

        String after = Files.readString(project.resolve("pom.xml"));
        assertThat(after).isEqualTo(originalPom);
        assertThat(log.infos)
                .anyMatch(s -> s.contains("IKE Foundation Apply"))
                .anyMatch(s -> s.contains("dry-run"))
                .noneMatch(s -> s.contains("wrote"));
    }

    @Test
    void foundationApply_aligned_logsAlignedAndNoOp(
            @TempDir Path tmp) throws Exception {
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
                        foundation:
                          parent:
                            groupId: network.ike.platform
                            artifactId: ike-parent
                            version: "36"
                          properties:
                            ike-tooling.version: "152"
                        """);
        String pom = """
                <?xml version="1.0"?>
                <project>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>36</version>
                    </parent>
                    <artifactId>some-consumer</artifactId>
                    <version>1-SNAPSHOT</version>
                    <properties>
                        <ike-tooling.version>152</ike-tooling.version>
                    </properties>
                </project>
                """;
        Files.writeString(project.resolve("pom.xml"), pom);

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        inject(mojo, "applyFoundation", true);

        mojo.execute();

        assertThat(Files.readString(project.resolve("pom.xml")))
                .isEqualTo(pom);
        assertThat(log.infos)
                .anyMatch(s -> s.contains("Foundation: aligned"));
    }

    @Test
    void foundationApply_writesBackupForRevert(@TempDir Path tmp)
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
                        foundation:
                          parent:
                            groupId: network.ike.platform
                            artifactId: ike-parent
                            version: "36"
                          properties: {}
                        """);
        String originalPom = """
                <?xml version="1.0"?>
                <project>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>35</version>
                    </parent>
                    <artifactId>some-consumer</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(project.resolve("pom.xml"), originalPom);

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        inject(mojo, "applyFoundation", true);

        mojo.execute();

        // Backup exists at .ike/foundation-revert.pom.xml with the
        // pre-apply content (so scaffold-revert can restore).
        Path backup = project.resolve(".ike")
                .resolve("foundation-revert.pom.xml");
        assertThat(backup).exists();
        assertThat(Files.readString(backup)).isEqualTo(originalPom);
        // POM was actually rewritten.
        assertThat(Files.readString(project.resolve("pom.xml")))
                .contains("<version>36</version>")
                .doesNotContain("<version>35</version>");
        assertThat(log.infos).anyMatch(
                s -> s.contains("backup:"));
    }

    @Test
    void foundationApply_noFoundationSection_skipsEntirely(
            @TempDir Path tmp) throws Exception {
        Path scaffold = tmp.resolve("scaffold");
        Path project = tmp.resolve("proj");
        Path userHome = tmp.resolve("home");
        Files.createDirectories(scaffold);
        Files.createDirectories(project);
        Files.createDirectories(userHome);
        // Manifest has no foundation: section.
        Files.writeString(
                scaffold.resolve("scaffold-manifest.yaml"),
                """
                        schema: 1
                        standards-version: "7"
                        files: []
                        """);
        Files.writeString(project.resolve("pom.xml"),
                "<project><artifactId>x</artifactId></project>");

        ScaffoldPublishMojo mojo = new ScaffoldPublishMojo();
        RecordingLog log = new RecordingLog();
        inject(mojo, "log", log);
        inject(mojo, "scaffoldDir", scaffold.toString());
        inject(mojo, "projectRoot", project.toString());
        inject(mojo, "userHome", userHome.toString());
        inject(mojo, "applyFoundation", true);

        mojo.execute();

        assertThat(log.infos)
                .noneMatch(s -> s.contains("IKE Foundation"));
    }
}
