package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for pure functions in {@link ReleaseSupport}:
 * version derivation, path validation, site path generation,
 * branch-to-path conversion, POM reading, and POM writing.
 */
class ReleaseSupportTest {

    // ── deriveReleaseVersion ─────────────────────────────────────────

    @Test
    void deriveReleaseVersion_stripsSnapshot() {
        assertThat(ReleaseSupport.deriveReleaseVersion("2-SNAPSHOT"))
                .isEqualTo("2");
    }

    @Test
    void deriveReleaseVersion_dotted() {
        assertThat(ReleaseSupport.deriveReleaseVersion("1.1.0-SNAPSHOT"))
                .isEqualTo("1.1.0");
    }

    @Test
    void deriveReleaseVersion_noSnapshot_unchanged() {
        assertThat(ReleaseSupport.deriveReleaseVersion("3.0.0"))
                .isEqualTo("3.0.0");
    }

    // ── deriveNextSnapshot ───────────────────────────────────────────

    @Test
    void deriveNextSnapshot_simpleInteger() {
        assertThat(ReleaseSupport.deriveNextSnapshot("2"))
                .isEqualTo("3-SNAPSHOT");
    }

    @Test
    void deriveNextSnapshot_dotted() {
        assertThat(ReleaseSupport.deriveNextSnapshot("1.1.0"))
                .isEqualTo("1.1.1-SNAPSHOT");
    }

    @Test
    void deriveNextSnapshot_alreadySnapshot_stillWorks() {
        assertThat(ReleaseSupport.deriveNextSnapshot("1.0.0-SNAPSHOT"))
                .isEqualTo("1.0.1-SNAPSHOT");
    }

    // ── validateRemotePath ───────────────────────────────────────────

    @Test
    void validateRemotePath_validPath_noException() throws MojoException {
        // Should not throw — path has base + project + type
        ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline/snapshot");
    }

    @Test
    void validateRemotePath_deepPath_noException() throws MojoException {
        ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline/snapshot/main");
    }

