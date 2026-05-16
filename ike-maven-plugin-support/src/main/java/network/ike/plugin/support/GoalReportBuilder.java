package network.ike.plugin.support;

import java.util.List;

/**
 * Fluent builder for the body of an IKE goal report.
 *
 * <p>{@link GoalReport} owns the report <em>frame</em> — the
 * {@code # <prefix>:<goal>} title and the timestamp line. This builder
 * owns the <em>body</em>: the sections, paragraphs, bullet lists,
 * tables, and fenced code blocks beneath that frame. Routing every
 * report through one builder keeps section depth, table syntax, and
 * spacing identical across goals instead of each mojo hand-rolling its
 * own {@link StringBuilder} (IKE-Network/ike-issues#408).
 *
 * <p>Methods return {@code this} so a report reads as a single
 * chained expression:
 *
 * <pre>{@code
 * String body = new GoalReportBuilder()
 *         .section("Status")
 *         .paragraph("3 cloned, 1 not cloned.")
 *         .table(List.of("Subproject", "Branch"),
 *                List.of(new String[]{"ike-docs", "main"}))
 *         .build();
 * }</pre>
 *
 * <p>The builder emits GitHub-flavoured Markdown. Section headings use
 * {@code ##} — one level below the {@code #} title {@code GoalReport}
 * prepends. Pass the {@link #build()} result to
 * {@code AbstractGoalMojo.writeReport} (ike plugin) or
 * {@code AbstractWorkspaceMojo.writeReport} (ws plugin).
 */
public final class GoalReportBuilder {

    private final StringBuilder body = new StringBuilder(512);

    /** Creates an empty report-body builder. */
    public GoalReportBuilder() {
    }

    /**
     * Append a section heading ({@code ## title}).
     *
     * @param title the section title
     * @return this builder
     */
    public GoalReportBuilder section(String title) {
        ensureBlankLine();
        body.append("## ").append(title).append("\n\n");
        return this;
    }

    /**
     * Append a paragraph followed by a blank line.
     *
     * @param text the paragraph text
     * @return this builder
     */
    public GoalReportBuilder paragraph(String text) {
        body.append(text.stripTrailing()).append("\n\n");
        return this;
    }

    /**
     * Append a single bullet-list item ({@code - text}). Consecutive
     * calls build a contiguous list.
     *
     * @param text the bullet text
     * @return this builder
     */
    public GoalReportBuilder bullet(String text) {
        body.append("- ").append(text.stripTrailing()).append("\n");
        return this;
    }

    /**
     * Append a GitHub-flavoured Markdown table. A no-op when
     * {@code rows} is empty — an empty table is never emitted.
     *
     * @param headers column headers (defines the column count)
     * @param rows    row cells; each array should match the header
     *                count (shorter rows are padded, longer rows
     *                truncated, so a ragged caller cannot break the
     *                table grid)
     * @return this builder
     */
    public GoalReportBuilder table(List<String> headers,
                                   List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return this;
        }
        int cols = headers.size();
        ensureBlankLine();
        row(headers.toArray(new String[0]), cols);
        StringBuilder sep = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sep.append("---|");
        }
        body.append(sep).append("\n");
        for (String[] r : rows) {
            row(r, cols);
        }
        body.append("\n");
        return this;
    }

    /**
     * Append a fenced code block.
     *
     * @param language the fence language hint (e.g. {@code "dot"});
     *                 may be empty for a plain fence
     * @param content  the block content (a trailing newline is added
     *                 if absent)
     * @return this builder
     */
    public GoalReportBuilder codeBlock(String language, String content) {
        ensureBlankLine();
        body.append("```").append(language == null ? "" : language)
            .append("\n");
        body.append(content);
        if (!content.endsWith("\n")) {
            body.append("\n");
        }
        body.append("```\n\n");
        return this;
    }

    /**
     * Append a pre-rendered Markdown fragment verbatim — the escape
     * hatch for content a primitive does not cover (e.g.
     * {@code DriftReport.toMarkdown()}).
     *
     * @param markdown the Markdown fragment
     * @return this builder
     */
    public GoalReportBuilder raw(String markdown) {
        body.append(markdown);
        return this;
    }

    /**
     * Render the accumulated body as a Markdown string.
     *
     * @return the report body (no title or timestamp — those are
     *         added by {@link GoalReport})
     */
    public String build() {
        return body.toString().stripTrailing() + "\n";
    }

    private void row(String[] cells, int cols) {
        body.append("|");
        for (int i = 0; i < cols; i++) {
            String cell = i < cells.length && cells[i] != null
                    ? cells[i] : "";
            body.append(' ').append(cell).append(" |");
        }
        body.append("\n");
    }

    private void ensureBlankLine() {
        if (body.length() > 0 && !body.toString().endsWith("\n\n")) {
            if (body.charAt(body.length() - 1) != '\n') {
                body.append('\n');
            }
            body.append('\n');
        }
    }
}
