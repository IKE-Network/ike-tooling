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
 *
 * <p>All test POMs include {@code <modelVersion>} so they parse
 * through {@link org.apache.maven.model.v4.MavenStaxReader}. Comments
 * and CDATA are natively ignored by the Maven parser — regression for
 * the false-positive bug where a regex-based scanner flagged SNAPSHOT
 * versions inside {@code <!-- ... -->} blocks.
 */
class SnapshotScannerTest {

    // ── scanSourceProperties ──────────────────────────────────────────

    @Test
    void scanSourceProperties_catchesSnapshotPropertyValue(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <properties>
                    <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>
                    <other>1.2.3</other>
                  </properties>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanSourceProperties(pom);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("properties/ike-tooling.version");
            assertThat(v.value()).isEqualTo("112-SNAPSHOT");
            assertThat(v.pomFile()).isEqualTo(pom);
        });
    }

    @Test
    void scanSourceProperties_passesWhenNoSnapshot(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <properties>
                    <ike-tooling.version>117</ike-tooling.version>
                    <other>1.2.3</other>
                  </properties>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    @Test
    void scanSourceProperties_ignoresModuleOwnVersion(@TempDir Path dir) throws IOException {
        // Module's own <version> is handled by ReleaseSupport.setPomVersion
        // and is not a consumer-POM leakage path. Must not be flagged.
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
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
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
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
                .containsExactlyInAnyOrder("1-SNAPSHOT", "3-SNAPSHOT");
    }

    @Test
    void scanSourceProperties_handlesMissingPropertiesBlock(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    @Test
    void scanSourceProperties_ignoresCommentedSnapshotValues(@TempDir Path dir) throws IOException {
        // Regression: regex-based scanner flagged SNAPSHOT inside <!-- -->.
        // Maven StAX parser strips comments natively.
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <properties>
                    <!-- example for docs: <foo.version>9-SNAPSHOT</foo.version> -->
                    <real.version>2</real.version>
                  </properties>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
    }

    @Test
    void scanSourceProperties_findsProfileProperties(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <profiles>
                    <profile>
                      <id>release</id>
                      <properties>
                        <rel.version>9-SNAPSHOT</rel.version>
                      </properties>
                    </profile>
                  </profiles>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanSourceProperties(pom);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("profiles/release/properties/rel.version");
            assertThat(v.value()).isEqualTo("9-SNAPSHOT");
        });
    }

    // ── scanForSnapshotVersions ───────────────────────────────────────

    @Test
    void scanForSnapshotVersions_findsPluginSnapshotInPluginManagement(@TempDir Path dir) throws IOException {
        // The exact bug in ike-parent:289 that this preflight was designed
        // to catch: a literal SNAPSHOT in <pluginManagement>.
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>network.ike.tooling</groupId>
                          <artifactId>ike-maven-plugin</artifactId>
                          <version>117-SNAPSHOT</version>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo(
                    "build/pluginManagement/network.ike.tooling:ike-maven-plugin");
            assertThat(v.value()).isEqualTo("117-SNAPSHOT");
        });
    }

    @Test
    void scanForSnapshotVersions_findsSnapshotInDependency(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.foo</groupId>
                      <artifactId>bar</artifactId>
                      <version>2-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("dependencies/org.foo:bar");
            assertThat(v.value()).isEqualTo("2-SNAPSHOT");
        });
    }

    @Test
    void scanForSnapshotVersions_findsSnapshotInParent(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.tooling</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>105-SNAPSHOT</version>
                  </parent>
                  <artifactId>a</artifactId>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo(
                    "parent[network.ike.tooling:ike-parent]");
            assertThat(v.value()).isEqualTo("105-SNAPSHOT");
        });
    }

    @Test
    void scanForSnapshotVersions_ignoresModuleOwnVersion(@TempDir Path dir) throws IOException {
        // Module's own <project><version> is handled by setPomVersion
        // and must not be flagged.
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>117-SNAPSHOT</version>
                </project>
                """);

        assertThat(SnapshotScanner.scanForSnapshotVersions(List.of(pom))).isEmpty();
    }

    @Test
    void scanForSnapshotVersions_ignoresCommentedSnapshot(@TempDir Path dir) throws IOException {
        // Regression: regex-based scanner flagged SNAPSHOT inside <!-- -->.
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <!--
                    <dependency><version>9-SNAPSHOT</version></dependency>
                  -->
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId>
                      <artifactId>y</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(SnapshotScanner.scanForSnapshotVersions(List.of(pom))).isEmpty();
    }

    @Test
    void scanForSnapshotVersions_passesWhenAllLiteralVersionsAreReleased(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>105</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>g</groupId>
                        <artifactId>p</artifactId>
                        <version>117</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(SnapshotScanner.scanForSnapshotVersions(List.of(pom))).isEmpty();
    }

    @Test
    void scanForSnapshotVersions_aggregatesAcrossFiles(@TempDir Path dir) throws IOException {
        File pom1 = writePom(dir.resolve("a"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId><artifactId>y</artifactId>
                      <version>1-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        File pom2 = writePom(dir.resolve("b"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>b</artifactId>
                  <version>2</version>
                </project>
                """);
        File pom3 = writePom(dir.resolve("c"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>c</artifactId>
                  <version>3</version>
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId><artifactId>z</artifactId>
                      <version>3-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<SnapshotScanner.Violation> violations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom1, pom2, pom3));

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(SnapshotScanner.Violation::value)
                .containsExactly("1-SNAPSHOT", "3-SNAPSHOT");
    }

    // ── Regression: ike-parent shape (#175 acceptance criterion #3) ───

    /**
     * Anchor the {@code <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>}
     * bug to the actual ike-parent layout from before the property bump
     * (commit {@code 401196c}). The shape mirrors the real POM:
     *
     * <ul>
     *   <li>Multiple {@code <dependencyManagement>} entries reference
     *       {@code ${ike-tooling.version}} (consumer-POM-flattened — Maven 4
     *       writes the resolved literal into the released artifact).</li>
     *   <li>One {@code <pluginManagement>} entry uses a literal version
     *       because Maven loads extensions before property interpolation
     *       (this entry must not be flagged).</li>
     *   <li>The {@code <ike-tooling.version>} property value is
     *       {@code 112-SNAPSHOT} — exactly the value that leaked into
     *       released {@code ike-parent-105.pom}.</li>
     * </ul>
     *
     * <p>Layer 1 ({@link SnapshotScanner#scanSourceProperties}) must
     * flag the property exactly once. Layer 2
     * ({@link SnapshotScanner#scanForSnapshotVersions}) against the
     * literal-resolved POM must report zero violations — there is no
     * baked-in SNAPSHOT once the property has been bumped to a release
     * version.
     */
    @Test
    void regression_ikeParentShape_circa_401196c_flagsPropertyOnce(@TempDir Path dir)
            throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>network.ike</groupId>
                  <artifactId>ike-parent</artifactId>
                  <version>105</version>
                  <packaging>pom</packaging>
                  <properties>
                    <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-build-standards</artifactId>
                        <version>${ike-tooling.version}</version>
                        <classifier>claude</classifier>
                        <type>zip</type>
                      </dependency>
                      <dependency>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-build-standards</artifactId>
                        <version>${ike-tooling.version}</version>
                        <classifier>docs</classifier>
                        <type>zip</type>
                      </dependency>
                      <dependency>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-build-standards</artifactId>
                        <version>${ike-tooling.version}</version>
                        <classifier>asciidoctorconfig</classifier>
                        <type>zip</type>
                      </dependency>
                      <dependency>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-build-standards</artifactId>
                        <version>${ike-tooling.version}</version>
                        <classifier>config</classifier>
                        <type>zip</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>network.ike.tooling</groupId>
                          <artifactId>ike-maven-plugin</artifactId>
                          <version>118</version>
                          <extensions>true</extensions>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        // Layer 1: source-side property scan flags the SNAPSHOT property
        // exactly once. The four <dependency> entries that reference
        // ${ike-tooling.version} are NOT flagged here — they would only
        // be flagged after Maven's consumer POM flattener resolves them,
        // which Layer 2 simulates after release-time substitution.
        List<SnapshotScanner.Violation> propViolations =
                SnapshotScanner.scanSourceProperties(pom);
        assertThat(propViolations).singleElement().satisfies(v -> {
            assertThat(v.location()).isEqualTo("properties/ike-tooling.version");
            assertThat(v.value()).isEqualTo("112-SNAPSHOT");
        });

        // Layer 2: against the as-written POM (with property references
        // unresolved), no <version>...-SNAPSHOT</version> literal exists,
        // so the version scan must be silent. The literal "118" on the
        // extensions plugin must not trip a false positive.
        List<SnapshotScanner.Violation> versionViolations =
                SnapshotScanner.scanForSnapshotVersions(List.of(pom));
        assertThat(versionViolations).isEmpty();
    }

    /**
     * Same shape as {@link #regression_ikeParentShape_circa_401196c_flagsPropertyOnce}
     * but with the property bumped to a released version. Both layers
     * must report clean — this is the post-fix state that the release
     * gate is enforcing.
     */
    @Test
    void regression_ikeParentShape_postPropertyBump_passesBothLayers(@TempDir Path dir)
            throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>network.ike</groupId>
                  <artifactId>ike-parent</artifactId>
                  <version>106</version>
                  <packaging>pom</packaging>
                  <properties>
                    <ike-tooling.version>117</ike-tooling.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-build-standards</artifactId>
                        <version>${ike-tooling.version}</version>
                        <classifier>claude</classifier>
                        <type>zip</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>network.ike.tooling</groupId>
                          <artifactId>ike-maven-plugin</artifactId>
                          <version>118</version>
                          <extensions>true</extensions>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        assertThat(SnapshotScanner.scanSourceProperties(pom)).isEmpty();
        assertThat(SnapshotScanner.scanForSnapshotVersions(List.of(pom))).isEmpty();
    }

    // ── Violation.toBullet ────────────────────────────────────────────

    @Test
    void violationBullet_relativizesPathWhenGitRootProvided(@TempDir Path dir) throws IOException {
        File pom = writePom(dir.resolve("sub"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                </project>
                """);

        SnapshotScanner.Violation v = new SnapshotScanner.Violation(
                pom, "properties/x", "1-SNAPSHOT");

        assertThat(v.toBullet(dir.toFile()))
                .isEqualTo("    • sub/pom.xml: properties/x = 1-SNAPSHOT");
    }

    @Test
    void violationBullet_usesAbsoluteWhenGitRootNull(@TempDir Path dir) throws IOException {
        File pom = writePom(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                </project>
                """);

        SnapshotScanner.Violation v = new SnapshotScanner.Violation(
                pom, "properties/x", "1-SNAPSHOT");

        assertThat(v.toBullet(null))
                .contains(pom.getPath())
                .endsWith("properties/x = 1-SNAPSHOT");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static File writePom(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content, StandardCharsets.UTF_8);
        return pom.toFile();
    }
}
