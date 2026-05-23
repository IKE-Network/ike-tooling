package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VerifyReleasePublishedMojo}'s pure helpers
 * (POM field extraction, group-path conversion). HTTP checks aren't
 * unit-tested here — they're exercised end-to-end at release-flow
 * verify time and don't lend themselves to lightweight mocking
 * without dragging in HttpClient stubs.
 *
 * <p>ike-issues#374.
 */
class VerifyReleasePublishedMojoTest {

    // ── readPomField ─────────────────────────────────────────────

    @Test
    void readPomField_skipsParentBlock(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <parent>
                    <groupId>parent.group</groupId>
                    <artifactId>parent-artifact</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>child-artifact</artifactId>
                  <version>5-SNAPSHOT</version>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomField(
                pom.toFile(), "artifactId"))
                .isEqualTo("child-artifact");
        assertThat(VerifyReleasePublishedMojo.readPomField(
                pom.toFile(), "version"))
                .isEqualTo("5-SNAPSHOT");
    }

    @Test
    void readPomField_noParent_readsDirect(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <groupId>solo.group</groupId>
                  <artifactId>solo</artifactId>
                  <version>1</version>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomField(
                pom.toFile(), "groupId"))
                .isEqualTo("solo.group");
    }

    @Test
    void readPomField_missingField_null(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><artifactId>x</artifactId></project>");
        assertThat(VerifyReleasePublishedMojo.readPomField(
                pom.toFile(), "version")).isNull();
    }

    @Test
    void readPomField_missingFile_null() {
        assertThat(VerifyReleasePublishedMojo.readPomField(
                new File("/does/not/exist/pom.xml"), "artifactId"))
                .isNull();
        assertThat(VerifyReleasePublishedMojo.readPomField(
                null, "artifactId")).isNull();
    }

    // ── readParentField ──────────────────────────────────────────

    @Test
    void readParentField_extractsParentGroupId(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>43</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readParentField(
                pom.toFile(), "groupId"))
                .isEqualTo("network.ike.platform");
    }

    @Test
    void readParentField_noParent_null(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><artifactId>x</artifactId></project>");
        assertThat(VerifyReleasePublishedMojo.readParentField(
                pom.toFile(), "groupId")).isNull();
    }

    // ── readPomGroupPath ─────────────────────────────────────────

    @Test
    void readPomGroupPath_directGroupId_dotsToSlashes(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <groupId>network.ike.tooling</groupId>
                  <artifactId>x</artifactId>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomGroupPath(pom.toFile()))
                .isEqualTo("network/ike/tooling");
    }

    @Test
    void readPomGroupPath_fallsBackToParent(@TempDir Path tmp) throws IOException {
        // Common IKE shape: child declares no <groupId>, inherits from <parent>.
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>43</version>
                  </parent>
                  <artifactId>doc-example</artifactId>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomGroupPath(pom.toFile()))
                .isEqualTo("network/ike/platform");
    }

    @Test
    void readPomGroupPath_neitherGroupId_null(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><artifactId>x</artifactId></project>");
        assertThat(VerifyReleasePublishedMojo.readPomGroupPath(pom.toFile()))
                .isNull();
    }

    // ── padRight ─────────────────────────────────────────────────

    @Test
    void padRight_paddedAndTruncated() {
        assertThat(VerifyReleasePublishedMojo.padRight("x", 5))
                .isEqualTo("x    ");
        assertThat(VerifyReleasePublishedMojo.padRight("toolong", 3))
                .isEqualTo("toolong");
    }

    // ── resolveDefaults integration ──────────────────────────────

    @Test
    void resolveDefaults_stripsSnapshotFromVersion(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <artifactId>my-project</artifactId>
                  <version>42-SNAPSHOT</version>
                </project>
                """);
        VerifyReleasePublishedMojo mojo = new VerifyReleasePublishedMojo();
        mojo.pomFile = pom.toFile();
        mojo.resolveDefaults();
        assertThat(mojo.projectId).isEqualTo("my-project");
        assertThat(mojo.version).isEqualTo("42");
    }

    // ── gh-pages tree walk helpers ───────────────────────────────

    @Test
    void extractIndexHtmlPaths_findsPathsEndingInIndexHtml() {
        String body = """
                {
                  "tree": [
                    {"path": "index.html", "type": "blob"},
                    {"path": "21/index.html", "type": "blob"},
                    {"path": "21/doc-example/index.html", "type": "blob"},
                    {"path": "latest/index.html", "type": "blob"},
                    {"path": "css", "type": "tree"},
                    {"path": "css/site.css", "type": "blob"}
                  ]
                }
                """;
        var paths = VerifyReleasePublishedMojo.extractIndexHtmlPaths(body);
        org.assertj.core.api.Assertions.assertThat(paths)
                .containsExactly("index.html",
                        "21/index.html",
                        "21/doc-example/index.html",
                        "latest/index.html");
    }

    @Test
    void extractIndexHtmlPaths_excludesJavadocStyleSuffixes() {
        // javadoc generates files like allclasses-index.html and
        // allpackages-index.html — these just happen to end with
        // "index.html" but aren't a directory's landing page.
        // Must NOT match.
        String body = """
                {
                  "tree": [
                    {"path": "apidocs/allclasses-index.html", "type": "blob"},
                    {"path": "apidocs/allpackages-index.html", "type": "blob"},
                    {"path": "apidocs/index.html", "type": "blob"},
                    {"path": "apidocs/overview-summary.html", "type": "blob"}
                  ]
                }
                """;
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.extractIndexHtmlPaths(body))
                .containsExactly("apidocs/index.html");
    }

    @Test
    void extractIndexHtmlPaths_emptyOnNullOrBlank() {
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.extractIndexHtmlPaths(null))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.extractIndexHtmlPaths(""))
                .isEmpty();
    }

    @Test
    void parseSkipPatterns_splitsAndTrimsCommaSeparated() {
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.parseSkipPatterns(
                        "apidocs/, */apidocs/,xref/"))
                .containsExactly("apidocs/", "*/apidocs/", "xref/");
    }

    @Test
    void parseSkipPatterns_blankReturnsEmptyList() {
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.parseSkipPatterns(""))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.parseSkipPatterns(null))
                .isEmpty();
    }

    @Test
    void shouldSkipPath_versionDirsOtherThanCurrent() {
        var skip = java.util.List.<String>of();
        // Numeric prefix that isn't current → skip
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "20/doc-example", "21", skip)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "21/doc-example", "21", skip)).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "latest/doc-example", "21", skip)).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "doc-example", "21", skip)).isFalse();
    }

    @Test
    void shouldSkipPath_userPatterns_literal() {
        var skip = java.util.List.of("apidocs/");
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "apidocs/network/ike", "21", skip)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "apidocs", "21", skip)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "css/site.css", "21", skip)).isFalse();
    }

    @Test
    void shouldSkipPath_userPatterns_starWildcard() {
        var skip = java.util.List.of("*/apidocs/");
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "ike-bom/apidocs/network", "21", skip)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "ike-bom/apidocs", "21", skip)).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.shouldSkipPath(
                        "ike-bom/something-else", "21", skip)).isFalse();
    }

    @Test
    void looksLikeVersionSegment_recognizesShapes() {
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment("21"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment("166"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment(
                        "7-checkpoint.20260228.1")).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment(
                        "ike-tooling")).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment(""))
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(
                VerifyReleasePublishedMojo.looksLikeVersionSegment(null))
                .isFalse();
    }

    @Test
    void resolveDefaults_respectsExplicitValues(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <artifactId>my-project</artifactId>
                  <version>42-SNAPSHOT</version>
                </project>
                """);
        VerifyReleasePublishedMojo mojo = new VerifyReleasePublishedMojo();
        mojo.pomFile = pom.toFile();
        mojo.projectId = "override-project";
        mojo.version = "99";
        mojo.resolveDefaults();
        assertThat(mojo.projectId).isEqualTo("override-project");
        assertThat(mojo.version).isEqualTo("99");
    }

    // ── readPomSubprojects (ike-issues#382) ──────────────────────

    @Test
    void readPomSubprojects_maven4Subprojects(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <subprojects>
                    <subproject>module-a</subproject>
                    <subproject>module-b</subproject>
                    <subproject>module-c</subproject>
                  </subprojects>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                pom.toFile()))
                .containsExactly("module-a", "module-b", "module-c");
    }

    @Test
    void readPomSubprojects_legacyModules(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modules>
                    <module>old-a</module>
                    <module>old-b</module>
                  </modules>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                pom.toFile()))
                .containsExactly("old-a", "old-b");
    }

    @Test
    void readPomSubprojects_inProfile_picksThemUp(@TempDir Path tmp)
            throws IOException {
        // workspace-reactor-example pattern: subprojects inside file-activated
        // profiles. The cross-reference should still see them so the
        // workspace verify covers all declared subprojects.
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <profiles>
                    <profile>
                      <id>with-doc-example</id>
                      <subprojects>
                        <subproject>doc-example</subproject>
                      </subprojects>
                    </profile>
                    <profile>
                      <id>with-project-example</id>
                      <subprojects>
                        <subproject>project-example</subproject>
                      </subprojects>
                    </profile>
                  </profiles>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                pom.toFile()))
                .containsExactly("doc-example", "project-example");
    }

    @Test
    void readPomSubprojects_deduplicates(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <subprojects>
                    <subproject>module-a</subproject>
                  </subprojects>
                  <profiles>
                    <profile>
                      <id>extra</id>
                      <subprojects>
                        <subproject>module-a</subproject>
                      </subprojects>
                    </profile>
                  </profiles>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                pom.toFile()))
                .containsExactly("module-a");
    }

    @Test
    void readPomSubprojects_noneDeclared_empty(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <artifactId>singleton</artifactId>
                </project>
                """);
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                pom.toFile()))
                .isEmpty();
    }

    @Test
    void readPomSubprojects_missingFile_empty() {
        assertThat(VerifyReleasePublishedMojo.readPomSubprojects(
                new File("/does/not/exist/pom.xml")))
                .isEmpty();
    }
}
