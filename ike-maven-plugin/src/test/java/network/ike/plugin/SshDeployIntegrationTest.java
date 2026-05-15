package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for SSH-based site deployment using a real
 * SSH server in a Docker container. Tests the stage-and-swap
 * deployment logic from ReleaseSupport against a real SSH daemon.
 *
 * <p>Uses key-based auth (no sshpass dependency). An ephemeral
 * RSA keypair is generated per test run and mounted into the
 * container.
 *
 * <p>Requires Docker. Excluded from default build; run with
 * {@code mvn verify -Dgroups=container}.
 */
class SshDeployIntegrationTest extends ContainerTestSupport {

    private static final String SSH_USER = "testuser";
    private static Path privateKeyFile;
    private static GenericContainer<?> sshContainer;
    private static int sshPort;

    @BeforeAll
    static void startContainer() throws Exception {
        // Generate ephemeral keypair
        Path keyDir = Files.createTempDirectory("ssh-test-keys");
        privateKeyFile = keyDir.resolve("id_rsa");
        Path publicKeyFile = keyDir.resolve("id_rsa.pub");

        // Use ssh-keygen (available on macOS, Linux, Windows with Git)
        Process keygen = new ProcessBuilder(
                "ssh-keygen", "-t", "rsa", "-b", "2048",
                "-f", privateKeyFile.toString(),
                "-N", "",  // no passphrase
                "-q")      // quiet
                .redirectErrorStream(true)
                .start();
        keygen.getInputStream().readAllBytes();
        assertThat(keygen.waitFor()).as("ssh-keygen failed").isZero();

        // Restrict private key permissions
        Files.setPosixFilePermissions(privateKeyFile,
                PosixFilePermissions.fromString("rw-------"));

        // Use linuxserver/openssh-server for full shell access
        // (atmoz/sftp is SFTP-only with chroot, no shell commands)
        sshContainer = new GenericContainer<>(
                DockerImageName.parse("lscr.io/linuxserver/openssh-server:latest"))
                .withExposedPorts(2222)
                .withEnv("PUID", "1000")
                .withEnv("PGID", "1000")
                .withEnv("USER_NAME", SSH_USER)
                .withEnv("PUBLIC_KEY", Files.readString(publicKeyFile).trim())
                .withEnv("SUDO_ACCESS", "true")
                .withEnv("PASSWORD_ACCESS", "false");

        sshContainer.start();
        sshPort = sshContainer.getMappedPort(2222);

        // Create the site base directory for ReleaseSupport tests.
        // The container user has sudo access, so use it to create /srv.
        waitForSsh();
        sshExecStatic("sudo mkdir -p /srv/ike-site && sudo chown "
                + SSH_USER + ":" + SSH_USER + " /srv/ike-site");
    }

