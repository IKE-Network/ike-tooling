package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScaffoldApplierTest {

    private final Instant fixedNow =
            Instant.parse("2026-04-23T12:00:00Z");
    private final Clock fixedClock =
            Clock.fixed(fixedNow, ZoneOffset.UTC);
    private final ScaffoldApplier applier =
            new ScaffoldApplier(fixedClock);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ManifestEntry toolOwned(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, dest, null, Map.of());
    }

    private static ManifestEntry tracked(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    private static ManifestEntry toolOwnedMode(String dest, String mode) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, dest, null,
                Map.of("mode", mode));
    }

    /** Plan that installs/updates {@code entry} with {@code content}. */
    private static ScaffoldPlan writePlan(
            ManifestEntry entry, Path dest, byte[] content,
            TierAction.Write.Kind kind) {
        String sha = Sha256.of(content);
        TierAction.Write w = new TierAction.Write(
                entry, dest, content, sha, sha, kind,
                kind.name().toLowerCase(java.util.Locale.ROOT));
        return new ScaffoldPlan("7", List.of(
                new PlannedEntry(entry, w, List.of())));
    }

    private static void assumePosix(Path anyDir) {
        assumeTrue(anyDir.getFileSystem()
                        .supportedFileAttributeViews().contains("posix"),
                "POSIX permissions unsupported on this filesystem");
    }

    private static String modeOf(Path p) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(p));
    }

    @Test
    void writeActionsPopulateDiskAndLockfile(@TempDir Path project)
            throws IOException {
        ManifestEntry e = toolOwned("mvnw");
        byte[] tpl = bytes("#!/bin/sh\nmvnw body\n");
        String sha = Sha256.of(tpl);
        TierAction.Write w = new TierAction.Write(
                e, project.resolve("mvnw"), tpl,
                sha, sha,
                TierAction.Write.Kind.INSTALL,
                "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, w, List.of())));

        ScaffoldLockfile updated = applier.apply(
                plan, ScaffoldLockfile.empty());

        assertThat(Files.readString(project.resolve("mvnw")))
                .isEqualTo("#!/bin/sh\nmvnw body\n");
        assertThat(updated.schema())
                .isEqualTo(ScaffoldLockfile.CURRENT_SCHEMA);
        assertThat(updated.standardsVersion()).isEqualTo("7");
        assertThat(updated.applied()).isEqualTo(fixedNow);
        assertThat(updated.files()).containsOnlyKeys("mvnw");
        LockfileEntry le = updated.files().get("mvnw");
        assertThat(le.tier()).isEqualTo(ScaffoldTier.TOOL_OWNED);
        assertThat(le.templateSha()).isEqualTo(sha);
    }

    @Test
    void writeCreatesParentDirectories(@TempDir Path project)
            throws IOException {
        ManifestEntry e = tracked(".mvn/maven.config");
        byte[] tpl = bytes("-T1C\n");
        String sha = Sha256.of(tpl);
        TierAction.Write w = new TierAction.Write(
                e, project.resolve(".mvn/maven.config"), tpl,
                sha, sha,
                TierAction.Write.Kind.INSTALL,
                "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, w, List.of())));

        applier.apply(plan, ScaffoldLockfile.empty());

        assertThat(project.resolve(".mvn")).exists();
        assertThat(Files.readString(
                project.resolve(".mvn/maven.config")))
                .isEqualTo("-T1C\n");
    }

    @Test
    void writeReplacesExistingFile(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve("file.txt");
        Files.writeString(dest, "old content");

        ManifestEntry e = new ManifestEntry(
                "file.txt", ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, "file.txt", null, Map.of());
        byte[] newBytes = bytes("new content");
        String sha = Sha256.of(newBytes);
        TierAction.Write w = new TierAction.Write(
                e, dest, newBytes, sha, sha,
                TierAction.Write.Kind.UPDATE,
                "refresh");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, w, List.of())));

        applier.apply(plan, ScaffoldLockfile.empty());

        assertThat(Files.readString(dest)).isEqualTo("new content");
    }

    @Test
    void skipLeavesLockfileEntryUnchanged(@TempDir Path project) {
        ManifestEntry e = tracked(".mvn/maven.config");
        TierAction.Skip skip = new TierAction.Skip(
                e, project.resolve(".mvn/maven.config"),
                "user-edited", "--- a\n+++ b\n");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, skip, List.of())));

        LockfileEntry prior = LockfileEntry.tracked(
                ScaffoldTier.TRACKED, "sha256:aa", "sha256:aa");
        ScaffoldLockfile initial = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config", prior);

        ScaffoldLockfile updated = applier.apply(plan, initial);

        assertThat(updated.files()).containsEntry(
                ".mvn/maven.config", prior);
    }

    @Test
    void upToDateRefreshesTemplateSha(@TempDir Path project) {
        ManifestEntry e = tracked(".mvn/maven.config");
        TierAction.UpToDate u = new TierAction.UpToDate(
                e, project.resolve(".mvn/maven.config"),
                "sha256:new", "sha256:new", "up-to-date");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, u, List.of())));

        LockfileEntry prior = LockfileEntry.tracked(
                ScaffoldTier.TRACKED, "sha256:old", "sha256:old");
        ScaffoldLockfile initial = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config", prior);

        ScaffoldLockfile updated = applier.apply(plan, initial);
        LockfileEntry le = updated.files().get(".mvn/maven.config");
        assertThat(le.templateSha()).isEqualTo("sha256:new");
        assertThat(le.appliedSha()).isEqualTo("sha256:new");
    }

    @Test
    void userManagedRefreshesLockfileWithoutWriting(
            @TempDir Path home) {
        ManifestEntry e = new ManifestEntry(
                "~/.gitconfig", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "git-config", java.util.Map.of());
        Path dest = home.resolve(".gitconfig");
        // Precondition: file exists, stays exactly as-is after apply.
        try {
            Files.writeString(dest,
                    "[core]\n\thooksPath = /custom/hooks\n");
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        String sha = Sha256.of("any".getBytes());
        TierAction.UserManaged m = new TierAction.UserManaged(
                e, dest, sha, sha,
                "deferred to user value for [core].hooksPath");
        ManagedElement managed = new ManagedElement(
                "[core].hooksPath", fixedNow, "7");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, m, List.of(managed))));

        ScaffoldLockfile updated = applier.apply(
                plan, ScaffoldLockfile.empty());

        try {
            assertThat(Files.readString(dest))
                    .isEqualTo(
                            "[core]\n\thooksPath = /custom/hooks\n");
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        LockfileEntry le = updated.files().get("~/.gitconfig");
        assertThat(le.tier()).isEqualTo(ScaffoldTier.MODEL_MANAGED);
        assertThat(le.managedElements()).containsExactly(managed);
    }

    @Test
    void modelManagedWriteStoresManagedElements(@TempDir Path home) {
        ManifestEntry e = new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "maven-settings-4", Map.of());
        byte[] xml = bytes("<settings/>");
        String sha = Sha256.of(xml);
        TierAction.Write w = new TierAction.Write(
                e, home.resolve(".m2/settings.xml"), xml, sha, sha,
                TierAction.Write.Kind.INSTALL, "install");
        ManagedElement managed = new ManagedElement(
                "/settings/pluginGroups/pluginGroup[text()='x']",
                fixedNow, "7");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, w, List.of(managed))));

        ScaffoldLockfile updated = applier.apply(
                plan, ScaffoldLockfile.empty());
        LockfileEntry le = updated.files().get("~/.m2/settings.xml");
        assertThat(le.tier()).isEqualTo(ScaffoldTier.MODEL_MANAGED);
        assertThat(le.templateSha()).isNull();
        assertThat(le.appliedSha()).isNull();
        assertThat(le.managedElements()).containsExactly(managed);
    }

    @Test
    void entriesOutsidePlanScopeArePreserved(@TempDir Path project)
            throws IOException {
        ManifestEntry e = toolOwned("mvnw");
        byte[] tpl = bytes("new-mvnw\n");
        String sha = Sha256.of(tpl);
        TierAction.Write w = new TierAction.Write(
                e, project.resolve("mvnw"), tpl, sha, sha,
                TierAction.Write.Kind.INSTALL, "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, w, List.of())));

        ScaffoldLockfile initial = ScaffoldLockfile.empty()
                .withEntry("~/.m2/settings.xml",
                        LockfileEntry.modelManaged(List.of()));
        ScaffoldLockfile updated = applier.apply(plan, initial);

        assertThat(updated.files())
                .containsKeys("mvnw", "~/.m2/settings.xml");
    }

    @Test
    void removeOrphansDeletesFileAndDropsEntry(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve("versions-upgrade-rules.yaml");
        Files.write(dest, bytes("rules\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:aa", "sha256:aa"));
        OrphanEntry orphan = new OrphanEntry(
                "versions-upgrade-rules.yaml", dest,
                ScaffoldTier.TRACKED,
                OrphanEntry.Disposition.REMOVE, "will be deleted");

        ScaffoldLockfile result = applier.removeOrphans(
                List.of(orphan), lockfile);

        assertThat(dest).doesNotExist();
        assertThat(result.files())
                .doesNotContainKey("versions-upgrade-rules.yaml");
    }

    @Test
    void removeOrphansKeepsUserEditedFileAndEntry(
            @TempDir Path project) throws IOException {
        Path dest = project.resolve("versions-upgrade-rules.yaml");
        Files.write(dest, bytes("edited\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:aa", "sha256:aa"));
        OrphanEntry orphan = new OrphanEntry(
                "versions-upgrade-rules.yaml", dest,
                ScaffoldTier.TRACKED,
                OrphanEntry.Disposition.SKIP_USER_EDITED,
                "edited since publish");

        ScaffoldLockfile result = applier.removeOrphans(
                List.of(orphan), lockfile);

        assertThat(dest).exists();
        assertThat(result.files())
                .containsKey("versions-upgrade-rules.yaml");
    }

    @Test
    void removeOrphansDropsAlreadyAbsentEntry(@TempDir Path project) {
        Path dest = project.resolve("versions-upgrade-rules.yaml");
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:aa", "sha256:aa"));
        OrphanEntry orphan = new OrphanEntry(
                "versions-upgrade-rules.yaml", dest,
                ScaffoldTier.TRACKED,
                OrphanEntry.Disposition.ALREADY_ABSENT,
                "file already absent");

        ScaffoldLockfile result = applier.removeOrphans(
                List.of(orphan), lockfile);

        assertThat(result.files())
                .doesNotContainKey("versions-upgrade-rules.yaml");
    }

    @Test
    void executableModeInstallsAs0755(@TempDir Path project)
            throws IOException {
        assumePosix(project);
        ManifestEntry e = toolOwnedMode("mvnw", "executable");
        Path dest = project.resolve("mvnw");

        applier.apply(
                writePlan(e, dest, bytes("#!/bin/sh\n"),
                        TierAction.Write.Kind.INSTALL),
                ScaffoldLockfile.empty());

        assertThat(modeOf(dest)).isEqualTo("rwxr-xr-x");
        assertThat(Files.isExecutable(dest)).isTrue();
    }

    @Test
    void privateModeInstallsAs0600(@TempDir Path home) throws IOException {
        assumePosix(home);
        ManifestEntry e = toolOwnedMode(
                "~/.git-hooks/pre-commit", "private");
        Path dest = home.resolve(".git-hooks/pre-commit");

        applier.apply(
                writePlan(e, dest, bytes("#!/bin/sh\nhook\n"),
                        TierAction.Write.Kind.INSTALL),
                ScaffoldLockfile.empty());

        assertThat(modeOf(dest)).isEqualTo("rw-------");
        assertThat(Files.isExecutable(dest)).isFalse();
    }

    @Test
    void defaultModeInstallsAs0644(@TempDir Path project)
            throws IOException {
        assumePosix(project);
        ManifestEntry e = toolOwned("mvnw.cmd");
        Path dest = project.resolve("mvnw.cmd");

        applier.apply(
                writePlan(e, dest, bytes("@echo off\n"),
                        TierAction.Write.Kind.INSTALL),
                ScaffoldLockfile.empty());

        assertThat(modeOf(dest)).isEqualTo("rw-r--r--");
    }

    @Test
    void defaultModeUpdatePreservesExistingPerms(@TempDir Path home)
            throws IOException {
        assumePosix(home);
        // A user's locked-down settings.xml (0600 with secrets) must
        // not be loosened to 0644 by a default-mode refresh.
        Path dest = home.resolve(".m2/settings.xml");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "<settings/>");
        Files.setPosixFilePermissions(dest,
                PosixFilePermissions.fromString("rw-------"));

        ManifestEntry e = toolOwned("~/.m2/settings.xml");
        applier.apply(
                writePlan(e, dest, bytes("<settings>new</settings>"),
                        TierAction.Write.Kind.UPDATE),
                ScaffoldLockfile.empty());

        assertThat(Files.readString(dest))
                .isEqualTo("<settings>new</settings>");
        assertThat(modeOf(dest)).isEqualTo("rw-------");
    }

    @Test
    void explicitModeIsEnforcedOnUpdate(@TempDir Path project)
            throws IOException {
        assumePosix(project);
        // A drifted, non-executable mvnw already on disk must be
        // brought to 0755 when the tool-owned entry is refreshed.
        Path dest = project.resolve("mvnw");
        Files.writeString(dest, "old\n");
        Files.setPosixFilePermissions(dest,
                PosixFilePermissions.fromString("rw-r--r--"));

        ManifestEntry e = toolOwnedMode("mvnw", "executable");
        applier.apply(
                writePlan(e, dest, bytes("#!/bin/sh\nnew\n"),
                        TierAction.Write.Kind.UPDATE),
                ScaffoldLockfile.empty());

        assertThat(modeOf(dest)).isEqualTo("rwxr-xr-x");
        assertThat(Files.isExecutable(dest)).isTrue();
    }

    @Test
    void emptyPlanStillUpdatesStampsAndPreservesEntries() {
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of());
        LockfileEntry prior = LockfileEntry.toolOwned("sha256:aa");
        ScaffoldLockfile initial = ScaffoldLockfile.empty()
                .withEntry("mvnw", prior);

        ScaffoldLockfile updated = applier.apply(plan, initial);
        assertThat(updated.standardsVersion()).isEqualTo("7");
        assertThat(updated.applied()).isEqualTo(fixedNow);
        assertThat(updated.files()).containsEntry("mvnw", prior);
    }
}
