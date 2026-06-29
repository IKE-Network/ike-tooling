package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GoalLinkRewriter} — the post-render goal auto-linker.
 * High blast radius (runs over every published page), so the matrix is
 * the safety proof: link the right tokens, never touch listings, never
 * double-wrap, never touch non-goals, and leave everything else intact.
 */
class GoalLinkRewriterTest {

    @Test
    void linksInlineWsGoalToLatestWsGoalsPage() {
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(
                "<p>Run <code>ws:checkpoint-publish</code> now.</p>");
        assertEquals(1, r.count());
        assertEquals(
                "<p>Run <a href=\"" + GoalLinkRewriter.WS_GOALS_BASE
                        + "checkpoint-publish\"><code>ws:checkpoint-publish</code></a> now.</p>",
                r.html());
    }

    @Test
    void linksInlineIkeGoalToLatestIkePluginPage() {
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(
                "<li><code>ike:notarize</code></li>");
        assertEquals(1, r.count());
        assertTrue(r.html().contains(
                "<a href=\"" + GoalLinkRewriter.IKE_GOALS_BASE
                        + "notarize\"><code>ike:notarize</code></a>"));
    }

    @Test
    void linksGoalWithHyphenatedMultiSegmentName() {
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(
                "<code>ike:jpackage-props</code>");
        assertEquals(1, r.count());
        assertTrue(r.html().endsWith("#jpackage-props\"><code>ike:jpackage-props</code></a>"));
    }

    @Test
    void doesNotLinkGoalInsideCodeListing() {
        String html = "<pre class=\"source\"><code>mvn ws:push\nmvn ws:checkpoint-publish</code></pre>";
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(html);
        assertEquals(0, r.count());
        assertEquals(html, r.html());
    }

    @Test
    void doesNotLinkBareCodeTokenInsidePreEvenIfExactMatch() {
        String html = "<pre><code>ws:push</code></pre>";
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(html);
        assertEquals(0, r.count());
        assertEquals(html, r.html());
    }

    @Test
    void doesNotDoubleWrapAnAlreadyLinkedGoal() {
        String html = "<a href=\"https://example.org/x\"><code>ws:push</code></a>";
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(html);
        assertEquals(0, r.count());
        assertEquals(html, r.html());
    }

    @Test
    void leavesNonGoalInlineCodeUntouched() {
        String html = "<p>On <code>HEAD</code> push to <code>origin</code>.</p>";
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(html);
        assertEquals(0, r.count());
        assertEquals(html, r.html());
    }

    @Test
    void linksGoalOnCodeElementCarryingAttributes() {
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(
                "<code class=\"literal\">ws:align-publish</code>");
        assertEquals(1, r.count());
        assertTrue(r.html().contains("#align-publish\">"));
        assertTrue(r.html().contains("<code class=\"literal\">ws:align-publish</code></a>"));
    }

    @Test
    void linksMultipleGoalsAndSkipsListingAndAnchorInSamePage() {
        String html = "<p>Use <code>ws:checkpoint-publish</code> and <code>ike:notarize</code>.</p>"
                + "<pre><code>mvn ws:push</code></pre>"
                + "<a href=\"x\"><code>ws:commit-publish</code></a>";
        GoalLinkRewriter.Result r = GoalLinkRewriter.rewriteHtml(html);
        assertEquals(2, r.count());
        assertTrue(r.html().contains("#checkpoint-publish\">"));
        assertTrue(r.html().contains("#notarize\">"));
        // listing + already-linked goal untouched
        assertTrue(r.html().contains("<pre><code>mvn ws:push</code></pre>"));
        assertTrue(r.html().contains("<a href=\"x\"><code>ws:commit-publish</code></a>"));
        assertFalse(r.html().contains("#push\">"));
        assertFalse(r.html().contains("#commit-publish\">"));
    }

    @Test
    void handlesNullAndEmptyAsNoOp() {
        assertEquals(0, GoalLinkRewriter.rewriteHtml(null).count());
        GoalLinkRewriter.Result empty = GoalLinkRewriter.rewriteHtml("");
        assertEquals(0, empty.count());
        assertEquals("", empty.html());
    }

    @Test
    void rewriteSiteHtmlProcessesOnlyHtmlFilesWithGoals(@TempDir Path dir) throws IOException {
        Path withGoal = dir.resolve("page.html");
        Path noGoal = dir.resolve("plain.html");
        Path notHtml = dir.resolve("notes.txt");
        Files.writeString(withGoal, "<code>ws:push</code>", StandardCharsets.UTF_8);
        Files.writeString(noGoal, "<p>nothing here</p>", StandardCharsets.UTF_8);
        Files.writeString(notHtml, "<code>ws:push</code>", StandardCharsets.UTF_8);

        int linked = GoalLinkRewriter.rewriteSiteHtml(dir, new TestLog());

        assertEquals(1, linked);
        assertTrue(Files.readString(withGoal).contains("#push\"><code>ws:push</code></a>"));
        assertEquals("<p>nothing here</p>", Files.readString(noGoal));
        // non-HTML file is never touched, even with a goal token in it
        assertEquals("<code>ws:push</code>", Files.readString(notHtml));
    }

    @Test
    void rewriteSiteHtmlIsNoOpForMissingDirectory(@TempDir Path dir) {
        assertEquals(0, GoalLinkRewriter.rewriteSiteHtml(dir.resolve("nope"), new TestLog()));
        assertEquals(0, GoalLinkRewriter.rewriteSiteHtml(null, new TestLog()));
    }
}
