package network.ike.plugin;

import org.apache.maven.api.plugin.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Post-render pass that turns inline Maven goal references in the
 * generated site HTML into links to their published goal documentation.
 *
 * <p>An inline monospaced goal token such as {@code <code>ws:checkpoint-publish</code>}
 * becomes
 * {@code <a href="…/ws-goals.html#checkpoint-publish"><code>ws:checkpoint-publish</code></a>}.
 * Links always point at the <em>{@code /latest/}</em> goal-reference page
 * (version-independent), so a page never pins a goal link to a specific
 * release:
 * <ul>
 *   <li>{@code ws:<goal>} → {@value #WS_GOALS_BASE}{@code <goal>}</li>
 *   <li>{@code ike:<goal>} → {@value #IKE_GOALS_BASE}{@code <goal>}</li>
 * </ul>
 *
 * <p>The rewrite runs against the already-rendered HTML rather than
 * during AsciiDoc parsing because the site is rendered by
 * {@code asciidoctor-parser-doxia-module}, which does not invoke
 * AsciidoctorJ extensions (verified — neither tree- nor postprocessors
 * fire). Operating on the final HTML also makes the behaviour uniform
 * across every {@code ike:site-publish}-deployed site with one
 * implementation (IKE-Network/ike-issues#783).
 *
 * <p>Tokens are skipped when they sit inside a {@code <pre>} code listing
 * (a command example is not a cross-reference) or are already inside an
 * {@code <a>} (so a hand-authored link is never double-wrapped). Only the
 * matched {@code <code>} spans are modified; the rest of each page is left
 * byte-for-byte unchanged.
 */
public final class GoalLinkRewriter {

    private GoalLinkRewriter() {}

    /** Base URL (latest) of the {@code ws} plugin goal reference, anchor-ready. */
    static final String WS_GOALS_BASE =
            "https://ike.network/ike-platform/latest/ike-workspace-maven-plugin/ws-goals.html#";

    /** Base URL (latest) of the {@code ike} plugin goal reference, anchor-ready. */
    static final String IKE_GOALS_BASE =
            "https://ike.network/ike-tooling/latest/ike-maven-plugin/index.html#";

    /**
     * Overrides for goals whose documentation anchor differs from the goal
     * name. On the handwritten goal-reference pages a few goals have no
     * dedicated anchor — a draft variant documented under its publish
     * sibling, or a goal covered only within a section — so without an
     * override the link would land at the top of the page. Keyed by
     * {@code prefix:goal}; goals not listed use their own name as the
     * anchor. Extend as new gaps surface (IKE-Network/ike-issues#783).
     */
    private static final Map<String, String> ANCHOR_ALIASES = Map.of(
            "ws:checkpoint-draft", "checkpoint-publish",
            "ike:release-changelog", "release-goals");

    /**
     * An inline monospaced goal token: a {@code <code>} element (optionally
     * carrying attributes) whose entire content is {@code ws:<goal>} or
     * {@code ike:<goal>}. Group 1 is the prefix, group 2 the goal name.
     */
    private static final Pattern CODE_GOAL = Pattern.compile(
            "<code(?:\\s[^>]*)?>(ws|ike):([a-z][a-z0-9-]*)</code>");

    /**
     * Regions whose goal tokens must not be linked: code listings
     * ({@code <pre>…</pre>}) and existing anchors ({@code <a>…</a>}).
     * Reluctant quantifiers are safe because neither element nests in
     * valid HTML output.
     */
    private static final Pattern SKIP_REGION = Pattern.compile(
            "<pre\\b[^>]*>.*?</pre>|<a\\b[^>]*>.*?</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Rewrite every {@code *.html} file under {@code siteDir} in place,
     * linking inline goal references to their latest goal docs.
     *
     * <p>Best-effort: a read/write failure on one file aborts the walk but
     * is reported as a non-fatal warning rather than failing the publish.
     * Files with no goal references are left untouched (not rewritten).
     *
     * @param siteDir root of a rendered site tree (e.g. {@code target/site}
     *                or {@code target/staging}); {@code null} or a
     *                non-directory is a no-op
     * @param log     Maven logger for the summary line
     * @return the total number of goal references linked
     */
    public static int rewriteSiteHtml(Path siteDir, Log log) {
        if (siteDir == null || !Files.isDirectory(siteDir)) {
            return 0;
        }
        int[] totalLinks = {0};
        int[] touchedFiles = {0};
        try (Stream<Path> walk = Files.walk(siteDir)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .forEach(path -> {
                    try {
                        String html = Files.readString(path, StandardCharsets.UTF_8);
                        Result result = rewriteHtml(html);
                        if (result.count() > 0) {
                            Files.writeString(path, result.html(), StandardCharsets.UTF_8);
                            totalLinks[0] += result.count();
                            touchedFiles[0]++;
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException | UncheckedIOException e) {
            log.warn("  ⚠ Goal auto-link: skipped (non-fatal): " + e.getMessage());
            return totalLinks[0];
        }
        if (totalLinks[0] > 0) {
            log.info("  Goal auto-link: linked " + totalLinks[0]
                    + " goal reference(s) across " + touchedFiles[0] + " page(s)");
        }
        return totalLinks[0];
    }

    /**
     * Outcome of rewriting one HTML document.
     *
     * @param html  the rewritten HTML (unchanged when {@code count == 0})
     * @param count the number of goal references linked
     */
    record Result(String html, int count) {}

    /**
     * Link inline goal references in a single HTML document.
     *
     * @param html the rendered HTML page (may be {@code null})
     * @return the rewrite result; the original HTML and a count of 0 when
     *         there is nothing to link
     */
    static Result rewriteHtml(String html) {
        if (html == null || html.isEmpty()) {
            return new Result(html, 0);
        }
        List<int[]> skip = skipRanges(html);
        Matcher matcher = CODE_GOAL.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 256);
        int last = 0;
        int count = 0;
        while (matcher.find()) {
            if (inSkippedRange(matcher.start(), skip)) {
                continue;
            }
            String prefix = matcher.group(1);
            String goal = matcher.group(2);
            String base = "ws".equals(prefix) ? WS_GOALS_BASE : IKE_GOALS_BASE;
            String anchor = ANCHOR_ALIASES.getOrDefault(prefix + ":" + goal, goal);
            out.append(html, last, matcher.start());
            out.append("<a href=\"").append(base).append(anchor).append("\">")
               .append(matcher.group()).append("</a>");
            last = matcher.end();
            count++;
        }
        if (count == 0) {
            return new Result(html, 0);
        }
        out.append(html, last, html.length());
        return new Result(out.toString(), count);
    }

    /** Collect {@code [start, end)} ranges of skipped regions, in order. */
    private static List<int[]> skipRanges(String html) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = SKIP_REGION.matcher(html);
        while (matcher.find()) {
            ranges.add(new int[] {matcher.start(), matcher.end()});
        }
        return ranges;
    }

    /** True when {@code pos} falls inside any skipped region. */
    private static boolean inSkippedRange(int pos, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (pos >= range[0] && pos < range[1]) {
                return true;
            }
        }
        return false;
    }
}
