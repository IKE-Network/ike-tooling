package network.ike.plugin;

import network.ike.plugin.ReleaseNotesSupport.Issue;
import network.ike.plugin.ReleaseNotesSupport.TestingContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleaseNotesSupport} — the shared release notes
 * engine used by both {@code ws:release-notes} and {@code ike:release}.
 *
 * <p>Tests pure formatting logic. GitHub API integration is tested
 * via the live {@code ws:release-notes} goal (requires network).
 */
class ReleaseNotesIntegrationTest {

    @Test
    void milestone_name_convention_matches_release() {
        // ReleaseMojo constructs: projectId + " v" + releaseVersion
        String projectId = "ike-tooling";
        String releaseVersion = "57";
        String milestoneName = projectId + " v" + releaseVersion;

        assertThat(milestoneName).isEqualTo("ike-tooling v57");
    }

    @Test
    void formatNotes_produces_valid_markdown_for_gh_release() {
        List<Issue> issues = List.of(
                new Issue(12, "Fix pluginGroups prerequisite docs", List.of("bug", "tooling")),
                new Issue(16, "Implement ws:release-notes goal", List.of("enhancement", "tooling")),
                new Issue(13, "Add bootstrap checklist", List.of("documentation", "tooling")));

        String notes = ReleaseNotesSupport.formatNotes("ike-tooling v57", issues);

        // Valid markdown heading
        assertThat(notes).startsWith("## ike-tooling v57\n");

        // Bug under Fixes
        assertThat(notes).contains("### Fixes\n\n- Fix pluginGroups");

        // Enhancement under Enhancements
        assertThat(notes).contains("### Enhancements\n\n- Implement ws:release-notes");

        // Documentation under Internal
        assertThat(notes).contains("### Internal\n\n- Add bootstrap checklist");
    }

    @Test
    void formatNotes_empty_milestone_suitable_for_gh_release() {
        String notes = ReleaseNotesSupport.formatNotes("ike-tooling v57", List.of());

        // Should still produce valid content, not null
        assertThat(notes).isNotNull();
        assertThat(notes).contains("ike-tooling v57");
    }

    @Test
    void formatNotes_renders_foundation_upgrades_section() {
        // #706 — a cascade-only rebuild (no issues) announces what it was
        // rebuilt against rather than "No closed issues".
        List<CascadeBump> upgrades = List.of(
                new CascadeBump("network.ike.tooling", "ike-tooling", "221", "222"),
                new CascadeBump("network.ike.docs", "ike-docs", "75", "76"));

        String notes = ReleaseNotesSupport.formatNotes("ike-docs v76",
                List.of(), upgrades);

        assertThat(notes).contains("### Foundation upgrades");
        assertThat(notes).contains("Rebuilt against ike-tooling 222, ike-docs 76.");
        assertThat(notes).contains("- `network.ike.tooling:ike-tooling` 221 → 222");
        assertThat(notes).contains("- `network.ike.docs:ike-docs` 75 → 76");
        // The "no changes" placeholder must not appear when there are
        // real upgrades to report.
        assertThat(notes).doesNotContain("No closed issues");
    }

    @Test
    void formatNotes_still_announces_no_changes_when_truly_empty() {
        String notes = ReleaseNotesSupport.formatNotes("ike-docs v76",
                List.of(), List.of());

        assertThat(notes).contains("No closed issues in this milestone.");
        assertThat(notes).doesNotContain("### Foundation upgrades");
    }

    @Test
    void testingContext_toMarkdown_categorizes_issues() {
        List<Issue> closed = List.of(
                new Issue(13, "Add bootstrap checklist", List.of("documentation")),
                new Issue(16, "Implement ws:release-notes", List.of("enhancement")));
        List<Issue> open = List.of(
                new Issue(12, "Fix pluginGroups docs", List.of("bug")));

        TestingContext context = new TestingContext("ike-tooling v57", closed, open);
        String md = context.toMarkdown();

        assertThat(md).contains("## Testing Context: ike-tooling v57");
        assertThat(md).contains("### Ready to Test");
        assertThat(md).contains("- Add bootstrap checklist (#13)");
        assertThat(md).contains("- Implement ws:release-notes (#16)");
        assertThat(md).contains("### In Progress");
        assertThat(md).contains("- Fix pluginGroups docs (#12)");
    }

