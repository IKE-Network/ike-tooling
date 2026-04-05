package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import network.ike.plugin.ws.WsReleaseNotesMojo.Issue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the release notes formatting logic in
 * {@link WsReleaseNotesMojo}.
 *
 * <p>Tests the pure formatting functions (categorization by label,
 * sorting, markdown output) without requiring GitHub API access.
 */
class WsReleaseNotesFormattingTest {

    @Test
    void categorizes_bugs_as_fixes() throws Exception {
        var issues = List.of(
                issue(1, "Fix null pointer in init", List.of("bug", "tooling")));

        String notes = invokeFormat("test v1", issues);

        assertThat(notes).contains("### Fixes");
        assertThat(notes).contains("- Fix null pointer in init (#1)");
        assertThat(notes).doesNotContain("### Enhancements");
        assertThat(notes).doesNotContain("### Internal");
    }

    @Test
    void categorizes_enhancements() throws Exception {
        var issues = List.of(
                issue(2, "Add ws:release-notes goal", List.of("enhancement", "tooling")));

        String notes = invokeFormat("test v1", issues);

        assertThat(notes).contains("### Enhancements");
        assertThat(notes).contains("- Add ws:release-notes goal (#2)");
    }

    @Test
    void categorizes_unlabeled_as_internal() throws Exception {
        var issues = List.of(
                issue(3, "Update docs", List.of("documentation")));

        String notes = invokeFormat("test v1", issues);

        assertThat(notes).contains("### Internal");
        assertThat(notes).contains("- Update docs (#3)");
    }

    @Test
    void mixed_categories_produce_all_sections() throws Exception {
        var issues = List.of(
                issue(1, "Fix crash", List.of("bug")),
                issue(2, "New feature", List.of("enhancement")),
                issue(3, "Cleanup", List.of("tech-debt")));

        String notes = invokeFormat("milestone v2", issues);

        assertThat(notes)
                .contains("## milestone v2")
                .contains("### Fixes")
                .contains("### Enhancements")
                .contains("### Internal");
    }

    @Test
    void release_notes_labeled_issues_sort_first() throws Exception {
        var issues = List.of(
                issue(10, "Minor fix", List.of("bug")),
                issue(5, "Important fix", List.of("bug", "release-notes")));

        String notes = invokeFormat("test v1", issues);

        // release-notes issue (#5) should appear before #10
        int importantIdx = notes.indexOf("Important fix (#5)");
        int minorIdx = notes.indexOf("Minor fix (#10)");
        assertThat(importantIdx).isLessThan(minorIdx);
    }

    @Test
    void empty_issues_produces_no_closed_message() throws Exception {
        String notes = invokeFormat("test v1", List.of());

        assertThat(notes).contains("No closed issues in this milestone.");
        assertThat(notes).doesNotContain("### Fixes");
        assertThat(notes).doesNotContain("### Enhancements");
        assertThat(notes).doesNotContain("### Internal");
    }

    @Test
    void milestone_name_appears_as_heading() throws Exception {
        String notes = invokeFormat("ike-tooling v57", List.of());

        assertThat(notes).startsWith("## ike-tooling v57");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Issue issue(int number, String title, List<String> labels) {
        return new Issue(number, title, labels);
    }

    @SuppressWarnings("unchecked")
    private String invokeFormat(String milestone, List<?> issues)
            throws Exception {
        WsReleaseNotesMojo mojo = new WsReleaseNotesMojo();
        Method method = WsReleaseNotesMojo.class.getDeclaredMethod(
                "formatNotes", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(mojo, milestone, issues);
    }
}
