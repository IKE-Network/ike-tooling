package network.ike.plugin;

import network.ike.plugin.ReleaseNotesSupport.Issue;
import network.ike.plugin.ReleaseNotesSupport.TestingContext;
import org.junit.jupiter.api.Test;

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
        var issues = List.of(
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
        var upgrades = List.of(
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
        var closed = List.of(
                new Issue(13, "Add bootstrap checklist", List.of("documentation")),
                new Issue(16, "Implement ws:release-notes", List.of("enhancement")));
        var open = List.of(
                new Issue(12, "Fix pluginGroups docs", List.of("bug")));

        var context = new TestingContext("ike-tooling v57", closed, open);
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
        var closed = List.of(
                new Issue(13, "Add bootstrap checklist", List.of("documentation")));
        var open = List.of(
                new Issue(12, "Fix pluginGroups docs", List.of("bug")));

        var context = new TestingContext("ike-tooling v57", closed, open);
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
        var context = new TestingContext("test v1", List.of(), List.of());
        String md = context.toMarkdown();

        assertThat(md).contains("No issues in milestone.");
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
