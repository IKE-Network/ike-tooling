package network.ike.plugin;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.Subproject;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleaseNotesSupport#formatWorkspaceChangelog(Manifest, Manifest,
 * ReleaseNotesSupport.SubprojectCommits)} — the per-subproject changelog that makes a
 * workspace checkpoint's notification reflect every subproject's code change, not just
 * the aggregator's own commits (IKE-Network/ike-issues#792).
 *
 * <p>The pure (manifest + commit-supplier) overload is exercised here so the diff logic
 * is covered without a git checkout; the git-backed overload is a thin adapter over it.
 */
class WorkspaceChangelogTest {

    private static final String KOMET_OLD = "1332de4" + "0".repeat(33);
    private static final String KOMET_NEW = "9eb7283" + "1".repeat(33);
    private static final String CORE_OLD = "aaaaaaa" + "0".repeat(33);
    private static final String CORE_NEW = "bbbbbbb" + "1".repeat(33);

    @Test
    void changedSubproject_emitsSectionWithCompareLinkAndRefs() {
        Manifest from = manifest("komet", KOMET_OLD);
        Manifest to = manifest("komet", KOMET_NEW);

        String md = ReleaseNotesSupport.formatWorkspaceChangelog(from, to,
                (name, sp, fromSha, toSha) -> List.of(
                        "fix(kview): persist STAMP edits\n\nFixes: ikmdev/komet-desktop#22"));

        assertThat(md)
                .contains("### komet — [`1332de4` → `9eb7283`]")
                .contains("https://github.com/ikmdev/komet/compare/"
                        + KOMET_OLD + "..." + KOMET_NEW)
                .contains("- fix(kview): persist STAMP edits (ikmdev/komet-desktop#22)");
    }

    @Test
    void unchangedPin_isOmitted() {
        Manifest from = manifest("komet", KOMET_NEW);
        Manifest to = manifest("komet", KOMET_NEW); // same pin

        String md = ReleaseNotesSupport.formatWorkspaceChangelog(from, to,
                (name, sp, fromSha, toSha) -> {
                    throw new AssertionError("supplier must not be called for an "
                            + "unchanged pin");
                });

        assertThat(md).isEmpty();
    }

    @Test
    void onlyMachineryCommits_omitsSection() {
        Manifest from = manifest("komet", KOMET_OLD);
        Manifest to = manifest("komet", KOMET_NEW);

        // "release:" matches the machinery filter, so formatChangelog yields
        // nothing substantive and the whole section is dropped.
        String md = ReleaseNotesSupport.formatWorkspaceChangelog(from, to,
                (name, sp, fromSha, toSha) -> List.of("release: cut 1.59.1"));

        assertThat(md).isEmpty();
    }

    @Test
    void newSubproject_hasNoPriorPin_soNoCompareLink() {
        Manifest from = manifest(); // empty — komet is new since the last checkpoint
        Manifest to = manifest("komet", KOMET_NEW);

        String md = ReleaseNotesSupport.formatWorkspaceChangelog(from, to,
                (name, sp, fromSha, toSha) -> {
                    assertThat(fromSha).as("a new subproject has no prior pin").isNull();
                    return List.of("feat: initial komet import\n\nRefs: ikmdev/komet#1");
                });

        assertThat(md)
                .contains("### komet\n")          // header, but...
                .doesNotContain("### komet — [")   // ...no compare link
                .contains("- feat: initial komet import (ikmdev/komet#1)");
    }

    @Test
    void sectionsFollowManifestOrder() {
        Manifest from = manifest("komet", KOMET_OLD, "tinkar-core", CORE_OLD);
        Manifest to = manifest("komet", KOMET_NEW, "tinkar-core", CORE_NEW);

        String md = ReleaseNotesSupport.formatWorkspaceChangelog(from, to,
                (name, sp, fromSha, toSha) -> List.of(
                        "feat: " + name + " change\n\nRefs: ikmdev/" + name + "#1"));

        assertThat(md.indexOf("### komet"))
                .as("komet precedes tinkar-core, matching manifest order")
                .isLessThan(md.indexOf("### tinkar-core"))
                .isNotNegative();
    }

    @Test
    void nullToManifest_returnsEmpty() {
        assertThat(ReleaseNotesSupport.formatWorkspaceChangelog(
                manifest("komet", KOMET_OLD), null,
                (name, sp, fromSha, toSha) -> List.of("x"))).isEmpty();
    }

    @Test
    void nullFromManifest_treatsEverySubprojectAsNew() {
        Manifest to = manifest("komet", KOMET_NEW);

        String md = ReleaseNotesSupport.formatWorkspaceChangelog(null, to,
                (name, sp, fromSha, toSha) -> {
                    assertThat(fromSha).isNull();
                    return List.of("feat: thing\n\nRefs: ikmdev/komet#2");
                });

        assertThat(md).contains("### komet").contains("- feat: thing (ikmdev/komet#2)");
    }

    @Test
    void compareUrl_stripsDotGitAndHandlesMissingPins() {
        assertThat(ReleaseNotesSupport.compareUrl(
                "https://github.com/ikmdev/komet.git", "aaa", "bbb"))
                .isEqualTo("https://github.com/ikmdev/komet/compare/aaa...bbb");
        assertThat(ReleaseNotesSupport.compareUrl(
                "https://github.com/ikmdev/komet.git", null, "bbb")).isNull();
        assertThat(ReleaseNotesSupport.compareUrl(null, "aaa", "bbb")).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Build a manifest from alternating {@code name, sha} pairs. */
    private static Manifest manifest(String... nameShaPairs) {
        StringBuilder yaml = new StringBuilder("schema-version: \"1.0\"\nsubprojects:\n");
        for (int i = 0; i < nameShaPairs.length; i += 2) {
            String name = nameShaPairs[i];
            String sha = nameShaPairs[i + 1];
            yaml.append("  ").append(name).append(":\n")
                .append("    repo: https://github.com/ikmdev/").append(name).append(".git\n")
                .append("    branch: main\n")
                .append("    sha: \"").append(sha).append("\"\n")
                .append("    version: 1.0.0-SNAPSHOT\n");
        }
        // With no pairs the YAML ends at "subprojects:" (a null value), which
        // ManifestReader reads as an empty subproject map.
        return ManifestReader.read(new StringReader(yaml.toString()));
    }

    /** Sanity: the helper actually pins shas the way the diff logic reads them. */
    @Test
    void helperPinsShas() {
        Subproject sp = manifest("komet", KOMET_NEW).subprojects().get("komet");
        assertThat(sp.sha()).isEqualTo(KOMET_NEW);
        assertThat(sp.repo()).isEqualTo("https://github.com/ikmdev/komet.git");
    }
}
