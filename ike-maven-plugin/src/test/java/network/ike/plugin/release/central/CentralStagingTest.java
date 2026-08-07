package network.ike.plugin.release.central;

import network.ike.plugin.TestLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CentralStaging} — the shared Central staging
 * finalization both deploy paths run (IKE-Network/ike-issues#966).
 * The swap/verify halves themselves are covered by
 * {@link GeneratedBomSwapTest}; these tests cover the orchestration:
 * the recursive prune and the zero-swap paths.
 */
class CentralStagingTest {

    @Test
    void prune_deletes_build_poms_and_sidecars_recursively(
            @TempDir Path staging) throws Exception {
        Path module = Files.createDirectories(
                staging.resolve("network/ike/platform/ike-bom/146"));
        Files.writeString(module.resolve("ike-bom-146-build.pom"),
                "<project/>");
        Files.writeString(module.resolve("ike-bom-146-build.pom.asc"),
                "sig");
        Files.writeString(module.resolve("ike-bom-146-build.pom.sha256"),
                "sum");
        Files.writeString(module.resolve("ike-bom-146.pom"), "<project/>");
        Files.writeString(module.resolve("ike-bom-146.pom.asc"), "sig");

        CentralStaging.pruneBuildPoms(new TestLog(), staging);

        assertThat(module.resolve("ike-bom-146-build.pom")).doesNotExist();
        assertThat(module.resolve("ike-bom-146-build.pom.asc"))
                .doesNotExist();
        assertThat(module.resolve("ike-bom-146-build.pom.sha256"))
                .doesNotExist();
        assertThat(module.resolve("ike-bom-146.pom")).exists();
        assertThat(module.resolve("ike-bom-146.pom.asc")).exists();
    }

    @Test
    void swap_returns_zero_without_generated_boms(@TempDir Path root)
            throws Exception {
        // A repo without a BOM module stages nothing to swap — the
        // finalization is a logged no-op, not a failure. The mvnw
        // path may be absent here: the re-sign subprocess only runs
        // per planned swap.
        Path buildRoot = Files.createDirectories(root.resolve("repo"));
        Path staging = Files.createDirectories(
                buildRoot.resolve("target/staging-deploy"));

        int swapped = CentralStaging.swapGeneratedBoms(new TestLog(),
                buildRoot.toFile(),
                new File(buildRoot.toFile(), "mvnw"), staging);

        assertThat(swapped).isZero();
    }

    @Test
    void swap_skips_generated_bom_without_staged_counterpart(
            @TempDir Path root) throws Exception {
        // plan() pairs generated BOMs with staged POMs; a generated
        // BOM whose artifact was never staged is skipped, not failed.
        Path buildRoot = Files.createDirectories(root.resolve("repo"));
        Path target = Files.createDirectories(
                buildRoot.resolve("ike-bom/target"));
        Files.writeString(target.resolve("generated-bom.xml"), """
                <project>
                  <groupId>network.ike.platform</groupId>
                  <artifactId>ike-bom</artifactId>
                  <version>146</version>
                  <dependencyManagement/>
                </project>
                """);
        Path staging = Files.createDirectories(
                buildRoot.resolve("target/staging-deploy"));

        int swapped = CentralStaging.swapGeneratedBoms(new TestLog(),
                buildRoot.toFile(),
                new File(buildRoot.toFile(), "mvnw"), staging);

        assertThat(swapped).isZero();
    }
}
