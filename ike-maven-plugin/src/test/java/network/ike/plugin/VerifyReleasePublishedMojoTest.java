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
}
