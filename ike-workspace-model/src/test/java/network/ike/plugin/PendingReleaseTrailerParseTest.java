package network.ike.plugin;

import network.ike.plugin.ReleaseNotesSupport.IssueRef;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleaseNotesSupport#parseClosingTrailers} — the
 * closing-trailer parser that drives {@code pending-release} label
 * removal at release time.
 *
 * <p>Conventions enforced:
 *
 * <ul>
 *   <li>Full {@code <owner>/<repo>#N} form is preferred per
 *       IKE-COMMITS.md; bare {@code #N} references resolve via
 *       {@code fallbackRepo}.</li>
 *   <li>Closing keywords match GitHub's documented set:
 *       {@code close/closes/closed}, {@code fix/fixes/fixed},
 *       {@code resolve/resolves/resolved}, case-insensitive.</li>
 *   <li>{@code Refs} is intentionally not a closing keyword.</li>
 * </ul>
 */
class PendingReleaseTrailerParseTest {

    private static final String FALLBACK = "IKE-Network/ike-tooling";

    @Test
    void parses_full_form_trailer() {
        String body = """
                Fix duplicate sha keys in workspace.yaml

                Fixes IKE-Network/ike-issues#387
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(
                new IssueRef("IKE-Network/ike-issues", 387));
    }

    @Test
    void parses_multiple_trailers_in_one_commit() {
        String body = """
                Document commit-msg standard

                Fixes IKE-Network/ike-issues#388
                Fixes IKE-Network/ike-issues#389
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(
                new IssueRef("IKE-Network/ike-issues", 388),
                new IssueRef("IKE-Network/ike-issues", 389));
    }

    @Test
    void recognizes_all_closing_verbs_case_insensitive() {
        String body = """
                close IKE-Network/ike-issues#1
                Closes IKE-Network/ike-issues#2
                CLOSED IKE-Network/ike-issues#3
                fix IKE-Network/ike-issues#4
                Fixes IKE-Network/ike-issues#5
                FIXED IKE-Network/ike-issues#6
                resolve IKE-Network/ike-issues#7
                Resolves IKE-Network/ike-issues#8
                RESOLVED IKE-Network/ike-issues#9
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).hasSize(9);
        assertThat(refs).extracting(IssueRef::number)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void refs_keyword_is_not_a_closer() {
        String body = """
                Bump dependency

                Refs IKE-Network/ike-issues#100
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).isEmpty();
    }

    @Test
    void cross_org_reference_uses_explicit_repo() {
        String body = """
                Fix stamp persistence on pattern inactivation

                Fixes ikmdev/komet-desktop#22
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(
                new IssueRef("ikmdev/komet-desktop", 22));
    }

    @Test
    void bare_reference_resolves_against_fallback() {
        String body = """
                Tweak readme

                Fixes #5
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(new IssueRef(FALLBACK, 5));
    }

    @Test
    void bare_reference_with_null_fallback_is_ignored() {
        String body = "Fixes #5\n";

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, null);

        assertThat(refs).isEmpty();
    }

    @Test
    void duplicate_references_across_commits_are_deduped() {
        String body = """
                Commit A

                Fixes IKE-Network/ike-issues#42
                --end--
                Commit B follow-up

                Fixes IKE-Network/ike-issues#42
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(
                new IssueRef("IKE-Network/ike-issues", 42));
    }

    @Test
    void trailer_with_optional_colon_is_accepted() {
        String body = """
                Subject

                Fixes: IKE-Network/ike-issues#7
                Closes: ikmdev/komet-desktop#12
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).containsExactly(
                new IssueRef("IKE-Network/ike-issues", 7),
                new IssueRef("ikmdev/komet-desktop", 12));
    }

    @Test
    void empty_or_null_input_returns_empty() {
        assertThat(ReleaseNotesSupport.parseClosingTrailers(null, FALLBACK))
                .isEmpty();
        assertThat(ReleaseNotesSupport.parseClosingTrailers("", FALLBACK))
                .isEmpty();
    }

    @Test
    void mid_word_keyword_does_not_match() {
        String body = """
                Subject

                Prefixes: do not match this line
                """;

        Set<IssueRef> refs = ReleaseNotesSupport.parseClosingTrailers(
                body, FALLBACK);

        assertThat(refs).isEmpty();
    }

    // ── hasAnyIssueTrailer (used by #392 trailer-compliance preflight) ─

    @Test
    void hasAnyIssueTrailer_accepts_closing_keywords() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nFixes IKE-Network/ike-issues#1\n")).isTrue();
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nCloses IKE-Network/ike-issues#2\n")).isTrue();
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nResolves IKE-Network/ike-issues#3\n")).isTrue();
    }

    @Test
    void hasAnyIssueTrailer_accepts_refs_keyword() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nRefs IKE-Network/ike-issues#7\n")).isTrue();
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nRef ikmdev/komet-desktop#12\n")).isTrue();
    }

    @Test
    void hasAnyIssueTrailer_accepts_bare_hash_with_keyword() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nFixes #5\n")).isTrue();
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nRefs #100\n")).isTrue();
    }

    @Test
    void hasAnyIssueTrailer_rejects_no_trailer() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Bump test dep\n\nNo issue here.\n")).isFalse();
    }

    @Test
    void hasAnyIssueTrailer_rejects_keyword_without_hash() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nRefs the design note in docs/...\n")).isFalse();
    }

    @Test
    void hasAnyIssueTrailer_handles_empty_or_null() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(null)).isFalse();
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer("")).isFalse();
    }

    @Test
    void hasAnyIssueTrailer_rejects_mid_word_match() {
        assertThat(ReleaseNotesSupport.hasAnyIssueTrailer(
                "Subject\n\nPrefixes #5\n")).isFalse();
    }
}
