package network.ike.plugin;

import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.CascadeRepo;
import network.ike.workspace.cascade.ProjectCascade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IkeReleaseCascadeMojo} — specifically the
 * stale-upstream-pin detection that drives the release-pending
 * decision (IKE-Network/ike-issues#468).
 *
 * <p>{@code walkOne} itself shells out to {@code mvn} and to {@code git},
 * so it can't be exercised end-to-end as a unit test; the per-decision
 * helpers ({@link IkeReleaseCascadeMojo#stalePinsFor},
 * {@link IkeReleaseCascadeMojo#latestReleaseTag},
 * {@link IkeReleaseCascadeMojo#meaningfulCommitsSinceTag}) are
 * package-visible so each can be tested directly.
 */
class IkeReleaseCascadeMojoTest {

    // ── Reusable cascade edge fixtures ──────────────────────────────

    private static final CascadeEdge TOOLING = new CascadeEdge(
            "network.ike.tooling", "ike-tooling", "ike-tooling",
            "https://github.com/IKE-Network/ike-tooling.git",
            "ike-tooling.version");

    private static final CascadeEdge DOCS = new CascadeEdge(
            "network.ike.docs", "ike-docs", "ike-docs",
            "https://github.com/IKE-Network/ike-docs.git",
            "ike-docs.version");

    private static final CascadeEdge EXTENSION = new CascadeEdge(
            "network.ike.tooling", "ike-workspace-extension",
            "ike-workspace-extension",
            "https://github.com/IKE-Network/ike-workspace-extension.git",
            "ike-workspace-extension.version");

    // ── stalePinsFor: head member with no upstream edges ────────────

    @Test
    void stalePinsFor_headMember_returnsEmpty(@TempDir Path tmp)
            throws IOException {
        // ike-tooling: head — no upstream edges.
        Path siblings = tmp.resolve("siblings");
        Path toolingDir = siblings.resolve("ike-tooling");
        Files.createDirectories(toolingDir);
        writePom(toolingDir,
                "<project><artifactId>ike-tooling</artifactId>"
                + "<version>193-SNAPSHOT</version></project>");
        CascadeRepo head = node(TOOLING,
                new ProjectCascade(1, true, List.of(), true, List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                head, toolingDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── stalePinsFor: pin matches upstream's latest tag ─────────────

    @Test
    void stalePinsFor_pinMatchesUpstreamTag_returnsEmpty(@TempDir Path tmp)
            throws Exception {
        Path siblings = tmp.resolve("siblings");
        Path toolingDir = createGitRepoWithTag(siblings, "ike-tooling",
                "v192");
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        writePom(docsDir,
                "<project><artifactId>ike-docs</artifactId>"
                + "<version>49-SNAPSHOT</version>"
                + "<properties><ike-tooling.version>192</ike-tooling.version>"
                + "</properties></project>");
        // Suppress unused warnings.
        assertThat(toolingDir).exists();

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false,
                        List.of(TOOLING), true, List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── stalePinsFor: pin older than upstream's latest tag ──────────

    @Test
    void stalePinsFor_pinOlderThanUpstreamTag_returnsBumpDescription(
            @TempDir Path tmp) throws Exception {
        Path siblings = tmp.resolve("siblings");
        createGitRepoWithTag(siblings, "ike-tooling", "v192");
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        writePom(docsDir,
                "<project><artifactId>ike-docs</artifactId>"
                + "<version>49-SNAPSHOT</version>"
                + "<properties><ike-tooling.version>191</ike-tooling.version>"
                + "</properties></project>");

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false,
                        List.of(TOOLING), true, List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).containsExactly(
                "ike-tooling.version  (191 → 192)");
    }

    // ── stalePinsFor: multiple upstreams, mixed states ──────────────

    @Test
    void stalePinsFor_mixedUpstreamPinStates_returnsOnlyStale(
            @TempDir Path tmp) throws Exception {
        // Reproduce the #468 two-heads-converging-on-terminal shape:
        // ike-platform has THREE upstreams: ike-tooling (at v192, pin
        // up-to-date), ike-docs (at v49, pin up-to-date), and
        // ike-workspace-extension (at v3, pin still at 2 — stale).
        // Only the workspace-extension pin should appear.
        Path siblings = tmp.resolve("siblings");
        createGitRepoWithTag(siblings, "ike-tooling", "v192");
        createGitRepoWithTag(siblings, "ike-docs", "v49");
        createGitRepoWithTag(siblings, "ike-workspace-extension", "v3");

        Path platformDir = siblings.resolve("ike-platform");
        Files.createDirectories(platformDir);
        writePom(platformDir,
                "<project><artifactId>ike-platform</artifactId>"
                + "<version>79-SNAPSHOT</version>"
                + "<properties>"
                + "<ike-tooling.version>192</ike-tooling.version>"
                + "<ike-docs.version>49</ike-docs.version>"
                + "<ike-workspace-extension.version>2</ike-workspace-extension.version>"
                + "</properties></project>");

        CascadeRepo platform = node(
                new CascadeEdge("network.ike.platform", "ike-platform",
                        "ike-platform", null, null),
                new ProjectCascade(1, false,
                        List.of(TOOLING, DOCS, EXTENSION),
                        true, List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                platform, platformDir.toFile(), siblings.toFile());

        assertThat(stale).containsExactly(
                "ike-workspace-extension.version  (2 → 3)");
    }

    // ── stalePinsFor: upstream has no tags (never released) ─────────

    @Test
    void stalePinsFor_upstreamHasNoTags_returnsEmpty(@TempDir Path tmp)
            throws Exception {
        Path siblings = tmp.resolve("siblings");
        // Create the upstream checkout with .git but no tags.
        Path toolingDir = siblings.resolve("ike-tooling");
        Files.createDirectories(toolingDir);
        runGit(toolingDir, "init", "-q", "-b", "main");
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        writePom(docsDir,
                "<project><artifactId>ike-docs</artifactId>"
                + "<version>49-SNAPSHOT</version>"
                + "<properties><ike-tooling.version>191</ike-tooling.version>"
                + "</properties></project>");
        assertThat(toolingDir).exists();

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false, List.of(TOOLING), true,
                        List.of()));

        // Upstream has no released tag — walker cannot tell if the pin
        // is stale, so it reports nothing rather than guessing. The
        // upstream itself will be release-pending (its own
        // meaningfulCommitsSinceTag handles first-release) and any
        // resulting tag will get picked up on the next pass.
        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── stalePinsFor: pin is a ${...} placeholder ───────────────────

    @Test
    void stalePinsFor_unresolvedPropertyPlaceholder_isSkipped(
            @TempDir Path tmp) throws Exception {
        Path siblings = tmp.resolve("siblings");
        createGitRepoWithTag(siblings, "ike-tooling", "v192");
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        writePom(docsDir,
                "<project><artifactId>ike-docs</artifactId>"
                + "<version>49-SNAPSHOT</version>"
                + "<properties><ike-tooling.version>${parent.tooling.version}</ike-tooling.version>"
                + "</properties></project>");

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false, List.of(TOOLING), true,
                        List.of()));

        // ${...} placeholder is a parent-driven indirection — not
        // something this walker should attempt to bump. Skipped.
        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── stalePinsFor: missing upstream checkout ─────────────────────

    @Test
    void stalePinsFor_missingUpstreamCheckout_isSkipped(@TempDir Path tmp)
            throws Exception {
        // siblings/ does NOT contain ike-tooling. The walker should
        // not raise; the missing-checkout error is reported elsewhere
        // when the walker tries to iterate that member.
        Path siblings = tmp.resolve("siblings");
        Files.createDirectories(siblings);
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        writePom(docsDir,
                "<project><artifactId>ike-docs</artifactId>"
                + "<version>49-SNAPSHOT</version>"
                + "<properties><ike-tooling.version>191</ike-tooling.version>"
                + "</properties></project>");

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false, List.of(TOOLING), true,
                        List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── stalePinsFor: pom file is absent ────────────────────────────

    @Test
    void stalePinsFor_pomAbsent_returnsEmpty(@TempDir Path tmp)
            throws Exception {
        Path siblings = tmp.resolve("siblings");
        createGitRepoWithTag(siblings, "ike-tooling", "v192");
        Path docsDir = siblings.resolve("ike-docs");
        Files.createDirectories(docsDir);
        // No pom.xml.

        CascadeRepo docs = node(DOCS,
                new ProjectCascade(1, false, List.of(TOOLING), true,
                        List.of()));

        List<String> stale = IkeReleaseCascadeMojo.stalePinsFor(
                docs, docsDir.toFile(), siblings.toFile());

        assertThat(stale).isEmpty();
    }

    // ── latestReleaseTag: picks the newest v-prefixed tag ───────────

    @Test
    void latestReleaseTag_picksNewestVPrefixed(@TempDir Path tmp)
            throws Exception {
        Path repo = createGitRepoWithTag(tmp, "ike-tooling", "v9");
        runGit(repo, "tag", "v10");
        runGit(repo, "tag", "v192");
        runGit(repo, "tag", "not-a-release-tag");

        assertThat(IkeReleaseCascadeMojo.latestReleaseTag(repo.toFile()))
                .isEqualTo("v192");
    }

    @Test
    void latestReleaseTag_noTags_returnsNull(@TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("ike-tooling");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q", "-b", "main");

        assertThat(IkeReleaseCascadeMojo.latestReleaseTag(repo.toFile()))
                .isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static CascadeRepo node(CascadeEdge id,
                                    ProjectCascade cascade) {
        return new CascadeRepo(id.groupId(), id.artifactId(),
                id.repo(), id.url(), cascade);
    }

    private static void writePom(Path dir, String xml) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), xml);
    }

    /**
     * Creates a git repo at {@code siblings/name}, makes an initial
     * commit with a placeholder file, and tags it.
     *
     * <p>Uses {@code -c core.hooksPath=/dev/null} to neutralize any
     * user-global hooks (e.g., the ike-dev prepare-commit-msg hook
     * that rejects commits with no staged content) so tests don't
     * depend on the developer's local git config.
     */
    private static Path createGitRepoWithTag(Path siblings, String name,
                                             String tag)
            throws Exception {
        Path dir = siblings.resolve(name);
        Files.createDirectories(dir);
        runGit(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve(".init"), "");
        runGit(dir, "add", ".init");
        runGit(dir, "-c", "user.email=t@t", "-c", "user.name=t",
                "-c", "core.hooksPath=/dev/null",
                "commit", "-q", "-m", "init");
        runGit(dir, "tag", tag);
        return dir;
    }

    private static void runGit(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        for (String a : args) {
            cmd.add(a);
        }
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true).start();
        int rc = p.waitFor();
        if (rc != 0) {
            throw new RuntimeException("git " + String.join(" ", cmd)
                    + " exit=" + rc + "\n"
                    + new String(p.getInputStream().readAllBytes()));
        }
    }
}
