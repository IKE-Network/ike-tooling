package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