    @Test
    void validateRemotePath_wrongBase_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/tmp/evil/path"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not start with");
    }

    @Test
    void validateRemotePath_tooShallow_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("too shallow");
    }

    @Test
    void validateRemotePath_justBase_throws() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/"))
                .isInstanceOf(MojoException.class);
    }

    // ── siteDiskPath ─────────────────────────────────────────────────

    @Test
    void siteDiskPath_release() {
        assertThat(ReleaseSupport.siteDiskPath("ike-pipeline", "release", null))
                .isEqualTo("/srv/ike-site/ike-pipeline/release");
    }

    @Test
    void siteDiskPath_snapshotWithBranch() {
        assertThat(ReleaseSupport.siteDiskPath("ike-pipeline", "snapshot", "main"))
                .isEqualTo("/srv/ike-site/ike-pipeline/snapshot/main");
    }

    @Test
    void siteDiskPath_checkpoint() {
        assertThat(ReleaseSupport.siteDiskPath("ike-docs", "checkpoint", "v1.0"))
                .isEqualTo("/srv/ike-site/ike-docs/checkpoint/v1.0");
    }

    @Test
    void siteDiskPath_blankSubPath_noTrailingSlash() {
        assertThat(ReleaseSupport.siteDiskPath("proj", "release", ""))
                .isEqualTo("/srv/ike-site/proj/release");
    }

    // ── branchToSitePath ─────────────────────────────────────────────

    @Test
    void branchToSitePath_main_unchanged() {
        assertThat(ReleaseSupport.branchToSitePath("main"))
                .isEqualTo("main");
    }

    @Test
    void branchToSitePath_featureBranch_preservesSlash() {
        assertThat(ReleaseSupport.branchToSitePath("feature/my-work"))
                .isEqualTo("feature/my-work");
    }

    @Test
    void branchToSitePath_unsafeChars_replaced() {
        assertThat(ReleaseSupport.branchToSitePath("feature/weird@chars!"))
                .isEqualTo("feature/weird-chars-");
    }

    // ── siteStagingPath ──────────────────────────────────────────────

    @Test
    void siteStagingPath_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingPath("/srv/ike-site/proj/release"))
                .isEqualTo("/srv/ike-site/proj/release.staging");
    }

    // ── siteStagingUrl ──────────────────────────────────────────────

    @Test
    void siteStagingUrl_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingUrl("scpexe://proxy/srv/ike-site/proj/release"))
                .isEqualTo("scpexe://proxy/srv/ike-site/proj/release.staging");
    }

    // ── readPomVersion (file-based) ─────────────────────────────────

    @Test
    void readPomVersion_simpleProject(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>3.1.0-SNAPSHOT</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("3.1.0-SNAPSHOT");
    }

    @Test
    void readPomVersion_skipsParentVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("1.0.0");
    }

    @Test
    void readPomVersion_noVersion_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>orphan</artifactId>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    @Test
    void readPomVersion_integerVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-pipeline</artifactId>
                    <version>20</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomVersion(pom))
                .isEqualTo("20");
    }

    // ── readPomArtifactId (file-based) ──────────────────────────────

    @Test
    void readPomArtifactId_simpleProject(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-pipeline</artifactId>
                    <version>20-SNAPSHOT</version>
                </project>
                """);

        assertThat(ReleaseSupport.readPomArtifactId(pom))
                .isEqualTo("ike-pipeline");
    }

    @Test
    void readPomArtifactId_skipsParentArtifactId(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-module</artifactId>
                </project>
                """);

        assertThat(ReleaseSupport.readPomArtifactId(pom))
                .isEqualTo("child-module");
    }

    @Test
    void readPomArtifactId_noArtifactId_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <version>1.0</version>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomArtifactId(pom))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Could not extract <artifactId>");
    }

    // ── setPomVersion (file-based) ──────────────────────────────────

    @Test
    void setPomVersion_replacesProjectVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0-SNAPSHOT", "1.0.0");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>1.0.0</version>")
                .doesNotContain("SNAPSHOT");
    }

    @Test
    void setPomVersion_skipsParentVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0-SNAPSHOT", "1.0.0");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>20-SNAPSHOT</version>")  // parent unchanged
                .contains("<version>1.0.0</version>");       // project updated
    }

    @Test
    void setPomVersion_versionNotFound_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        assertThatThrownBy(() ->
                ReleaseSupport.setPomVersion(pom, "999.0.0", "999.0.1"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not contain");
    }

    // ── validateRemotePath (additional edge cases) ──────────────────

    @Test
    void validateRemotePath_projectOnly_noSlash_valid() throws MojoException {
        // "ike-pipeline" has no slash but is not blank — depth=0 which is < 1
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/ike-pipeline"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("too shallow");
    }

    // ── findPomFiles (file-based) ──────────────────────────────────

    @Test
    void findPomFiles_findsRootPom(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getName()).isEqualTo("pom.xml");
    }

    @Test
    void findPomFiles_findsNestedPoms(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve("sub-a"));
        writePom(tmpDir.resolve("sub-a"), "<project/>");
        Files.createDirectories(tmpDir.resolve("sub-b"));
        writePom(tmpDir.resolve("sub-b"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(3);
    }

    @Test
    void findPomFiles_excludesTargetDirectory(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve("target/classes"));
        writePom(tmpDir.resolve("target"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
    }

    @Test
    void findPomFiles_excludesMvnDirectory(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        Files.createDirectories(tmpDir.resolve(".mvn/wrapper"));
        writePom(tmpDir.resolve(".mvn"), "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());

        assertThat(poms).hasSize(1);
    }

    // ── replaceProjectVersionRefs + restoreBackups (file-based) ────

    @Test
    void replaceProjectVersionRefs_replacesExpression(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, """
                <project>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var log = new TestLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains("<version>2.0.0</version>")
                .doesNotContain("${project.version}");
    }

    @Test
    void replaceProjectVersionRefs_createsBackup(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project><version>${project.version}</version></project>");

        var log = new TestLog();
        ReleaseSupport.replaceProjectVersionRefs(tmpDir.toFile(), "3.0", log);

        Path backup = tmpDir.resolve("pom.xml.ike-backup");
        assertThat(backup).exists();
        String backupContent = Files.readString(backup, StandardCharsets.UTF_8);
        assertThat(backupContent).contains("${project.version}");
    }

    @Test
    void replaceProjectVersionRefs_skipsFilesWithoutExpression(@TempDir Path tmpDir)
            throws Exception {
        writePom(tmpDir, "<project><version>1.0.0</version></project>");

        var log = new TestLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).isEmpty();
    }

    @Test
    void restoreBackups_restoresFromBackup(@TempDir Path tmpDir) throws Exception {
        String original = "<project><version>${project.version}</version></project>";
        writePom(tmpDir, original);

        var log = new TestLog();
        ReleaseSupport.replaceProjectVersionRefs(tmpDir.toFile(), "3.0", log);

        // Now restore
        var restored = ReleaseSupport.restoreBackups(tmpDir.toFile(), log);

        assertThat(restored).hasSize(1);
        String content = Files.readString(restored.get(0).toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("${project.version}");

        // Backup should be deleted
        Path backup = tmpDir.resolve("pom.xml.ike-backup");
        assertThat(backup).doesNotExist();
    }

    @Test
    void restoreBackups_noBackups_emptyResult(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");

        var log = new TestLog();
        var restored = ReleaseSupport.restoreBackups(tmpDir.toFile(), log);

        assertThat(restored).isEmpty();
    }

    // ── routeSubprocessLine ─────────────────────────────────────────

    @Test
    void routeSubprocessLine_errorPrefix_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[ERROR] Something went wrong");
        assertThat(log.errors).containsExactly("Something went wrong");
    }

    @Test
    void routeSubprocessLine_warningPrefix_routesToWarn() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[WARNING] Deprecated API");
        assertThat(log.warnings).containsExactly("Deprecated API");
    }

    @Test
    void routeSubprocessLine_infoPrefix_routesToInfo() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[INFO] Building project");
        assertThat(log.infos).containsExactly("Building project");
    }

    @Test
    void routeSubprocessLine_debugPrefix_routesToDebug() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[DEBUG] Classpath entry");
        assertThat(log.debugs).containsExactly("Classpath entry");
    }

    @Test
    void routeSubprocessLine_jvmWarning_routesToWarn() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "WARNING: sun.misc.Unsafe deprecated");
        assertThat(log.warnings).containsExactly("sun.misc.Unsafe deprecated");
    }

    @Test
    void routeSubprocessLine_jvmError_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "ERROR: fatal JVM error");
        assertThat(log.errors).containsExactly("fatal JVM error");
    }

    @Test
    void routeSubprocessLine_plainText_routesToInfo() {
        // ike-issues#329: plain (unprefixed) subprocess output now
        // routes to info so git/curl/scp output is visible in the
        // build log. Earlier behavior was log.debug — hid both
        // successes and unrecognized failures.
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "Just a plain line");
        assertThat(log.infos).containsExactly("Just a plain line");
        assertThat(log.debugs).isEmpty();
    }

    @Test
    void routeSubprocessLine_gitFatal_routesToError() {
        // ike-issues#329: git error patterns must be visible.
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log,
                "fatal: Could not read from remote repository.");
        assertThat(log.errors).containsExactly(
                "fatal: Could not read from remote repository.");
        assertThat(log.infos).isEmpty();
    }

    @Test
    void routeSubprocessLine_gitError_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "error: failed to push some refs");
        assertThat(log.errors).containsExactly(
                "error: failed to push some refs");
    }

    @Test
    void routeSubprocessLine_gitRemoteError_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log,
                "remote: error: GH013: Repository rule violations found");
        assertThat(log.errors).containsExactly(
                "remote: error: GH013: Repository rule violations found");
    }

    @Test
    void routeSubprocessLine_gitRejected_routesToError() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log,
                "! [rejected] main -> main (fetch first)");
        assertThat(log.errors).containsExactly(
                "! [rejected] main -> main (fetch first)");
    }

    @Test
    void routeSubprocessLine_withPrefix_prependsLabel() {
        var log = new CapturingLog();
        ReleaseSupport.routeSubprocessLine(log, "[INFO] Building", "[nexus] ");
        assertThat(log.infos).containsExactly("[nexus] Building");
    }

    // ── exec / execCapture (with real processes) ────────────────────

    @Test
    void exec_successfulCommand_noException(@TempDir Path tmpDir) {
        var log = new TestLog();
        assertThatCode(() ->
                ReleaseSupport.exec(tmpDir.toFile(), log, "echo", "hello"))
                .doesNotThrowAnyException();
    }

    @Test
    void exec_failingCommand_throwsWithExitCode(@TempDir Path tmpDir) {
        var log = new TestLog();
        assertThatThrownBy(() ->
                ReleaseSupport.exec(tmpDir.toFile(), log, "false"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("exit 1");
    }

    @Test
    void execCapture_capturesOutput(@TempDir Path tmpDir) throws Exception {
        String result = ReleaseSupport.execCapture(tmpDir.toFile(),
                "echo", "hello world");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void execCapture_failingCommand_throws(@TempDir Path tmpDir) {
        assertThatThrownBy(() ->
                ReleaseSupport.execCapture(tmpDir.toFile(), "false"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("exit 1");
    }

    // ── execParallel ────────────────────────────────────────────────

    @Test
    void execParallel_twoSuccessfulTasks(@TempDir Path tmpDir) {
        var log = new TestLog();
        assertThatCode(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("a", new String[]{"echo", "alpha"}),
                new ReleaseSupport.LabeledTask("b", new String[]{"echo", "beta"})
        )).doesNotThrowAnyException();
    }

    @Test
    void execParallel_oneFailingTask_throwsWithLabel(@TempDir Path tmpDir) {
        var log = new TestLog();
        assertThatThrownBy(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("good", new String[]{"echo", "ok"}),
                new ReleaseSupport.LabeledTask("bad", new String[]{"false"})
        ))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("bad");
    }

    @Test
    void execParallel_bothFailing_reportsAll(@TempDir Path tmpDir) {
        var log = new TestLog();
        assertThatThrownBy(() -> ReleaseSupport.execParallel(
                tmpDir.toFile(), log,
                new ReleaseSupport.LabeledTask("task1", new String[]{"false"}),
                new ReleaseSupport.LabeledTask("task2", new String[]{"false"})
        ))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("task1")
                .hasMessageContaining("task2");
    }

    // ── gitAddFiles ─────────────────────────────────────────────────

    @Test
    void gitAddFiles_emptyList_noOp(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        var log = new TestLog();

        // Should not throw — empty list is a no-op
        assertThatCode(() ->
                ReleaseSupport.gitAddFiles(tmpDir.toFile(), log, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void gitAddFiles_stagesFiles(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        var log = new TestLog();

        // Create a new file
        Path newFile = tmpDir.resolve("new.txt");
        Files.writeString(newFile, "new content", StandardCharsets.UTF_8);

        ReleaseSupport.gitAddFiles(tmpDir.toFile(), log,
                List.of(newFile.toFile()));

        // Verify the file is staged
        String status = execCapture(tmpDir, "git", "status", "--porcelain");
        assertThat(status).contains("A  new.txt");
    }

    // ── hasRemote ───────────────────────────────────────────────────

    @Test
    void hasRemote_noRemotes_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isFalse();
    }

    @Test
    void hasRemote_originExists_returnsTrue(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "remote", "add", "origin", "https://example.com/repo.git");

        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isTrue();
    }

    @Test
    void hasRemote_differentRemote_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "remote", "add", "upstream", "https://example.com/repo.git");

        assertThat(ReleaseSupport.hasRemote(tmpDir.toFile(), "origin"))
                .isFalse();
    }

    // ── requireCleanWorktree ────────────────────────────────────────

    @Test
    void requireCleanWorktree_clean_noException(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);

        assertThatCode(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .doesNotThrowAnyException();
    }

    @Test
    void requireCleanWorktree_unstagedChanges_throws(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        // Modify the committed file
        Files.writeString(tmpDir.resolve("init.txt"), "modified",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("unstaged");
    }

    @Test
    void requireCleanWorktree_stagedChanges_throws(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Files.writeString(tmpDir.resolve("staged.txt"), "new",
                StandardCharsets.UTF_8);
        exec(tmpDir, "git", "add", "staged.txt");

        assertThatThrownBy(() ->
                ReleaseSupport.requireCleanWorktree(tmpDir.toFile()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("staged");
    }

    // ── currentBranch ───────────────────────────────────────────────

    @Test
    void currentBranch_returnsMainByDefault(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        assertThat(ReleaseSupport.currentBranch(tmpDir.toFile()))
                .isEqualTo("main");
    }

    @Test
    void currentBranch_afterCheckout(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "checkout", "-b", "feature/test");

        assertThat(ReleaseSupport.currentBranch(tmpDir.toFile()))
                .isEqualTo("feature/test");
    }

    // ── gitRoot ─────────────────────────────────────────────────────

    @Test
    void gitRoot_returnsRepoRoot(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Path subDir = tmpDir.resolve("a/b/c");
        Files.createDirectories(subDir);

        File root = ReleaseSupport.gitRoot(subDir.toFile());
        assertThat(root.getCanonicalPath())
                .isEqualTo(tmpDir.toFile().getCanonicalPath());
    }

    @Test
    void gitRoot_notARepo_throwsRemediationMessage(@TempDir Path tmpDir) {
        // No initGitRepo — tmpDir is just a bare directory.
        // ike-issues#357: error must explain the workaround instead
        // of the bare "Command failed (exit 128)" the user saw before.
        assertThatThrownBy(() -> ReleaseSupport.gitRoot(tmpDir.toFile()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining(tmpDir.toFile().getAbsolutePath())
                .hasMessageContaining("_git-init.sh")
                .hasMessageContaining("ike-issues#357");
    }

    // ── tagExists ───────────────────────────────────────────────────

    @Test
    void tagExists_existingTag_returnsTrue(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        exec(tmpDir, "git", "tag", "v1.0.0");

        assertThat(ReleaseSupport.tagExists(tmpDir.toFile(), "v1.0.0"))
                .isTrue();
    }

    @Test
    void tagExists_missingTag_returnsFalse(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);

        assertThat(ReleaseSupport.tagExists(tmpDir.toFile(), "v999"))
                .isFalse();
    }

    // ── deriveCheckpointVersion ─────────────────────────────────────

    @Test
    void deriveCheckpointVersion_usesShortSha(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        String shortSha = execCapture(tmpDir, "git", "rev-parse", "--short", "HEAD");

        String version = ReleaseSupport.deriveCheckpointVersion(
                "2.0.0-SNAPSHOT", tmpDir.toFile());

        assertThat(version)
                .startsWith("2.0.0-checkpoint.")
                .endsWith("." + shortSha);
    }

    @Test
    void deriveCheckpointVersion_stripsSnapshot(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        String shortSha = execCapture(tmpDir, "git", "rev-parse", "--short", "HEAD");

        String version = ReleaseSupport.deriveCheckpointVersion(
                "1.127.2-SNAPSHOT", tmpDir.toFile());

        assertThat(version).startsWith("1.127.2-checkpoint.");
        assertThat(version).endsWith("." + shortSha);
        assertThat(version).doesNotContain("SNAPSHOT");
    }

    @Test
    void deriveCheckpointVersion_deterministicAcrossCalls(@TempDir Path tmpDir)
            throws Exception {
        initGitRepo(tmpDir);

        // Same commit → same version every time (no sequence counter race)
        String v1 = ReleaseSupport.deriveCheckpointVersion("3.0.0-SNAPSHOT", tmpDir.toFile());
        String v2 = ReleaseSupport.deriveCheckpointVersion("3.0.0-SNAPSHOT", tmpDir.toFile());

        assertThat(v1).isEqualTo(v2);
    }

    // ── resolveMavenWrapper ─────────────────────────────────────────

    @Test
    void resolveMavenWrapper_withWrapper(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        Path mvnw = tmpDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho mvnw", StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);

        File result = ReleaseSupport.resolveMavenWrapper(
                tmpDir.toFile(), new TestLog());
        assertThat(result.getAbsolutePath()).isEqualTo(mvnw.toAbsolutePath().toString());
    }

    @Test
    void resolveMavenWrapperFor_unixSelectsMvnw(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        // Both wrappers present — Unix path must select mvnw, NOT mvnw.cmd
        Path mvnw = tmpDir.resolve("mvnw");
        Path mvnwCmd = tmpDir.resolve("mvnw.cmd");
        Files.writeString(mvnw, "#!/bin/sh", StandardCharsets.UTF_8);
        Files.writeString(mvnwCmd, "@echo off", StandardCharsets.UTF_8);

        File result = ReleaseSupport.resolveMavenWrapperFor(
                tmpDir.toFile(), new TestLog(), false);
        assertThat(result.getName()).isEqualTo("mvnw");
        assertThat(result.getAbsolutePath()).isEqualTo(mvnw.toAbsolutePath().toString());
    }

    @Test
    void resolveMavenWrapperFor_windowsSelectsMvnwCmd(@TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        // Both wrappers present — Windows path must select mvnw.cmd, NOT mvnw
        Path mvnw = tmpDir.resolve("mvnw");
        Path mvnwCmd = tmpDir.resolve("mvnw.cmd");
        Files.writeString(mvnw, "#!/bin/sh", StandardCharsets.UTF_8);
        Files.writeString(mvnwCmd, "@echo off", StandardCharsets.UTF_8);

        File result = ReleaseSupport.resolveMavenWrapperFor(
                tmpDir.toFile(), new TestLog(), true);
        assertThat(result.getName()).isEqualTo("mvnw.cmd");
        assertThat(result.getAbsolutePath()).isEqualTo(mvnwCmd.toAbsolutePath().toString());
    }

    @Test
    void resolveMavenWrapperFor_windowsWithOnlyUnixWrapper_fallsThroughToSystem(
            @TempDir Path tmpDir) throws Exception {
        initGitRepo(tmpDir);
        // Only mvnw present — on Windows that's not usable, so we fall through
        // to system lookup (which/where mvn.cmd). Since this test runs on a
        // Unix host, the Windows branch will try `where mvn.cmd`, which is not
        // available on Unix → expect the "neither found" MojoException.
        Path mvnw = tmpDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ReleaseSupport.resolveMavenWrapperFor(
                tmpDir.toFile(), new TestLog(), true))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("mvnw.cmd")
                .hasMessageContaining("mvn.cmd");
    }

    @Test
    void resolveMavenWrapperFor_unixNoWrapper_fallsBackToSystemMvn(
            @TempDir Path tmpDir) throws Exception {
        // Only run when system mvn is on PATH (typical CI/dev environment)
        // Otherwise skip — we exercise the failure path in a separate test.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                hasSystemMvn(), "system 'mvn' not on PATH");
        initGitRepo(tmpDir);
        // No wrapper at all — should fall back to system mvn via `which mvn`.

        CapturingLog log = new CapturingLog();
        File result = ReleaseSupport.resolveMavenWrapperFor(
                tmpDir.toFile(), log, false);

        assertThat(result.exists())
                .as("system mvn returned by `which` must exist on disk").isTrue();
        assertThat(result.getName()).isIn("mvn", "mvn.cmd");
        assertThat(log.infos)
                .anyMatch(m -> m.contains("No Maven wrapper found")
                        && m.contains("using system"));
    }

    // ── firstNonEmptyLine ───────────────────────────────────────────

    @Test
    void firstNonEmptyLine_singleLine() {
        assertThat(ReleaseSupport.firstNonEmptyLine("/usr/local/bin/mvn"))
                .isEqualTo("/usr/local/bin/mvn");
    }

    @Test
    void firstNonEmptyLine_singleLineWithTrailingNewline() {
        assertThat(ReleaseSupport.firstNonEmptyLine("/usr/local/bin/mvn\n"))
                .isEqualTo("/usr/local/bin/mvn");
    }

    @Test
    void firstNonEmptyLine_singleLineWithSurroundingWhitespace() {
        assertThat(ReleaseSupport.firstNonEmptyLine("   /usr/local/bin/mvn   "))
                .isEqualTo("/usr/local/bin/mvn");
    }

    @Test
    void firstNonEmptyLine_windowsWhereMultiLineCRLF() {
        // Windows `where mvn.cmd` returns CRLF-separated lines, e.g.:
        //   C:\Tools\maven\bin\mvn.cmd
        //   C:\ProgramData\chocolatey\bin\mvn.cmd
        // We must take the FIRST entry (earliest on PATH wins).
        String whereOutput =
                "C:\\Tools\\maven\\bin\\mvn.cmd\r\n"
                        + "C:\\ProgramData\\chocolatey\\bin\\mvn.cmd\r\n";
        assertThat(ReleaseSupport.firstNonEmptyLine(whereOutput))
                .isEqualTo("C:\\Tools\\maven\\bin\\mvn.cmd");
    }

    @Test
    void firstNonEmptyLine_multiLineLF() {
        String output = "/usr/local/bin/mvn\n/opt/homebrew/bin/mvn\n";
        assertThat(ReleaseSupport.firstNonEmptyLine(output))
                .isEqualTo("/usr/local/bin/mvn");
    }

    @Test
    void firstNonEmptyLine_skipsLeadingBlankLines() {
        String output = "\n\n   \n/usr/local/bin/mvn\n";
        assertThat(ReleaseSupport.firstNonEmptyLine(output))
                .isEqualTo("/usr/local/bin/mvn");
    }

    @Test
    void firstNonEmptyLine_emptyInput() {
        assertThat(ReleaseSupport.firstNonEmptyLine("")).isEqualTo("");
    }

    @Test
    void firstNonEmptyLine_onlyWhitespace() {
        assertThat(ReleaseSupport.firstNonEmptyLine("   \n  \n  ")).isEqualTo("");
    }

    // ── isWindows ───────────────────────────────────────────────────

    @Test
    void isWindows_matchesOsNameProperty() {
        boolean expected = System.getProperty("os.name", "")
                .toLowerCase().contains("win");
        assertThat(ReleaseSupport.isWindows()).isEqualTo(expected);
    }

    private static boolean hasSystemMvn() {
        try {
            return ReleaseSupport.execCapture(
                    new File(System.getProperty("user.dir")),
                    "which", "mvn").length() > 0;
        } catch (MojoException e) {
            return false;
        }
    }

    // ── readPomVersion: version only in parent ─────────────────────

    @Test
    void readPomVersion_versionOnlyInParent_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>child-no-version</artifactId>
                </project>
                """);

        // After stripping <parent>, no <version> remains → should throw
        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    @Test
    void readPomVersion_minimalValidPom_throws(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);

        assertThatThrownBy(() -> ReleaseSupport.readPomVersion(pom))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Could not extract <version>");
    }

    // ── setPomVersion: edge cases ────────────────────────────────────

    @Test
    void setPomVersion_noParentBlock_replacesFirstVersion(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>simple</artifactId>
                    <version>5.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "5.0.0", "5.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("<version>5.0.1</version>");
        assertThat(content).doesNotContain("<version>5.0.0</version>");
    }

    @Test
    void setPomVersion_versionInComment_notReplaced(@TempDir Path tmpDir) throws Exception {
        // The old version appears in a comment AND as the real version.
        // setPomVersion does text replacement, so the comment version
        // will NOT be replaced (it looks for the exact <version>X</version>
        // tag after the parent block). Document current behavior.
        File pom = writePom(tmpDir, """
                <project>
                    <!-- Current version is 3.0.0 -->
                    <groupId>network.ike</groupId>
                    <artifactId>app</artifactId>
                    <version>3.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "3.0.0", "3.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // The comment text "3.0.0" inside <!-- ... --> should remain
        // because setPomVersion only replaces <version>3.0.0</version>
        assertThat(content).contains("<!-- Current version is 3.0.0 -->");
        assertThat(content).contains("<version>3.0.1</version>");
    }

    @Test
    void setPomVersion_cdataSection_notAffected(@TempDir Path tmpDir) throws Exception {
        File pom = writePom(tmpDir, """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                    <description><![CDATA[Version 1.0.0 notes]]></description>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "1.0.0", "1.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // CDATA contains "1.0.0" but NOT in a <version> tag, so unchanged
        assertThat(content).contains("Version 1.0.0 notes");
        assertThat(content).contains("<version>1.0.1</version>");
    }

    @Test
    void setPomVersion_parentHasSameVersion_onlyProjectVersionChanged(@TempDir Path tmpDir)
            throws Exception {
        // Both parent and project have the same version string.
        // setPomVersion should only change the project version (after parent block).
        File pom = writePom(tmpDir, """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>2.0.0</version>
                </project>
                """);

        ReleaseSupport.setPomVersion(pom, "2.0.0", "2.0.1");

        String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        // Parent version unchanged, project version changed
        assertThat(content)
                .containsOnlyOnce("<version>2.0.1</version>")
                .contains("<version>2.0.0</version>");  // parent still has old version
    }

    // ── findPomFiles: additional edge cases ──────────────────────────

    @Test
    void findPomFiles_noPomFiles_emptyResult(@TempDir Path tmpDir) throws Exception {
        // Directory with no pom.xml files at all
        Files.writeString(tmpDir.resolve("README.txt"), "hello", StandardCharsets.UTF_8);

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).isEmpty();
    }

    @Test
    void findPomFiles_deeplyNestedTarget_excluded(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project/>");
        // Nested target directories should all be excluded
        Path deep = tmpDir.resolve("sub/target/nested/deep");
        Files.createDirectories(deep);
        writePom(deep, "<project/>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).hasSize(1);  // only root pom
    }

    @Test
    void findPomFiles_singlePom_noSubmodules(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, "<project><version>1.0</version></project>");

        var poms = ReleaseSupport.findPomFiles(tmpDir.toFile());
        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getParentFile().toPath()).isEqualTo(tmpDir);
    }

    // ── replaceProjectVersionRefs: edge cases ────────────────────────

    @Test
    void replaceProjectVersionRefs_multipleRefsInOnePom(@TempDir Path tmpDir) throws Exception {
        writePom(tmpDir, """
                <project>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <version>${project.version}</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        var log = new TestLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "2.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        // Both occurrences should be replaced
        assertThat(content).doesNotContain("${project.version}");
        // Count: two version elements should now have literal "2.0.0"
        long count = content.lines()
                .filter(line -> line.contains("<version>2.0.0</version>"))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void replaceProjectVersionRefs_nestedSubmodules(@TempDir Path tmpDir) throws Exception {
        // Root has no ${project.version}, but two submodules do
        writePom(tmpDir, "<project><version>1.0</version></project>");

        Path subA = tmpDir.resolve("sub-a");
        Files.createDirectories(subA);
        writePom(subA, """
                <project>
                    <parent><version>1.0</version></parent>
                    <version>${project.version}</version>
                </project>
                """);

        Path subB = tmpDir.resolve("sub-b");
        Files.createDirectories(subB);
        writePom(subB, """
                <project>
                    <dependencies>
                        <dependency><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        var log = new TestLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "3.0.0", log);

        assertThat(modified).hasSize(2);
    }

    @Test
    void replaceProjectVersionRefs_expressionInXmlComment_stillReplaced(@TempDir Path tmpDir)
            throws Exception {
        // ${project.version} in an XML comment IS replaced (text-level replacement).
        // This documents current behavior.
        writePom(tmpDir, """
                <project>
                    <!-- ref: ${project.version} -->
                    <dependencies>
                        <dependency><version>${project.version}</version></dependency>
                    </dependencies>
                </project>
                """);

        var log = new TestLog();
        var modified = ReleaseSupport.replaceProjectVersionRefs(
                tmpDir.toFile(), "4.0.0", log);

        assertThat(modified).hasSize(1);
        String content = Files.readString(modified.get(0).toPath(), StandardCharsets.UTF_8);
        // Both comment and element occurrences replaced
        assertThat(content).doesNotContain("${project.version}");
    }

    // ── helper ──────────────────────────────────────────────────────

    private static File writePom(Path dir, String content) throws IOException {
        Path pomPath = dir.resolve("pom.xml");
        Files.writeString(pomPath, content, StandardCharsets.UTF_8);
        return pomPath.toFile();
    }

    private void initGitRepo(Path dir) throws Exception {
        Files.writeString(dir.resolve("init.txt"), "init", StandardCharsets.UTF_8);
        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // gh-pages hybrid structure (#332)
    // ════════════════════════════════════════════════════════════════

    @Test
    void isVersionDirName_singleSegmentInteger_returnsTrue() {
        assertThat(ReleaseSupport.isVersionDirName("145")).isTrue();
        assertThat(ReleaseSupport.isVersionDirName("1")).isTrue();
    }

    @Test
    void isVersionDirName_semverLike_returnsTrue() {
        assertThat(ReleaseSupport.isVersionDirName("1.2.3")).isTrue();
        assertThat(ReleaseSupport.isVersionDirName("2.0.0-rc1")).isTrue();
    }

    @Test
    void isVersionDirName_dateBased_returnsTrue() {
        assertThat(ReleaseSupport.isVersionDirName("2026-04-25")).isTrue();
    }

    @Test
    void isVersionDirName_assetDirs_returnFalse() {
        assertThat(ReleaseSupport.isVersionDirName("css")).isFalse();
        assertThat(ReleaseSupport.isVersionDirName("js")).isFalse();
        assertThat(ReleaseSupport.isVersionDirName("images")).isFalse();
        assertThat(ReleaseSupport.isVersionDirName("fonts")).isFalse();
        assertThat(ReleaseSupport.isVersionDirName("webfonts")).isFalse();
    }

    @Test
    void isVersionDirName_latestAlias_returnsFalse() {
        // 'latest' starts with a letter so it's not preserved as a
        // version subdir — the gh-pages publish overwrites it on each
        // release.
        assertThat(ReleaseSupport.isVersionDirName("latest")).isFalse();
    }

    @Test
    void isVersionDirName_emptyOrNull_returnFalse() {
        assertThat(ReleaseSupport.isVersionDirName("")).isFalse();
        assertThat(ReleaseSupport.isVersionDirName(null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // isEmptyDirectory + empty-staging guard (#334)
    // ════════════════════════════════════════════════════════════════

    @Test
    void isEmptyDirectory_emptyDir_returnsTrue() throws Exception {
        Path empty = Files.createTempDirectory("ike-test-empty-");
        try {
            assertThat(ReleaseSupport.isEmptyDirectory(empty)).isTrue();
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    @Test
    void isEmptyDirectory_nonEmptyDir_returnsFalse() throws Exception {
        Path dir = Files.createTempDirectory("ike-test-nonempty-");
        try {
            Files.writeString(dir.resolve("file.txt"), "content");
            assertThat(ReleaseSupport.isEmptyDirectory(dir)).isFalse();
        } finally {
            ReleaseSupport.deleteDirectory(dir);
        }
    }

    @Test
    void isEmptyDirectory_dirWithSubdir_returnsFalse() throws Exception {
        // A directory containing only a subdirectory still counts as
        // non-empty — the entries-listing API doesn't care whether the
        // entry is a file or directory.
        Path dir = Files.createTempDirectory("ike-test-subdir-");
        try {
            Files.createDirectory(dir.resolve("sub"));
            assertThat(ReleaseSupport.isEmptyDirectory(dir)).isFalse();
        } finally {
            ReleaseSupport.deleteDirectory(dir);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // copyDirectoryExcludingTopLevelVersionDirs (#337)
    // ════════════════════════════════════════════════════════════════

    @Test
    void copyDirectoryExcludingTopLevelVersionDirs_skipsTopLevelVersionDirs() throws Exception {
        Path src = Files.createTempDirectory("ike-test-filter-src-");
        Path dst = Files.createTempDirectory("ike-test-filter-dst-");
        try {
            // Top-level version dirs (should be skipped)
            Files.createDirectory(src.resolve("25"));
            Files.writeString(src.resolve("25").resolve("file.txt"), "v25 content");
            Files.createDirectory(src.resolve("28"));
            Files.writeString(src.resolve("28").resolve("file.txt"), "v28 content");

            // Top-level non-version dirs (should be copied)
            Files.createDirectory(src.resolve("css"));
            Files.writeString(src.resolve("css").resolve("site.css"), "/* css */");
            Files.createDirectory(src.resolve("ike-bom"));
            Files.writeString(src.resolve("ike-bom").resolve("index.html"), "<html/>");

            // Top-level files (should be copied)
            Files.writeString(src.resolve("index.html"), "<html/>");
            Files.writeString(src.resolve("bom.json"), "{}");

            ReleaseSupport.copyDirectoryExcludingTopLevelVersionDirs(src, dst);

            // Version dirs filtered
            assertThat(Files.exists(dst.resolve("25"))).isFalse();
            assertThat(Files.exists(dst.resolve("28"))).isFalse();

            // Non-version dirs copied (with contents)
            assertThat(Files.exists(dst.resolve("css").resolve("site.css"))).isTrue();
            assertThat(Files.exists(dst.resolve("ike-bom").resolve("index.html"))).isTrue();

            // Files copied
            assertThat(Files.exists(dst.resolve("index.html"))).isTrue();
            assertThat(Files.exists(dst.resolve("bom.json"))).isTrue();
        } finally {
            ReleaseSupport.deleteDirectory(src);
            ReleaseSupport.deleteDirectory(dst);
        }
    }

    @Test
    void copyDirectoryExcludingTopLevelVersionDirs_doesNotFilterAtDepth() throws Exception {
        // Version-named subdirs at depth >0 are legitimate content
        // (e.g., a docs section listing release notes per version);
        // the filter applies only at the top level.
        Path src = Files.createTempDirectory("ike-test-filter-depth-src-");
        Path dst = Files.createTempDirectory("ike-test-filter-depth-dst-");
        try {
            Files.createDirectories(src.resolve("docs").resolve("changelog").resolve("1.2.3"));
            Files.writeString(src.resolve("docs").resolve("changelog").resolve("1.2.3").resolve("notes.html"),
                    "<html/>");

            ReleaseSupport.copyDirectoryExcludingTopLevelVersionDirs(src, dst);

            assertThat(Files.exists(dst.resolve("docs").resolve("changelog").resolve("1.2.3").resolve("notes.html")))
                    .isTrue();
        } finally {
            ReleaseSupport.deleteDirectory(src);
            ReleaseSupport.deleteDirectory(dst);
        }
    }

    @Test
    void copyDirectoryExcludingTopLevelVersionDirs_preservesNonVersionDirAtTopLevel() throws Exception {
        // Regression for the v30 ike-platform recovery: when
        // staging has a mix of version dirs and (legitimate)
        // non-version dirs at the top level, only the version
        // dirs are filtered.
        Path src = Files.createTempDirectory("ike-test-mix-src-");
        Path dst = Files.createTempDirectory("ike-test-mix-dst-");
        try {
            Files.createDirectory(src.resolve("29"));
            Files.writeString(src.resolve("29").resolve("stale.txt"), "stale");
            Files.createDirectory(src.resolve("apidocs"));
            Files.writeString(src.resolve("apidocs").resolve("index.html"),
                    "<html/>");
            Files.writeString(src.resolve("index.html"), "<html/>");

            ReleaseSupport.copyDirectoryExcludingTopLevelVersionDirs(src, dst);

            assertThat(Files.exists(dst.resolve("29"))).isFalse();
            assertThat(Files.exists(dst.resolve("apidocs").resolve("index.html")))
                    .isTrue();
            assertThat(Files.exists(dst.resolve("index.html"))).isTrue();
        } finally {
            ReleaseSupport.deleteDirectory(src);
            ReleaseSupport.deleteDirectory(dst);
        }
    }

    @Test
    void copyDirectoryExcludingTopLevelVersionDirs_emptySource_succeedsNoOp() throws Exception {
        Path src = Files.createTempDirectory("ike-test-empty-src-");
        Path dst = Files.createTempDirectory("ike-test-empty-dst-");
        try {
            ReleaseSupport.copyDirectoryExcludingTopLevelVersionDirs(src, dst);
            // No exception, dst is empty.
            try (Stream<Path> entries = Files.list(dst)) {
                assertThat(entries.findAny()).isEmpty();
            }
        } finally {
            ReleaseSupport.deleteDirectory(src);
            ReleaseSupport.deleteDirectory(dst);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // detectParentArtifactNesting (#342)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void detectParentArtifactNesting_typicalNesting_returnsInnerPath(@TempDir Path tmpDir)
            throws Exception {
        // Reproduce the v150 ike-example-ws layout:
        //   target/staging/ike-parent/ike-example-ws/index.html
        Path staging = tmpDir.resolve("staging");
        Path parentDir = staging.resolve("ike-parent");
        Path inner = parentDir.resolve("ike-example-ws");
        Files.createDirectories(inner);
        Files.writeString(inner.resolve("index.html"), "<html/>");

        Path result = ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws");

        assertThat(result).isEqualTo(inner);
    }

    @Test
    void detectParentArtifactNesting_noNesting_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Flat staging — content directly under stagingDir, no
        // parent-artifactId wrap.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("index.html"), "<html/>");
        Files.writeString(staging.resolve("bom.json"), "{}");

        assertThat(ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectParentArtifactNesting_multipleTopLevel_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Aggregator with multiple module subdirs — legitimately
        // multi-top-level. Don't unwrap.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("ike-parent").resolve("ike-example-ws"));
        Files.createDirectories(staging.resolve("other-dir"));

        assertThat(ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectParentArtifactNesting_innerDoesNotMatchProjectId_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Single top-level dir but its inner subdir is NOT named
        // projectId — could be coincidental layout, don't unwrap.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("some-wrap").resolve("totally-different"));

        assertThat(ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectParentArtifactNesting_innerEmpty_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Inner projectId dir exists but is empty — nothing to
        // publish from it; don't unwrap.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("ike-parent").resolve("ike-example-ws"));

        assertThat(ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectParentArtifactNesting_singleEntryIsFile_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Single top-level entry but it's a file, not a dir.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.detectParentArtifactNesting(
                staging, "ike-example-ws")).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // detectAggregatedStaging (#351)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void detectAggregatedStaging_workspaceWithSubprojects_returnsWorkspaceSubdir(
            @TempDir Path tmpDir) throws Exception {
        // Reproduce the v153 ike-example-ws case: target/staging/
        // contains ike-example-ws/, doc-example/, example-project/,
        // ike-example-its/ all as siblings. The workspace's own
        // content is in ike-example-ws/ and should win.
        Path staging = tmpDir.resolve("staging");
        Path wsDir = staging.resolve("ike-example-ws");
        Files.createDirectories(wsDir);
        Files.writeString(wsDir.resolve("index.html"), "<html/>");
        Files.createDirectories(staging.resolve("doc-example"));
        Files.writeString(staging.resolve("doc-example")
                .resolve("index.html"), "<html/>");
        Files.createDirectories(staging.resolve("example-project"));
        Files.writeString(staging.resolve("example-project")
                .resolve("index.html"), "<html/>");

        Path result = ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws");

        assertThat(result).isEqualTo(wsDir);
    }

    @Test
    void detectAggregatedStaging_singleTopLevel_returnsNull(@TempDir Path tmpDir)
            throws Exception {
        // Single top-level entry — detectParentArtifactNesting's
        // territory. detectAggregatedStaging must not fire here.
        Path staging = tmpDir.resolve("staging");
        Path wsDir = staging.resolve("ike-example-ws");
        Files.createDirectories(wsDir);
        Files.writeString(wsDir.resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectAggregatedStaging_multipleTopLevelButProjectIdAbsent_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // Several top-level subdirs but none matches projectId —
        // no candidate to unwrap to.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("doc-example"));
        Files.createDirectories(staging.resolve("example-project"));
        Files.writeString(staging.resolve("doc-example")
                .resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectAggregatedStaging_projectIdSubdirEmpty_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // projectId subdir exists alongside siblings but is empty —
        // nothing to publish.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("ike-example-ws"));
        Files.createDirectories(staging.resolve("doc-example"));
        Files.writeString(staging.resolve("doc-example")
                .resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws")).isNull();
    }

    @Test
    void detectAggregatedStaging_compoundNestingAndAggregation_returnsDeepProjectIdSubdir(
            @TempDir Path tmpDir) throws Exception {
        // Reproduce the v154 ike-example-ws case: target/staging/ has
        // sibling subdirs from aggregated reactor modules AND the
        // workspace's own content is nested under <parentArtifactId>/
        // (because the workspace inherits ike-parent). Combined #342
        // + #351 pattern that v1 of detectAggregatedStaging missed.
        Path staging = tmpDir.resolve("staging");
        // Workspace's own content, nested under parent-artifactId.
        Path wsDir = staging.resolve("ike-parent")
                .resolve("ike-example-ws");
        Files.createDirectories(wsDir);
        Files.writeString(wsDir.resolve("index.html"), "<html/>");
        Files.writeString(wsDir.resolve("workspace-yaml.html"), "<html/>");
        Files.writeString(wsDir.resolve("it-suite.html"), "<html/>");
        // Sibling top-level entries from other reactor modules.
        Files.createDirectories(staging.resolve("apidocs"));
        Files.createDirectories(staging.resolve("docs"));
        Files.createDirectories(staging.resolve("jacoco"));
        Files.writeString(staging.resolve("index.html"),
                "<html><title>example-project</title></html>");

        Path result = ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws");

        assertThat(result).isEqualTo(wsDir);
    }

    @Test
    void detectAggregatedStaging_flatStagingWithFiles_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // Single-module project staging — files at root, no subdirs.
        // Should not unwrap.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("index.html"), "<html/>");
        Files.writeString(staging.resolve("bom.json"), "{}");

        assertThat(ReleaseSupport.detectAggregatedStaging(
                staging, "ike-example-ws")).isNull();
    }

    // ── detectHttpsUrlStaging (ike-issues#359) ──────────────────────

    @Test
    void detectHttpsUrlStaging_ikeToolingShape_returnsProjectDir(
            @TempDir Path tmpDir) throws Exception {
        // ike-tooling: <site><url>https://ike.network/ike-tooling/
        // → staging/https:/ike.network/ike-tooling/<content>
        Path staging = tmpDir.resolve("staging");
        Path projectDir = staging.resolve("https:")
                .resolve("ike.network").resolve("ike-tooling");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("index.html"), "<html/>");

        Path result = ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-tooling");
        assertThat(result).isEqualTo(projectDir);
    }

    @Test
    void detectHttpsUrlStaging_ikePlatformShape_returnsProjectDir(
            @TempDir Path tmpDir) throws Exception {
        // ike-platform: <site><url>https://ike.network/ike-platform/<version>/
        // → staging/https:/ike.network/ike-platform/<version>/<content>.
        // The unwrap returns the projectId-level dir; the version-
        // nested unwrap (#337) then descends one more level.
        Path staging = tmpDir.resolve("staging");
        Path versioned = staging.resolve("https:")
                .resolve("ike.network").resolve("ike-platform")
                .resolve("40");
        Files.createDirectories(versioned);
        Files.writeString(versioned.resolve("index.html"), "<html/>");

        Path result = ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-platform");
        assertThat(result).isEqualTo(staging.resolve("https:")
                .resolve("ike.network").resolve("ike-platform"));
    }

    @Test
    void detectHttpsUrlStaging_ikeDocsShape_returnsProjectDirIgnoringSiblings(
            @TempDir Path tmpDir) throws Exception {
        // ike-docs: workspace's own staged content lives under
        // staging/https:/ike.network/ike-docs/; reactor siblings
        // (ike-doc-resources, minimal-fonts, etc.) live as
        // siblings to https: directly under staging/. The unwrap
        // must reach into the workspace's own subtree.
        Path staging = tmpDir.resolve("staging");
        Path projectDir = staging.resolve("https:")
                .resolve("ike.network").resolve("ike-docs");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("index.html"), "<html/>");
        // Sibling reactor modules at staging root
        Files.createDirectories(staging.resolve("ike-doc-resources"));
        Files.createDirectories(staging.resolve("minimal-fonts"));

        Path result = ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-docs");
        assertThat(result).isEqualTo(projectDir);
    }

    @Test
    void detectHttpsUrlStaging_noHttpsDir_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // Single-module project or scpexe URL staging — no https:
        // dir present. Conservative null so downstream unwraps run.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-tooling")).isNull();
    }

    @Test
    void detectHttpsUrlStaging_multipleHosts_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // Unusual configuration — multiple hosts under https:.
        // Stay conservative; let downstream handle it.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("https:")
                .resolve("ike.network").resolve("ike-tooling")
                .resolve("dummy"));
        Files.createDirectories(staging.resolve("https:")
                .resolve("example.com").resolve("ike-tooling")
                .resolve("dummy"));

        assertThat(ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-tooling")).isNull();
    }

    @Test
    void detectHttpsUrlStaging_projectDirEmpty_returnsNull(
            @TempDir Path tmpDir) throws Exception {
        // Defensive: if the project's own subdir under https:/<host>/
        // exists but is empty, the publish path has nothing useful
        // to copy. Don't return an empty source.
        Path staging = tmpDir.resolve("staging");
        Files.createDirectories(staging.resolve("https:")
                .resolve("ike.network").resolve("ike-tooling"));

        assertThat(ReleaseSupport.detectHttpsUrlStaging(
                staging, "ike-tooling")).isNull();
    }

    // ── findSubmoduleSiteDirs (ike-issues#363) ──────────────────────

    @Test
    void findSubmoduleSiteDirs_findsRenderedSubdirs_excludesWorkspaceOwn(
            @TempDir Path tmpDir) throws Exception {
        // ike-platform shape: under <projectId>/ at the URL-unwrap
        // layer, we have <version>/ (workspace own) plus sibling
        // submodule dirs each with their own index.html.
        Path layer = tmpDir.resolve("layer");
        Path versioned = layer.resolve("41");
        Files.createDirectories(versioned);
        Files.writeString(versioned.resolve("index.html"), "<html/>");
        Path sub1 = layer.resolve("ike-workspace-maven-plugin");
        Files.createDirectories(sub1);
        Files.writeString(sub1.resolve("index.html"), "<html/>");
        Path sub2 = layer.resolve("ike-bom");
        Files.createDirectories(sub2);
        Files.writeString(sub2.resolve("index.html"), "<html/>");
        // Resource dir without index.html
        Files.createDirectories(layer.resolve("css"));

        List<Path> result = ReleaseSupport.findSubmoduleSiteDirs(
                layer, versioned);

        assertThat(result)
                .containsExactlyInAnyOrder(sub1, sub2);
    }

    @Test
    void findSubmoduleSiteDirs_layerNotADir_returnsEmpty(
            @TempDir Path tmpDir) {
        Path missing = tmpDir.resolve("does-not-exist");
        assertThat(ReleaseSupport.findSubmoduleSiteDirs(missing, null))
                .isEmpty();
    }

    @Test
    void findSubmoduleSiteDirs_noIndexHtml_skipped(
            @TempDir Path tmpDir) throws Exception {
        // A subdir with no index.html is not a module site (it's
        // typically a resource bundle from the parent's site).
        Path layer = tmpDir.resolve("layer");
        Path noIndex = layer.resolve("fonts");
        Files.createDirectories(noIndex);
        Files.writeString(noIndex.resolve("roboto.ttf"), "fake");

        assertThat(ReleaseSupport.findSubmoduleSiteDirs(layer, null))
                .isEmpty();
    }

    @Test
    void findSubmoduleSiteDirs_nullExclude_includesAll(
            @TempDir Path tmpDir) throws Exception {
        Path layer = tmpDir.resolve("layer");
        Path m1 = layer.resolve("mod1");
        Path m2 = layer.resolve("mod2");
        Files.createDirectories(m1);
        Files.createDirectories(m2);
        Files.writeString(m1.resolve("index.html"), "<html/>");
        Files.writeString(m2.resolve("index.html"), "<html/>");

        assertThat(ReleaseSupport.findSubmoduleSiteDirs(layer, null))
                .containsExactlyInAnyOrder(m1, m2);
    }

    @Test
    void publishProjectSiteToGhPages_emptyStagingDir_throwsLoud() throws Exception {
        // The bug pattern from #334: an empty-but-existing target/staging/
        // directory used to silently produce a .nojekyll-only gh-pages
        // tree. Now it fails fast with a message that points at the
        // single-module / target/site/ fallback.
        Path emptyStaging = Files.createTempDirectory("ike-test-empty-staging-");
        try {
            assertThatThrownBy(() ->
                    ReleaseSupport.publishProjectSiteToGhPages(
                            emptyStaging,
                            "git@github.com:fake/fake.git",
                            new TestLog(),
                            "fake-project",
                            "1"))
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("Staging directory is empty")
                    .hasMessageContaining("target/site/");
        } finally {
            Files.deleteIfExists(emptyStaging);
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }

    /**
     * Simple log implementation that captures messages by level.
     */
    private static class CapturingLog implements Log {
        final List<String> debugs = new ArrayList<>();
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public boolean isDebugEnabled() { return true; }
        @Override public void debug(CharSequence content) { debugs.add(content.toString()); }
        @Override public void debug(CharSequence content, Throwable error) { debugs.add(content.toString()); }
        @Override public void debug(Throwable error) { debugs.add(error.getMessage()); }
        @Override public void debug(Supplier<String> content) { debugs.add(content.get()); }
        @Override public void debug(Supplier<String> content, Throwable error) { debugs.add(content.get()); }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence content) { infos.add(content.toString()); }
        @Override public void info(CharSequence content, Throwable error) { infos.add(content.toString()); }
        @Override public void info(Throwable error) { infos.add(error.getMessage()); }
        @Override public void info(Supplier<String> content) { infos.add(content.get()); }
        @Override public void info(Supplier<String> content, Throwable error) { infos.add(content.get()); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence content) { warnings.add(content.toString()); }
        @Override public void warn(CharSequence content, Throwable error) { warnings.add(content.toString()); }
        @Override public void warn(Throwable error) { warnings.add(error.getMessage()); }
        @Override public void warn(Supplier<String> content) { warnings.add(content.get()); }
        @Override public void warn(Supplier<String> content, Throwable error) { warnings.add(content.get()); }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence content) { errors.add(content.toString()); }
        @Override public void error(CharSequence content, Throwable error) { errors.add(content.toString()); }
        @Override public void error(Throwable error) { errors.add(error.getMessage()); }
        @Override public void error(Supplier<String> content) { errors.add(content.get()); }
        @Override public void error(Supplier<String> content, Throwable error) { errors.add(content.get()); }
    }

    /** Simple Log that prints to System.out/err, replacing Maven 3's SystemStreamLog. */
    private static class TestLog implements Log {
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence c) {}
        @Override public void debug(CharSequence c, Throwable e) {}
        @Override public void debug(Throwable e) {}
        @Override public void debug(Supplier<String> c) {}
        @Override public void debug(Supplier<String> c, Throwable e) {}
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence c) { System.out.println("[INFO] " + c); }
        @Override public void info(CharSequence c, Throwable e) { System.out.println("[INFO] " + c); }
        @Override public void info(Throwable e) { System.out.println("[INFO] " + e); }
        @Override public void info(Supplier<String> c) { System.out.println("[INFO] " + c.get()); }
        @Override public void info(Supplier<String> c, Throwable e) { System.out.println("[INFO] " + c.get()); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence c) { System.err.println("[WARN] " + c); }
        @Override public void warn(CharSequence c, Throwable e) { System.err.println("[WARN] " + c); }
        @Override public void warn(Throwable e) { System.err.println("[WARN] " + e); }
        @Override public void warn(Supplier<String> c) { System.err.println("[WARN] " + c.get()); }
        @Override public void warn(Supplier<String> c, Throwable e) { System.err.println("[WARN] " + c.get()); }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence c) { System.err.println("[ERROR] " + c); }
        @Override public void error(CharSequence c, Throwable e) { System.err.println("[ERROR] " + c); }
        @Override public void error(Throwable e) { System.err.println("[ERROR] " + e); }
        @Override public void error(Supplier<String> c) { System.err.println("[ERROR] " + c.get()); }
        @Override public void error(Supplier<String> c, Throwable e) { System.err.println("[ERROR] " + c.get()); }
    }
}
