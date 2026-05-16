package network.ike.plugin.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GoalReportBuilder}.
 */
class GoalReportBuilderTest {

    @Test
    void emptyBuilder_buildsSingleNewline() {
        assertThat(new GoalReportBuilder().build()).isEqualTo("\n");
    }

    @Test
    void section_emitsLevelTwoHeading() {
        String md = new GoalReportBuilder().section("Status").build();

        assertThat(md).startsWith("## Status");
    }

    @Test
    void paragraph_followedBySection_hasBlankSeparation() {
        String md = new GoalReportBuilder()
                .paragraph("Intro line.")
                .section("Detail")
                .build();

        assertThat(md).isEqualTo("Intro line.\n\n## Detail\n");
    }

    @Test
    void bullets_formContiguousList() {
        String md = new GoalReportBuilder()
                .bullet("one")
                .bullet("two")
                .build();

        assertThat(md).isEqualTo("- one\n- two\n");
    }

    @Test
    void table_emitsGithubFlavouredGrid() {
        String md = new GoalReportBuilder()
                .table(List.of("Name", "Branch"),
                        List.of(new String[]{"ike-docs", "main"},
                                new String[]{"ike-platform", "main"}))
                .build();

        assertThat(md)
                .contains("| Name | Branch |")
                .contains("|---|---|")
                .contains("| ike-docs | main |")
                .contains("| ike-platform | main |");
    }

    @Test
    void table_emptyRows_emitsNothing() {
        String md = new GoalReportBuilder()
                .table(List.of("A", "B"), List.of())
                .build();

        assertThat(md).isEqualTo("\n");
    }

    @Test
    void table_raggedRow_isPaddedToColumnCount() {
        String md = new GoalReportBuilder()
                .table(List.of("A", "B", "C"),
                        List.<String[]>of(new String[]{"only-one"}))
                .build();

        // Short row is padded with empty cells, not left ragged.
        assertThat(md).contains("| only-one |  |  |");
    }

    @Test
    void codeBlock_wrapsContentInFence() {
        String md = new GoalReportBuilder()
                .codeBlock("dot", "digraph g {}\n")
                .build();

        assertThat(md)
                .contains("```dot\n")
                .contains("digraph g {}")
                .contains("```");
    }

    @Test
    void raw_appendsFragmentVerbatim() {
        String md = new GoalReportBuilder()
                .raw("- ✓ **Parent** — no drift\n")
                .build();

        assertThat(md).isEqualTo("- ✓ **Parent** — no drift\n");
    }

    @Test
    void chainedReport_rendersInOrder() {
        String md = new GoalReportBuilder()
                .section("Overview")
                .paragraph("All good.")
                .section("Graph")
                .codeBlock("dot", "digraph g {}")
                .build();

        assertThat(md.indexOf("## Overview"))
                .isLessThan(md.indexOf("All good."));
        assertThat(md.indexOf("All good."))
                .isLessThan(md.indexOf("## Graph"));
        assertThat(md.indexOf("## Graph"))
                .isLessThan(md.indexOf("```dot"));
    }
}
