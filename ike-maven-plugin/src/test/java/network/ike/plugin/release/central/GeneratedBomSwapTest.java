package network.ike.plugin.release.central;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link GeneratedBomSwap} — the staging-time replacement of a
 * stub BOM POM with {@code ike:generate-bom} output
 * (IKE-Network/ike-issues#853).
 *
 * <p>The defect this guards against was silent: versions 72–132 of
 * {@code ike-bom} deployed the stub — no {@code <dependencyManagement>},
 * leaked {@code <build>} and {@code <distributionManagement>} — and no
 * build step ever noticed.
 */
class GeneratedBomSwapTest {

    private static final String GENERATED_BOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>network.ike.platform</groupId>
                <artifactId>ike-bom</artifactId>
                <version>133</version>
                <packaging>pom</packaging>
                <name>IKE BOM</name>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>dev.ikm.tinkar</groupId>
                            <artifactId>entity</artifactId>
                            <version>1.127.2</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    private static final String STUB_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>network.ike.platform</groupId>
                <artifactId>ike-bom</artifactId>
                <version>133</version>
                <packaging>pom</packaging>
                <build>
                    <plugins/>
                </build>
                <distributionManagement>
                    <repository>
                        <id>internal</id>
                        <url>https://nexus.example.org/staging</url>
                    </repository>
                </distributionManagement>
            </project>
            """;

    @TempDir Path tempDir;

    private Path gitRoot;
    private Path stagingDir;
    private Path stagedPom;

    @BeforeEach
    void setUp() throws Exception {
        gitRoot = tempDir.resolve("worktree");
        stagingDir = gitRoot.resolve("target/staging-deploy");
        Path bomTarget = gitRoot.resolve("ike-bom/target");
        Files.createDirectories(bomTarget);
        Files.writeString(bomTarget.resolve("generated-bom.xml"),
                GENERATED_BOM, StandardCharsets.UTF_8);

        Path stagedDir = stagingDir.resolve(
                "network/ike/platform/ike-bom/133");
        Files.createDirectories(stagedDir);
        stagedPom = stagedDir.resolve("ike-bom-133.pom");
        Files.writeString(stagedPom, STUB_POM, StandardCharsets.UTF_8);
    }

    @Test
    void plan_pairs_generated_bom_with_staged_pom() throws Exception {
        List<GeneratedBomSwap.Swap> swaps =
                GeneratedBomSwap.plan(gitRoot, stagingDir);

        assertThat(swaps).hasSize(1);
        GeneratedBomSwap.Swap swap = swaps.get(0);
        assertThat(swap.groupId()).isEqualTo("network.ike.platform");
        assertThat(swap.artifactId()).isEqualTo("ike-bom");
        assertThat(swap.version()).isEqualTo("133");
        assertThat(swap.stagedPom()).isEqualTo(stagedPom);
    }

    @Test
    void plan_skips_generated_bom_without_staged_counterpart()
            throws Exception {
        Files.delete(stagedPom);

        assertThat(GeneratedBomSwap.plan(gitRoot, stagingDir)).isEmpty();
    }

    @Test
    void apply_swaps_content_and_deletes_stale_sidecars() throws Exception {
        for (String suffix : List.of(".asc", ".md5", ".sha1",
                ".sha256", ".sha512")) {
            Files.writeString(stagedPom.resolveSibling(
                    "ike-bom-133.pom" + suffix), "stale");
        }
        GeneratedBomSwap.Swap swap =
                GeneratedBomSwap.plan(gitRoot, stagingDir).get(0);

        List<Path> deleted = GeneratedBomSwap.apply(swap);

        assertThat(Files.readString(stagedPom, StandardCharsets.UTF_8))
                .isEqualTo(GENERATED_BOM);
        assertThat(deleted).hasSize(5);
        assertThat(Files.list(stagedPom.getParent()))
                .as("stale signature and checksums are gone")
                .containsExactly(stagedPom);
    }

    @Test
    void verify_accepts_swapped_and_rejects_stub() throws Exception {
        GeneratedBomSwap.Swap swap =
                GeneratedBomSwap.plan(gitRoot, stagingDir).get(0);

        assertThat(GeneratedBomSwap.verify(swap))
                .as("the stub must fail verification")
                .contains("no <dependencyManagement>");

        GeneratedBomSwap.apply(swap);

        assertThat(GeneratedBomSwap.verify(swap)).isNull();
    }

    @Test
    void verify_rejects_leaked_build_machinery() throws Exception {
        GeneratedBomSwap.Swap swap =
                GeneratedBomSwap.plan(gitRoot, stagingDir).get(0);
        Files.writeString(stagedPom,
                GENERATED_BOM.replace("</project>",
                        "    <build>\n    </build>\n</project>"),
                StandardCharsets.UTF_8);

        assertThat(GeneratedBomSwap.verify(swap))
                .contains("stub build machinery");
    }
}
