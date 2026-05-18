package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrphanScannerTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ManifestEntry tracked(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    private static ScaffoldManifest manifestOf(ManifestEntry... entries) {
        return new ScaffoldManifest(1, "186", List.of(entries));
    }

    @Test
    void destInLockButNotManifestWithMatchingShaIsRemove(
            @TempDir Path project) throws IOException {
        byte[] content = bytes("schema-version: \"1.0\"\n");
        Path dest = project.resolve("versions-upgrade-rules.yaml");
        Files.write(dest, content);
        String sha = Sha256.of(content);
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED, sha, sha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.PROJECT,
                resolver);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).dest())
                .isEqualTo("versions-upgrade-rules.yaml");
        assertThat(orphans.get(0).disposition())
                .isEqualTo(OrphanEntry.Disposition.REMOVE);
    }

    @Test
    void editedOrphanIsSkippedNotRemoved(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve("versions-upgrade-rules.yaml");
        Files.write(dest, bytes("user edited this\n"));
        String priorSha = Sha256.of(bytes("original\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                priorSha, priorSha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.PROJECT,
                resolver);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).disposition())
                .isEqualTo(OrphanEntry.Disposition.SKIP_USER_EDITED);
        assertThat(orphans.get(0).reason()).contains("edited");
    }

    @Test
    void orphanWhoseFileIsGoneIsAlreadyAbsent(@TempDir Path project) {
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("versions-upgrade-rules.yaml",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:aa", "sha256:aa"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.PROJECT,
                resolver);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).disposition())
                .isEqualTo(OrphanEntry.Disposition.ALREADY_ABSENT);
    }

    @Test
    void destStillInManifestIsNotAnOrphan(@TempDir Path project)
            throws IOException {
        byte[] content = bytes("-T1C\n");
        Path dest = project.resolve(".mvn/maven.config");
        Files.createDirectories(dest.getParent());
        Files.write(dest, content);
        String sha = Sha256.of(content);
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED, sha, sha));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(tracked(".mvn/maven.config")),
                lockfile, ScaffoldScope.PROJECT, resolver);

        assertThat(orphans).isEmpty();
    }

    @Test
    void trackedBlockOrphanIsSkippedNotDeleted(@TempDir Path project)
            throws IOException {
        Path dest = project.resolve(".gitignore");
        Files.write(dest, bytes("build/\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".gitignore",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED_BLOCK,
                                "sha256:aa", "sha256:bb"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.PROJECT,
                resolver);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).disposition())
                .isEqualTo(OrphanEntry.Disposition.SKIP_USER_EDITED);
        assertThat(orphans.get(0).reason()).contains("tracked-block");
    }

    @Test
    void modelManagedOrphanIsSkippedNotDeleted(@TempDir Path home)
            throws IOException {
        Path dest = home.resolve(".gitconfig");
        Files.write(dest, bytes("[user]\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("~/.gitconfig",
                        LockfileEntry.modelManaged(List.of()));
        PathResolver resolver = new PathResolver(home, null);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.USER, resolver);

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).disposition())
                .isEqualTo(OrphanEntry.Disposition.SKIP_USER_EDITED);
        assertThat(orphans.get(0).reason()).contains("model-managed");
    }

    @Test
    void otherScopeEntriesAreIgnored(@TempDir Path project) {
        // Scanning PROJECT scope must ignore a ~/ user-scope entry.
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry("~/.m2/settings.xml",
                        LockfileEntry.modelManaged(List.of()));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        List<OrphanEntry> orphans = OrphanScanner.scan(
                manifestOf(), lockfile, ScaffoldScope.PROJECT,
                resolver);

        assertThat(orphans).isEmpty();
    }
}
