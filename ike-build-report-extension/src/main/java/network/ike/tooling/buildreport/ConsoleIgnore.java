package network.ike.tooling.buildreport;

import java.util.Objects;

/**
 * One rule in the ledger's {@code console: ignore:} list: a console
 * message the project has decided is not worth a reader's attention.
 *
 * <p>An ignored warning is still shown in the receipt, folded under its
 * rule, but it no longer counts — it does not turn a module yellow and
 * is left out of the warning total. A rule matches by plain substring,
 * not a pattern: the text is copied from the receipt, and console prose
 * is full of characters a regular expression would misread.</p>
 *
 * @param match  text a warning's message must contain to be ignored
 * @param reason why the warning is unimportant; may be empty
 */
public record ConsoleIgnore(String match, String reason) {

    /**
     * Validates the rule.
     *
     * @param match  text a warning's message must contain to be ignored
     * @param reason why the warning is unimportant; null becomes empty
     */
    public ConsoleIgnore {
        Objects.requireNonNull(match, "match");
        if (match.isBlank()) {
            throw new IllegalArgumentException("a console ignore rule needs non-blank match text");
        }
        reason = reason == null ? "" : reason;
    }

    /**
     * Says whether this rule ignores a console message.
     *
     * @param item the consolidated message
     * @return true when the message's text contains {@link #match()},
     *         whatever level it was printed at
     */
    public boolean ignores(ConsoleMessages.Item item) {
        // The level is not consulted: plugins relay a tool's stderr at
        // ERROR even when the tool itself calls the line a warning
        // (jlink's "WARNING: signed modular JAR…" arrives as [ERROR]).
        // A real failure still surfaces under FAILURES, from build
        // events no ignore rule can touch.
        return item.message().contains(match);
    }
}
