package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link UrlCascadeResolver} (IKE-Network/ike-issues#429).
 */
class UrlCascadeResolverTest {

    private static final String MANIFEST = """
            schema: 1
            terminal: true
            upstream:
              - groupId: network.ike.tooling
                artifactId: ike-tooling
                version-property: ike-tooling.version
            """;

    @Test
    void resolves_a_member_by_cloning_its_url(@TempDir Path tmp)
            throws Exception {
        Path remote = tmp.resolve("ike-docs-remote");
        Files.createDirectories(remote.resolve("src/main/cascade"));
        Files.writeString(
                remote.resolve("src/main/cascade/release-cascade.yaml"),
                MANIFEST, StandardCharsets.UTF_8);
        git(remote, "init", "-b", "main");
        git(remote, "config", "user.email", "test@example.com");
        git(remote, "config", "user.name", "Test");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "initial");

        UrlCascadeResolver resolver =
                new UrlCascadeResolver(tmp.resolve("clones"));
        CascadeEdge edge = new CascadeEdge("network.ike.docs", "ike-docs",
                "ike-docs", remote.toString(), null);

        ProjectCascade resolved = resolver.resolve(edge);

        assertThat(resolved.terminal()).isTrue();
        assertThat(resolved.upstream()).singleElement()
                .extracting(CascadeEdge::ga)
                .isEqualTo("network.ike.tooling:ike-tooling");
        // The clone landed under the clone directory, named by repo.
        assertThat(tmp.resolve("clones/ike-docs/.git")).exists();
    }

    @Test
    void refreshes_an_already_cloned_member(@TempDir Path tmp)
            throws Exception {
        Path remote = tmp.resolve("ike-docs-remote");
        Files.createDirectories(remote.resolve("src/main/cascade"));
        Files.writeString(
                remote.resolve("src/main/cascade/release-cascade.yaml"),
                MANIFEST, StandardCharsets.UTF_8);
        git(remote, "init", "-b", "main");
        git(remote, "config", "user.email", "test@example.com");
        git(remote, "config", "user.name", "Test");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "initial");

        UrlCascadeResolver resolver =
                new UrlCascadeResolver(tmp.resolve("clones"));
        CascadeEdge edge = new CascadeEdge("network.ike.docs", "ike-docs",
                "ike-docs", remote.toString(), null);

        // First call clones, second call hits the refresh path.
        resolver.resolve(edge);
        assertThat(resolver.resolve(edge).terminal()).isTrue();
    }

    @Test
    void edge_without_a_url_is_rejected(@TempDir Path tmp) {
        UrlCascadeResolver resolver = new UrlCascadeResolver(tmp);
        CascadeEdge noUrl = new CascadeEdge("network.ike.docs",
                "ike-docs", "ike-docs", null, null);

        assertThatThrownBy(() -> resolver.resolve(noUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no url");
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed");
        }
    }
}