    @Test
    void testingContext_toYaml_produces_valid_yaml() {
        List<Issue> closed = List.of(
                new Issue(13, "Add bootstrap checklist", List.of("documentation")));
        List<Issue> open = List.of(
                new Issue(12, "Fix pluginGroups docs", List.of("bug")));

        TestingContext context = new TestingContext("ike-tooling v57", closed, open);
        String yaml = context.toYaml("  ");

        assertThat(yaml).contains("testing-context:");
        assertThat(yaml).contains("milestone: \"ike-tooling v57\"");
        assertThat(yaml).contains("ready-to-test:");
        assertThat(yaml).contains("number: 13");
        assertThat(yaml).contains("in-progress:");
        assertThat(yaml).contains("number: 12");
    }

    @Test
    void testingContext_empty_produces_no_issues_message() {
        TestingContext context = new TestingContext("test v1", List.of(), List.of());
        String md = context.toMarkdown();

        assertThat(md).contains("No issues in milestone.");
    }

    @Test
    void parseIssueRefs_preserves_full_owner_repo_form() {
        String msg = """
                feat: do a thing (#705)

                Body text.

                Fixes IKE-Network/ike-issues#705
                Refs ikmdev/komet-desktop#12
                """;
        assertThat(ReleaseNotesSupport.parseIssueRefs(msg))
                .containsExactly("IKE-Network/ike-issues#705",
                        "ikmdev/komet-desktop#12");
    }

    @Test
    void parseIssueRefs_keeps_bare_refs_bare_and_dedupes() {
        String msg = """
                fix: something

                Fixes #42
                Closes #42
                """;
        // Bare #N stays bare (ambiguous repo, not guessed) and dedupes.
        assertThat(ReleaseNotesSupport.parseIssueRefs(msg))
                .containsExactly("#42");
    }

    @Test
    void isMachineryCommit_filters_release_cadence() {
        assertThat(ReleaseNotesSupport.isMachineryCommit("release: align upstream cascade")).isTrue();
        assertThat(ReleaseNotesSupport.isMachineryCommit("merge: release/223 to main")).isTrue();
        assertThat(ReleaseNotesSupport.isMachineryCommit("post-release: bump to 224-SNAPSHOT")).isTrue();
        assertThat(ReleaseNotesSupport.isMachineryCommit("Bump ike-parent 1 -> 2")).isTrue();
        assertThat(ReleaseNotesSupport.isMachineryCommit("feat: a real feature (#1)")).isFalse();
        assertThat(ReleaseNotesSupport.isMachineryCommit("docs: update guide")).isFalse();
    }

    @Test
    void formatChangelog_annotates_with_full_form_and_strips_bare_subject_refs() {
        List<String> commits = List.of(
                """
                feat: coherence gate + surfacing (#705, #706, #708)

                Fixes IKE-Network/ike-issues#705
                Fixes IKE-Network/ike-issues#706
                Refs IKE-Network/ike-issues#708
                """,
                "release: align upstream cascade — ike-tooling 222->223",
                """
                docs: cross-repo note

                Refs ikmdev/komet-desktop#12
                """);

        String log = ReleaseNotesSupport.formatChangelog(commits);

        // Machinery filtered out.
        assertThat(log).doesNotContain("align upstream cascade");
        // Trailing bare "(#705, #706, #708)" stripped; full-form appended.
        assertThat(log).contains(
                "- feat: coherence gate + surfacing "
                + "(IKE-Network/ike-issues#705, IKE-Network/ike-issues#706, "
                + "IKE-Network/ike-issues#708)");
        assertThat(log).doesNotContain("(#705, #706, #708)");
        // Cross-repo ref preserved in its own repo.
        assertThat(log).contains("- docs: cross-repo note (ikmdev/komet-desktop#12)");
    }

    @Test
    void formatChangelog_empty_when_only_machinery() {
        List<String> commits = List.of(
                "release: cut v1",
                "post-release: bump",
                "merge: release/1 to main");
        assertThat(ReleaseNotesSupport.formatChangelog(commits)).isEmpty();
    }

