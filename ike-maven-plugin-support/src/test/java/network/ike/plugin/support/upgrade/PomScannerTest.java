package network.ike.plugin.support.upgrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PomScannerTest {

    @Test
    void parsesParentReference() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>3</version>
                  </parent>
                  <artifactId>ike-bom</artifactId>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.parent()).isNotNull();
        assertThat(r.parent().groupId()).isEqualTo("network.ike.platform");
        assertThat(r.parent().artifactId()).isEqualTo("ike-parent");
        assertThat(r.parent().version()).isEqualTo("3");
    }

    @Test
    void absentParentIsNull() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>foo</groupId>
                  <artifactId>bar</artifactId>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.parent()).isNull();
    }

    @Test
    void parsesVersionPropertiesMap() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>127</ike-tooling.version>
                    <junit-jupiter.version>5.11.4</junit-jupiter.version>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.versionProperties())
                .containsEntry("ike-tooling.version", "127")
                .containsEntry("junit-jupiter.version", "5.11.4")
                .containsEntry("project.build.sourceEncoding", "UTF-8");
    }

    @Test
    void mapsPropertyReferencesToConsumingCoords() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>127</ike-tooling.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>network.ike.tooling</groupId>
                      <artifactId>ike-workspace-model</artifactId>
                      <version>${ike-tooling.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.propertyToCoords())
                .containsKey("ike-tooling.version");
        assertThat(r.propertyToCoords().get("ike-tooling.version"))
                .extracting("artifactId")
                .containsExactly("ike-workspace-model");
    }

    @Test
    void capturesLiteralVersionedPlugin() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>network.ike.tooling</groupId>
                          <artifactId>ike-maven-plugin</artifactId>
                          <version>125</version>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.literals()).hasSize(1);
        PomScanner.LiteralCoord lit = r.literals().get(0);
        assertThat(lit.groupId()).isEqualTo("network.ike.tooling");
        assertThat(lit.artifactId()).isEqualTo("ike-maven-plugin");
        assertThat(lit.version()).isEqualTo("125");
        assertThat(lit.location()).contains("pluginManagement");
    }

    @Test
    void scansDependencyManagementBomImports() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-bom</artifactId>
                        <version>3</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.literals()).hasSize(1);
        assertThat(r.literals().get(0).artifactId()).isEqualTo("ike-bom");
    }

    @Test
    void scansProfileBodies() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <profiles>
                    <profile>
                      <id>release</id>
                      <build>
                        <plugins>
                          <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-gpg-plugin</artifactId>
                            <version>3.2.7</version>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.literals()).hasSize(1);
        assertThat(r.literals().get(0).artifactId())
                .isEqualTo("maven-gpg-plugin");
        assertThat(r.literals().get(0).location())
                .contains("profile[release]");
    }

    @Test
    void inheritedVersionDoesNotAppearInResults() {
        // No <version> tag — version is inherited from
        // <pluginManagement>, so we must not generate an entry.
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;

        PomScanner.PomScanResult r = PomScanner.scan(pom);

        assertThat(r.literals()).isEmpty();
        assertThat(r.propertyToCoords()).isEmpty();
    }

    // ── extractPropertyReference ────────────────────────────────────

    @Test
    void simplePropertyReferenceIsExtracted() {
        assertThat(PomScanner.extractPropertyReference("${ike-tooling.version}"))
                .isEqualTo("ike-tooling.version");
    }

    @Test
    void literalIsNotPropertyReference() {
        assertThat(PomScanner.extractPropertyReference("3.5.2")).isNull();
    }

    @Test
    void mixedExpressionIsNotPropertyReference() {
        assertThat(PomScanner.extractPropertyReference("${a}-suffix")).isNull();
        assertThat(PomScanner.extractPropertyReference("prefix-${a}")).isNull();
    }
}
