package network.ike.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link OrgSiteSupport#resolveMaven} — specifically the
 * {@code maven.home} fallback (IKE-Network/ike-issues#928): the
 * org-site repo ships no {@code mvnw}, and resolution previously fell
 * straight through to a system {@code mvn} on PATH, failing on hosts
 * without one even though a Maven distribution is by definition
 * running the current build.
 */
class OrgSiteSupportResolveMavenTest {

    @TempDir Path tempDir;

    private String savedMavenHome;

    @BeforeEach
    void saveMavenHome() {
        savedMavenHome = System.getProperty("maven.home");
    }

    @AfterEach
    void restoreMavenHome() {
        if (savedMavenHome == null) {
            System.clearProperty("maven.home");
        } else {
            System.setProperty("maven.home", savedMavenHome);
        }
    }

    @Test
    void repo_wrapper_wins_when_present() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path mvnw = Files.writeString(repo.resolve("mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(mvnw, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

        File resolved = OrgSiteSupport.resolveMaven(
                repo.toFile(), new TestLog());

        assertThat(resolved).isEqualTo(mvnw.toFile());
    }

    @Test
    void falls_back_to_the_running_mavens_distribution() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("no-wrapper"));
        Path mavenHome = Files.createDirectories(tempDir.resolve("maven-home"));
        Path bin = Files.createDirectories(mavenHome.resolve("bin"));
        Path mvn = Files.writeString(bin.resolve("mvn"), "#!/bin/sh\n");
        System.setProperty("maven.home", mavenHome.toString());

        File resolved = OrgSiteSupport.resolveMaven(
                repo.toFile(), new TestLog());

        assertThat(resolved).isEqualTo(mvn.toFile());
    }
}
