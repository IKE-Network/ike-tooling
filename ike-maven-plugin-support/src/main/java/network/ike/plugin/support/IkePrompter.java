package network.ike.plugin.support;

import java.util.List;

/**
 * Interactive-prompt abstraction for IKE plugin goals
 * (IKE-Network/ike-issues#385).
 *
 * <p>This interface is the stable seam between goal code and the
 * messy reality of interactive input across environments. Goals
 * depend only on {@code IkePrompter}; the implementation is free to
 * change as Maven and IDE Maven runners evolve.
 *
 * <p>The shipped implementation, {@link ConsoleIkePrompter}, is
 * environment-aware: it renders an inline prompt on a real terminal
 * (a shell, or IntelliJ's Terminal tool window — where
 * {@code System.console()} is non-null) and an own-line prompt in a
 * piped runner (IntelliJ's Maven tool window — where
 * {@code System.console()} is {@code null}, established by
 * measurement under #385). The Maven 4 {@code Prompter} service is
 * deliberately not used — it writes through JLine to raw file
 * descriptors, uncoordinated with Maven's logger, and misrenders in
 * piped runners.
 *
 * <p>{@link ScriptedIkePrompter} is a deterministic implementation
 * for tests.
 */
public interface IkePrompter {

    /**
     * Whether an interactive input channel is available.
     *
     * <p>False in genuine non-interactive contexts (Maven batch
     * mode); a goal should then fail with a {@code -D<param>=}
     * instruction rather than prompt.
     *
     * @return true when prompting is possible
     */
    boolean isInteractive();

    /**
     * Prompts for a free-text line of input.
     *
     * @param label the prompt label, including any trailing
     *              separator (e.g. {@code "Commit message: "})
     * @return the entered line (trimmed), or {@code null} if input
     *         was blank, end-of-stream, or not interactive
     */
    String prompt(String label);

    /**
     * Prompts for a yes/no confirmation.
     *
     * @param label      the question
     * @param defaultYes the answer assumed on blank input or a
     *                   non-interactive context
     * @return the user's choice, or {@code defaultYes} as the default
     */
    boolean confirm(String label, boolean defaultYes);

    /**
     * Prompts for a numbered selection from a list.
     *
     * @param label   the selection header
     * @param options the choices, rendered as a numbered list
     * @return the chosen option, or {@code null} on invalid, blank,
     *         or non-interactive input
     */
    String select(String label, List<String> options);
}