    /** Wait until the SSH server accepts connections (may take a moment after container start). */
    private static void waitForSsh() throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ssh", "-i", privateKeyFile.toString(),
                        "-o", "StrictHostKeyChecking=no",
                        "-o", "UserKnownHostsFile=/dev/null",
                        "-o", "LogLevel=ERROR",
                        "-o", "ConnectTimeout=2",
                        "-p", String.valueOf(sshPort),
                        SSH_USER + "@localhost", "echo ready")
                        .redirectErrorStream(true);
                Process proc = pb.start();
                String out = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() == 0 && out.contains("ready")) return;
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new RuntimeException("SSH server did not become available");
    }

    private static void sshExecStatic(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost", command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes();
        assertThat(proc.waitFor()).as("SSH static failed: " + command).isZero();
    }

    @AfterAll
    static void stopContainer() {
        if (sshContainer != null) sshContainer.stop();
    }

    @Test
    void sshContainer_startsAndAcceptsConnections() {
        assertThat(sshContainer.isRunning()).isTrue();
        assertThat(sshPort).isGreaterThan(0);
    }

    @Test
    void canExecuteRemoteCommand() throws Exception {
        String output = sshExecCapture("echo hello");
        assertThat(output.trim()).isEqualTo("hello");
    }

    @Test
    void canCreateAndListRemoteDirectory() throws Exception {
        String dir = "/tmp/site-test/test-site";
        sshExec("mkdir -p " + dir);
        sshExec("echo 'test content' > " + dir + "/index.html");

        String listing = sshExecCapture("ls " + dir);
        assertThat(listing).contains("index.html");
    }

    @Test
    void canRemoveRemoteDirectory() throws Exception {
        String dir = "/tmp/site-test/to-delete";
        sshExec("mkdir -p " + dir + " && echo test > " + dir + "/file.txt");
        assertThat(sshExecCapture("ls " + dir)).contains("file.txt");

        sshExec("rm -rf " + dir);

        int exit = sshExecRaw("test -d " + dir);
        assertThat(exit).isNotZero();
    }

    @Test
    void stageAndSwap_replacesLiveDirectory() throws Exception {
        String base = "/tmp/site-test/swap-test";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // Create initial "live" v1
        sshExec("mkdir -p " + live + " && echo v1 > " + live + "/index.html");

        // Create "staging" v2
        sshExec("mkdir -p " + staging + " && echo v2 > " + staging + "/index.html");

        // Atomic swap (mirrors ReleaseSupport.swapRemoteSiteDir logic)
        sshExec("rm -rf " + old
                + " && mv " + live + " " + old
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        // v2 is now live
        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v2");
        // staging gone
        assertThat(sshExecRaw("test -d " + staging)).isNotZero();
        // old gone
        assertThat(sshExecRaw("test -d " + old)).isNotZero();
    }

    @Test
    void stageAndSwap_worksOnFirstDeploy() throws Exception {
        String base = "/tmp/site-test/first-deploy";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // Only staging exists (no previous live)
        sshExec("mkdir -p " + staging + " && echo v1 > " + staging + "/index.html");

        // Swap with || true for missing live dir
        sshExec("rm -rf " + old
                + " && (mv " + live + " " + old + " 2>/dev/null || true)"
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v1");
    }

    @Test
    void stageAndSwap_eliminatesStaleFiles() throws Exception {
        String base = "/tmp/site-test/stale-test";
        String live = base + "/release";
        String staging = live + ".staging";
        String old = live + ".old";

        // v1 has two files
        sshExec("mkdir -p " + live
                + " && echo v1 > " + live + "/index.html"
                + " && echo old > " + live + "/removed-page.html");

        // v2 has only one file (removed-page.html is gone)
        sshExec("mkdir -p " + staging + " && echo v2 > " + staging + "/index.html");

        // Swap
        sshExec("rm -rf " + old
                + " && mv " + live + " " + old
                + " && mv " + staging + " " + live
                + " && rm -rf " + old);

        // v2 live, stale file gone
        assertThat(sshExecCapture("cat " + live + "/index.html").trim())
                .isEqualTo("v2");
        assertThat(sshExecRaw("test -f " + live + "/removed-page.html"))
                .isNotZero();
    }

    // ── ReleaseSupport pure-logic tests ────────────────────────────

    @Test
    void validateRemotePath_acceptsValidPaths() throws Exception {
        // Should not throw for valid paths with sufficient depth
        ReleaseSupport.validateRemotePath("/srv/ike-site/my-project/release");
        ReleaseSupport.validateRemotePath("/srv/ike-site/my-project/snapshot/main");
        ReleaseSupport.validateRemotePath(
                "/srv/ike-site/my-project/checkpoint/7-checkpoint.20260301.1");
    }

    @Test
    void validateRemotePath_rejectsPathOutsideSiteBase() {
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/tmp/evil-path"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not start with");
    }

    @Test
    void validateRemotePath_rejectsTooShallowPath() {
        // Base path itself
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("too shallow");
        // Only one component deep (project name, no type)
        assertThatThrownBy(() ->
                ReleaseSupport.validateRemotePath("/srv/ike-site/project"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("too shallow");
    }

    @Test
    void siteDiskPath_constructsReleasePath() {
        String path = ReleaseSupport.siteDiskPath("my-proj", "release", null);
        assertThat(path).isEqualTo("/srv/ike-site/my-proj/release");
    }

    @Test
    void siteDiskPath_constructsSnapshotPath() {
        String path = ReleaseSupport.siteDiskPath("my-proj", "snapshot", "main");
        assertThat(path).isEqualTo("/srv/ike-site/my-proj/snapshot/main");
    }

    @Test
    void siteDiskPath_constructsCheckpointPath() {
        String path = ReleaseSupport.siteDiskPath(
                "my-proj", "checkpoint", "7-checkpoint.20260301.1");
        assertThat(path)
                .isEqualTo("/srv/ike-site/my-proj/checkpoint/7-checkpoint.20260301.1");
    }

    @Test
    void branchToSitePath_keepsSlashesAndSanitizes() {
        assertThat(ReleaseSupport.branchToSitePath("feature/my-work"))
                .isEqualTo("feature/my-work");
        assertThat(ReleaseSupport.branchToSitePath("main"))
                .isEqualTo("main");
        // Dangerous characters are replaced
        assertThat(ReleaseSupport.branchToSitePath("feature/bad;rm -rf /"))
                .doesNotContain(";");
    }

    @Test
    void siteStagingPath_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingPath("/srv/ike-site/proj/release"))
                .isEqualTo("/srv/ike-site/proj/release.staging");
    }

    @Test
    void siteStagingUrl_appendsSuffix() {
        assertThat(ReleaseSupport.siteStagingUrl("scpexe://proxy/srv/ike-site/proj/release"))
                .isEqualTo("scpexe://proxy/srv/ike-site/proj/release.staging");
    }

    // toPublicSiteUrl_convertsScpToHttp removed with DeploySiteDraftMojo (#398).

    // ── ReleaseSupport SSH methods against container ─────────────────

    @Test
    void cleanRemoteSiteDir_removesDirectory(@TempDir Path workDir)
            throws Exception {
        String dir = "/srv/ike-site/test-project/snapshot/feature-x";
        sshExec("mkdir -p " + dir + " && echo content > " + dir + "/index.html");
        assertThat(sshExecRaw("test -d " + dir)).isZero();

        ReleaseSupport.cleanRemoteSiteDir(
                workDir.toFile(),
                new network.ike.plugin.TestLog(),
                dir,
                sshPrefix());

        assertThat(sshExecRaw("test -d " + dir)).isNotZero();
    }

    @Test
    void swapRemoteSiteDir_replacesLiveWithStaging(@TempDir Path workDir)
            throws Exception {
        String base = "/srv/ike-site/test-project/release";
        String staging = base + ".staging";

        // Create v1 live and v2 staging
        sshExec("mkdir -p " + base + " && echo v1 > " + base + "/index.html");
        sshExec("mkdir -p " + staging + " && echo v2 > " + staging + "/index.html");

        ReleaseSupport.swapRemoteSiteDir(
                workDir.toFile(),
                new network.ike.plugin.TestLog(),
                base,
                sshPrefix());

        // v2 is now live
        assertThat(sshExecCapture("cat " + base + "/index.html").trim())
                .isEqualTo("v2");
        // staging is gone
        assertThat(sshExecRaw("test -d " + staging)).isNotZero();
        // .old is gone
        assertThat(sshExecRaw("test -d " + base + ".old")).isNotZero();
    }

    @Test
    void swapRemoteSiteDir_worksWithNoExistingLive(@TempDir Path workDir)
            throws Exception {
        String base = "/srv/ike-site/test-project/checkpoint/v1.0";
        String staging = base + ".staging";

        // Only staging exists — no previous live
        sshExec("rm -rf " + base + " " + staging);
        sshExec("mkdir -p " + staging + " && echo first > " + staging + "/index.html");

        ReleaseSupport.swapRemoteSiteDir(
                workDir.toFile(),
                new network.ike.plugin.TestLog(),
                base,
                sshPrefix());

        assertThat(sshExecCapture("cat " + base + "/index.html").trim())
                .isEqualTo("first");
    }

    // CleanSiteMojo / DeploySiteDraftMojo per-mojo tests removed with
    // the mojos themselves (#398 site convergence). The underlying
    // ReleaseSupport SSH primitives are still covered by the
    // cleanRemoteSiteDir / swapRemoteSiteDir tests above.

    // ── Test helpers ────────────────────────────────────────────────

    /**
     * Build the SSH command prefix array that routes to the container.
     * Used by the overloaded ReleaseSupport methods that accept
     * an explicit SSH prefix for testing.
     */
    private String[] sshPrefix() {
        return new String[]{
                "ssh",
                "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost"
        };
    }

    // initGitRepoWithPom / setField / createMojo helpers removed with
    // the per-mojo tests they supported (#398 site convergence). The
    // remaining tests exercise ReleaseSupport static methods directly
    // and don't need Mojo construction.

    // ── SSH helpers (key-based, no sshpass) ──────────────────────────

    private void sshExec(String command) throws Exception {
        int exit = sshExecRaw(command);
        assertThat(exit).as("SSH command failed: " + command).isZero();
    }

    private int sshExecRaw(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost",
                command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes();
        return proc.waitFor();
    }

    private String sshExecCapture(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost",
                command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit).as("SSH command failed: " + command).isZero();
        return output;
    }

}
