package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SiblingRepositoryKeyResolver} — coordinate-to-
 * repository keying by walking sibling git checkouts
 * (IKE-Network/ike-issues#496 part C).
 */
class SiblingRepositoryKeyResolverTest {

    @Test
    void resolves_root_coordinate_to_root_repo_key(@TempDir Path tmp)
            throws IOException {
        // siblings/ike-tooling/.git
        // siblings/ike-tooling/pom.xml — declares <scm> and coords
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        writePom(repo, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("network.ike.tooling", "ike-tooling"))
                .get()
                .extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void subproject_coordinate_inherits_root_repo_key(@TempDir Path tmp)
            throws IOException {
        // siblings/ike-tooling/.git
        // siblings/ike-tooling/pom.xml — <scm> declared
        // siblings/ike-tooling/ike-build-standards/pom.xml — no <scm>
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        writePom(repo, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");
        Path sub = repo.resolve("ike-build-standards");
        Files.createDirectories(sub);
        writeSubPom(sub, "network.ike.tooling", "ike-build-standards");

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        RepositoryKey toolingKey = resolver.resolve(
                "network.ike.tooling", "ike-tooling").orElseThrow();
        RepositoryKey buildStdKey = resolver.resolve(
                "network.ike.tooling", "ike-build-standards").orElseThrow();

        // Both coordinates collapse onto the same repository key.
        assertThat(buildStdKey).isEqualTo(toolingKey);
        assertThat(buildStdKey.url())
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void separate_git_repos_get_distinct_keys(@TempDir Path tmp)
            throws IOException {
        // Two independent repos under siblings/.
        Path siblings = tmp.resolve("siblings");

        Path tooling = siblings.resolve("ike-tooling");
        Files.createDirectories(tooling.resolve(".git"));
        writePom(tooling, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");

        Path docs = siblings.resolve("ike-docs");
        Files.createDirectories(docs.resolve(".git"));
        writePom(docs, "network.ike.docs", "ike-docs",
                "https://github.com/IKE-Network/ike-docs");

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("network.ike.tooling", "ike-tooling"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
        assertThat(resolver.resolve("network.ike.docs", "ike-docs"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/ike-docs");
    }

    @Test
    void nested_git_repo_uses_its_own_scm_not_outer(@TempDir Path tmp)
            throws IOException {
        // siblings/workspace-example/.git
        // siblings/workspace-example/pom.xml — <scm> for workspace-example
        // siblings/workspace-example/doc-example/.git ← inner repo
        // siblings/workspace-example/doc-example/pom.xml — <scm> for doc-example
        //
        // doc-example must resolve to its own <scm>, not workspace-example's,
        // because the nearer .git boundary is the inner one.
        Path siblings = tmp.resolve("siblings");

        Path outer = siblings.resolve("workspace-example");
        Files.createDirectories(outer.resolve(".git"));
        writePom(outer, "network.ike.examples", "workspace-example",
                "https://github.com/IKE-Network/workspace-example");

        Path inner = outer.resolve("doc-example");
        Files.createDirectories(inner.resolve(".git"));
        writePom(inner, "network.ike.examples", "doc-example",
                "https://github.com/IKE-Network/doc-example");

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve(
                "network.ike.examples", "workspace-example"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/workspace-example");
        assertThat(resolver.resolve(
                "network.ike.examples", "doc-example"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/doc-example");
    }

    @Test
    void unknown_coordinate_returns_empty(@TempDir Path tmp)
            throws IOException {
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        writePom(repo, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("org.example", "nowhere")).isEmpty();
    }

    @Test
    void missing_baseDir_resolves_to_empty(@TempDir Path tmp) {
        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(tmp.resolve("absent"));

        assertThat(resolver.resolve("network.ike.tooling", "ike-tooling"))
                .isEmpty();
    }

    @Test
    void pom_with_no_scm_at_repo_root_yields_empty(@TempDir Path tmp)
            throws IOException {
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        // Root POM declares coordinates but no <scm>.
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.tooling</groupId>
                    <artifactId>ike-tooling</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("network.ike.tooling", "ike-tooling"))
                .isEmpty();
    }

    @Test
    void scm_connection_falls_back_when_url_absent(@TempDir Path tmp)
            throws IOException {
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.tooling</groupId>
                    <artifactId>ike-tooling</artifactId>
                    <version>1-SNAPSHOT</version>
                    <scm>
                        <connection>scm:git:git@github.com:IKE-Network/ike-tooling.git</connection>
                    </scm>
                </project>
                """, StandardCharsets.UTF_8);

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("network.ike.tooling", "ike-tooling"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void subproject_inherits_groupId_from_parent_when_absent(
            @TempDir Path tmp) throws IOException {
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        writePom(repo, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");
        // Subproject omits <groupId> and inherits from <parent>.
        Path sub = repo.resolve("ike-build-standards");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-tooling</artifactId>
                        <version>1-SNAPSHOT</version>
                    </parent>
                    <artifactId>ike-build-standards</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve(
                "network.ike.tooling", "ike-build-standards"))
                .get().extracting(RepositoryKey::url)
                .isEqualTo("https://github.com/IKE-Network/ike-tooling");
    }

    @Test
    void skips_target_and_build_directories(@TempDir Path tmp)
            throws IOException {
        // A pom.xml under target/ should not be indexed — these are
        // build outputs that may contain stale copies of POMs.
        Path siblings = tmp.resolve("siblings");
        Path repo = siblings.resolve("ike-tooling");
        Files.createDirectories(repo.resolve(".git"));
        writePom(repo, "network.ike.tooling", "ike-tooling",
                "https://github.com/IKE-Network/ike-tooling");

        Path targetSubmodule = repo.resolve("target/effective-poms");
        Files.createDirectories(targetSubmodule);
        Files.writeString(targetSubmodule.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>stale-target-pom</artifactId>
                    <version>1</version>
                </project>
                """, StandardCharsets.UTF_8);

        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(siblings);

        assertThat(resolver.resolve("example", "stale-target-pom"))
                .isEmpty();
    }

    @Test
    void null_baseDir_rejected() {
        assertThatThrownBy(() -> new SiblingRepositoryKeyResolver(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_coordinate_resolves_to_empty(@TempDir Path tmp) {
        SiblingRepositoryKeyResolver resolver =
                new SiblingRepositoryKeyResolver(tmp);

        assertThat(resolver.resolve(null, "x")).isEmpty();
        assertThat(resolver.resolve("x", null)).isEmpty();
    }

    // ── Test helpers ────────────────────────────────────────────────

    private static void writePom(Path dir, String groupId,
                                 String artifactId, String scmUrl)
            throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1-SNAPSHOT</version>
                    <scm>
                        <url>%s</url>
                    </scm>
                </project>
                """.formatted(groupId, artifactId, scmUrl),
                StandardCharsets.UTF_8);
    }

    private static void writeSubPom(Path dir, String groupId,
                                    String artifactId) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """.formatted(groupId, artifactId),
                StandardCharsets.UTF_8);
    }
}