    @Test
    void commitMessagesBetween_reads_full_messages_then_changelog(@TempDir Path repo)
            throws Exception {
        // Real git, hermetic (no host config) — TESTING.md mock-last.
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("f"), "1");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m",
                "feat: first thing (#1)\n\nFixes IKE-Network/ike-issues#1");
        git(repo, "tag", "v1");
        Files.writeString(repo.resolve("f"), "2");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "release: align upstream cascade");
        Files.writeString(repo.resolve("f"), "3");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m",
                "docs: cross-repo note\n\nRefs ikmdev/komet-desktop#9");
        git(repo, "tag", "v2");

        List<String> msgs = ReleaseNotesSupport.commitMessagesBetween(
                repo.toFile(), "v1", "v2");
        assertThat(msgs).hasSize(2);  // the machinery commit is still here…

        String log = ReleaseNotesSupport.formatChangelog(msgs);
        // …but formatChangelog filters it and links the cross-repo ref.
        assertThat(log).doesNotContain("align upstream cascade");
        assertThat(log).contains("- docs: cross-repo note (ikmdev/komet-desktop#9)");
        assertThat(log).doesNotContain("(#1)");  // bare subject ref stripped
    }

    @Test
    void cascadeTopicLabel_uses_server_local_date_not_utc() {
        // 2026-06-20 05:30 UTC == 2026-06-19 22:30 in Los Angeles (UTC-7 PDT).
        // The label must reflect the release server's OWN day (the 19th),
        // not UTC's (the 20th) — the cross-timezone point.
        java.time.Instant instant = java.time.Instant.parse("2026-06-20T05:30:00Z");
        java.time.ZoneId la = java.time.ZoneId.of("America/Los_Angeles");
        String label = ReleaseNotesSupport.cascadeTopicLabel(instant, la);

        assertThat(label).startsWith("2026-06-19 ");   // server-local day
        assertThat(label).doesNotContain("2026-06-20"); // not UTC's day
        assertThat(label.trim()).isNotEqualTo("2026-06-19"); // a zone label is appended
    }

    @Test
    void cascadeTopic_names_round_trip() {
        String label = "2026-06-19 PDT";

        String inProgress = ReleaseNotesSupport.inProgressCascadeTopic(label);
        assertThat(inProgress).isEqualTo("2026-06-19 PDT Release Cascade — in progress");

        // The terminal recovers the label from the topic it found…
        assertThat(ReleaseNotesSupport.cascadeLabelOf(inProgress)).isEqualTo(label);
        // …and builds the completed name from it.
        assertThat(ReleaseNotesSupport.completedCascadeTopic(label, "66"))
                .isEqualTo("2026-06-19 PDT ike-parent Release Cascade v66");
    }

    @Test
    void cascadeLabelOf_rejects_non_in_progress_topics() {
        assertThat(ReleaseNotesSupport.cascadeLabelOf(
                "2026-06-19 PDT ike-parent Release Cascade v66")).isNull();
        assertThat(ReleaseNotesSupport.cascadeLabelOf("ike-tooling v223")).isNull();
        assertThat(ReleaseNotesSupport.cascadeLabelOf(null)).isNull();
    }

    @Test
    void cascadeMetaEnv_is_shell_sourceable() {
        String env = ReleaseNotesSupport.cascadeMetaEnv("2026-06-19 PDT");
        assertThat(env).contains("CASCADE_LABEL='2026-06-19 PDT'\n");
        assertThat(env).contains(
                "CASCADE_TOPIC_INPROGRESS='2026-06-19 PDT Release Cascade — in progress'\n");
    }

    @Test
    void formatCascadeSummary_lists_members_under_parent_version() {
        List<ReleaseNotesSupport.CascadeMember> members = List.of(
                new ReleaseNotesSupport.CascadeMember("ike-tooling", "223"),
                new ReleaseNotesSupport.CascadeMember("ike-docs", "77"),
                new ReleaseNotesSupport.CascadeMember("ike-platform", "66"));

        String summary = ReleaseNotesSupport.formatCascadeSummary("66", members);

        assertThat(summary).contains("ike-parent v66");
        assertThat(summary).contains("- ike-tooling v223");
        assertThat(summary).contains("- ike-docs v77");
        assertThat(summary).contains("- ike-platform v66");
    }

    /** Run git in {@code dir} with a hermetic env (no host/global config). */
    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 5];
        cmd[0] = "git";
        cmd[1] = "-c"; cmd[2] = "user.email=test@ike.example";
        cmd[3] = "-c"; cmd[4] = "user.name=Test";
        System.arraycopy(args, 0, cmd, 5, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + code + "):\n" + out);
        }
    }

    @Test
    void generate_returns_null_for_nonexistent_milestone() throws Exception {
        // Uses a repo that exists but a milestone that doesn't
        // This test requires network — skip gracefully if offline
        try {
            String result = ReleaseNotesSupport.generate(
                    "IKE-Network/ike-issues",
                    "nonexistent-milestone-xyz-999",
                    null);
            assertThat(result).isNull();
        } catch (Exception e) {
            // Network unavailable — skip
            if (!e.getMessage().contains("GitHub API")) {
                throw e;
            }
        }
    }
}
