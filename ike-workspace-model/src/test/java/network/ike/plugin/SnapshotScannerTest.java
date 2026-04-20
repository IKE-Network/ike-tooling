package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SnapshotScanner} — guards against the
 * {@code <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>}
 * class of consumer-POM leakage bug (see #177).
 */
class SnapshotScannerTest {

    // ── scanSourceProperties ──────────────────────────────────────────

    @Test
    void scanSourceProperties_catchesSnapshotPropertyValue(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <properties>
                    <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>
                    <other>1.2.3</other>
                  </properties>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanSourceProperties(pom);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("<ike-tooling.version>");
            assertThat(v.value()).isEqualTo("112-SNAPSHOT");
            assertThat(v.pomFile()).isEqualTo(pom);
        });
    }

    @Test
    void scanSourceProperties_passesWhenNoSnapshot(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <properties>
                    <ike-tooling.version>117</ike-tooling.version>
                    <other>1.2.3</other>
                  </properties>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    @Test
    void scanSourceProperties_ignoresNonPropertiesElements(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <version>117-SNAPSHOT</version>
                  <properties>
                    <other>1.2.3</other>
                  </properties>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    @Test
    void scanSourceProperties_multipleViolations(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <properties>
                    <a.version>1-SNAPSHOT</a.version>
                    <b.version>2</b.version>
                    <c.version>3-SNAPSHOT</c.version>
                  </properties>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanSourceProperties(pom);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(SnapshotScanner.Violation::value)
                .containsExactly("1-SNAPSHOT", "3-SNAPSHOT");
    }

    @Test
    void scanSourceProperties_handlesMissingPropertiesBlock(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <groupId>x</groupId>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    // ── scanForSnapshotVersions ───────────────────────────────────────

    @Test
    void scanForSnapshotVersions_findsLiteralSnapshotVersion(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <version>117-SNAPSHOT</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("<version>");
            assertThat(v.value()).isEqualTo("117-SNAPSHOT");
        });
    }

    @Test
    void scanForSnapshotVersions_passesWhenAllLiteralVersionsAreReleased(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <version>105</version>
                  <build>
                    <plugins>
                      <plugin><version>117</version></plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(SnapshotScanner.scanForSnapshotVersions(List.of(pom))).isEmpty();
    }

    @Test
    void scanForSnapshotVersions_aggregatesAcrossFiles(@TempDir Path dir) throws IOException {
        File pom1 = writePom(dir.resolve("a"), """
                <project><version>1-SNAPSHOT</version></project>
                """);
        File pom2 = writePom(dir.resolve("b"), """
                <project><version>2</version></project>
                """);
        File pom3 = writePom(dir.resolve("c"), """
                <project><version>3-SNAPSHOT</version></project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom1, pom2, pom3));

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(SnapshotScanner.Violation::value)
                .containsExactly("1-SNAPSHOT", "3-SNAPSHOT");
    }

    // ── Violation.toBullet ────────────────────────────────────────────

    @Test
    void violationBullet_relativizesPathWhenGitRootProvided(@TempDir Path dir) throws IOException {
        File pom = writePom(dir.resolve("sub"), """
                <project><properties><x>1-SNAPSHOT</x></properties></project>
                """);

        SnapshotScanner.Violation v = new SnapshotScanner.Violation(
                pom, "<x>", "1-SNAPSHOT");

        assertThat(v.toBullet(dir.toFile()))
                .isEqualTo("    • sub/pom.xml: <x> = 1-SNAPSHOT");
    }

    @Test
    void violationBullet_usesAbsoluteWhenGitRootNull(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project></project>
                """);

        SnapshotScanner.Violation v = new SnapshotScanner.Violation(
                pom, "<x>", "1-SNAPSHOT");

        assertThat(v.toBullet(null))
                .contains(pom.getPath())
                .endsWith("<x> = 1-SNAPSHOT");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static File writePom(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content, StandardCharsets.UTF_8);
        return pom.toFile();
    }
}
