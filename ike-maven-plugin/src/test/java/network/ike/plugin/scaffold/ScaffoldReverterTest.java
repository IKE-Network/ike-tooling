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

class ScaffoldReverterTest {

    private final Instant fixedNow =
            Instant.parse("2026-04-23T12:00:00Z");
    private final ScaffoldReverter reverter = new ScaffoldReverter(
            Clock.fixed(fixedNow, ZoneOffset.UTC));

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

    private static ManifestEntry trackedBlock(String dest) {
        Map<String, Object> extras = Map.of(
                "block-begin", "# BEGIN ike-managed",
                "block-end", "# END ike-managed");
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED_BLOCK, dest, null, extras);
    }

    private static ManifestEntry modelManaged() {
        return new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "maven-settings-4", Map.of());
    }

    private static ScaffoldManifest manifestOf(ManifestEntry... entries) {
        return new ScaffoldManifest(1, "7", List.of(entries));
    }

    @Test
    void toolOwnedMatchingShaIsDeleted(@TempDir Path project)
            throws IOException {
        byte[] content = bytes("#!/bin/sh\n");
        Path dest = project.resolve("mvnw");
        Files.write(dest, content);
        String sha = Sha256.of(content);
        ScaffoldManifest manifest = manifestOf(toolOwned("mvnw"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("mvnw", LockfileEntry.toolOwned(sha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(dest).doesNotExist();
        assertThat(result.outcomes()).hasSize(1);
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.DELETED);
        assertThat(result.updatedLockfile().files())
                .doesNotContainKey("mvnw");
    }

    @Test
    void toolOwnedDivergedIsSkipped(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve("mvnw");
        Files.write(dest, bytes("user-edited\n"));
        ScaffoldManifest manifest = manifestOf(toolOwned("mvnw"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("mvnw", LockfileEntry.toolOwned(
                        Sha256.of(bytes("#!/bin/sh\n"))));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(dest).exists();
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.SKIPPED);
        assertThat(result.updatedLockfile().files())
                .containsKey("mvnw");
    }

    @Test
    void trackedMatchingShaIsDeleted(@TempDir Path project)
            throws IOException {
        byte[] content = bytes("-T1C\n");
        Path dest = project.resolve(".mvn/maven.config");
        Files.createDirectories(dest.getParent());
        Files.write(dest, content);
        String sha = Sha256.of(content);
        ScaffoldManifest manifest = manifestOf(
                tracked(".mvn/maven.config"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED, sha, sha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(dest).doesNotExist();
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.DELETED);
    }

    @Test
    void trackedEditedIsSkipped(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve(".mvn/maven.config");
        Files.createDirectories(dest.getParent());
        Files.write(dest, bytes("user-edit\n"));
        String priorSha = Sha256.of(bytes("-T1C\n"));
        ScaffoldManifest manifest = manifestOf(
                tracked(".mvn/maven.config"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                priorSha, priorSha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(dest).exists();
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.SKIPPED);
        assertThat(result.outcomes().get(0).message())
                .contains("edited");
    }

    @Test
    void alreadyAbsentDropsLockfileEntry(@TempDir Path project) {
        ScaffoldManifest manifest = manifestOf(toolOwned("mvnw"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("mvnw", LockfileEntry.toolOwned("sha256:aa"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(
                        ScaffoldReverter.Outcome.Kind.REMOVED_FROM_LOCKFILE);
        assertThat(result.updatedLockfile().files())
                .doesNotContainKey("mvnw");
    }

    @Test
    void trackedBlockIsSkippedAsNotImplemented(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve(".gitignore");
        Files.write(dest, bytes("x\n"));
        ScaffoldManifest manifest = manifestOf(
                trackedBlock(".gitignore"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".gitignore",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED_BLOCK,
                                "sha256:aa", "sha256:bb"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.PROJECT, resolver);

        assertThat(dest).exists();
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.SKIPPED);
        assertThat(result.outcomes().get(0).message())
                .contains("tracked-block");
        assertThat(result.updatedLockfile().files())
                .containsKey(".gitignore");
    }

    @Test
    void modelManagedIsSkippedAsNotImplemented(@TempDir Path home)
            throws IOException {
        Path dest = home.resolve(".m2/settings.xml");
        Files.createDirectories(dest.getParent());
        Files.write(dest, bytes("<settings/>"));

        ScaffoldManifest manifest = manifestOf(modelManaged());
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("~/.m2/settings.xml",
                        LockfileEntry.modelManaged(List.of()));
        PathResolver resolver = new PathResolver(home, null);

        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.USER, resolver);

        assertThat(dest).exists();
        assertThat(result.outcomes().get(0).kind())
                .isEqualTo(ScaffoldReverter.Outcome.Kind.SKIPPED);
        assertThat(result.outcomes().get(0).message())
                .contains("model-managed");
    }

    @Test
    void entriesNotInLockfileAreIgnored(@TempDir Path project) {
        ScaffoldManifest manifest = manifestOf(toolOwned("mvnw"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                ScaffoldLockfile.empty(), manifest,
                ScaffoldScope.PROJECT, resolver);

        assertThat(result.outcomes()).isEmpty();
        assertThat(result.updatedLockfile().files()).isEmpty();
    }

    @Test
    void outOfScopeEntriesAreLeftInLockfile(@TempDir Path home) {
        ScaffoldManifest manifest = manifestOf(modelManaged());
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("~/.m2/settings.xml",
                        LockfileEntry.modelManaged(List.of()))
                .withEntry("mvnw",
                        LockfileEntry.toolOwned("sha256:aa"));
        PathResolver resolver = new PathResolver(home, null);

        // Revert USER scope — PROJECT "mvnw" must stay.
        ScaffoldReverter.RevertResult result = reverter.revert(
                lockfile, manifest, ScaffoldScope.USER, resolver);

        assertThat(result.updatedLockfile().files())
                .containsKey("mvnw");
    }

    @Test
    void updatedLockfileCarriesFreshTimestamp(@TempDir Path project) {
        ScaffoldManifest manifest = manifestOf(toolOwned("mvnw"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldReverter.RevertResult result = reverter.revert(
                ScaffoldLockfile.empty(), manifest,
                ScaffoldScope.PROJECT, resolver);

        assertThat(result.updatedLockfile().applied())
                .isEqualTo(fixedNow);
        assertThat(result.updatedLockfile().standardsVersion())
                .isEqualTo("7");
    }
}
