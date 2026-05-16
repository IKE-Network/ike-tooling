package network.ike.plugin.support;

import org.apache.maven.api.plugin.Log;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Environment-aware {@link IkePrompter} (IKE-Network/ike-issues#385).
 *
 * <p>Detects the input channel with {@link System#console()} and
 * renders accordingly:
 *
 * <ul>
 *   <li><b>{@code System.console() != null}</b> — a real terminal (a
 *       shell, or IntelliJ's Terminal tool window). The label is
 *       written and the input read through {@link Console}, so the
 *       prompt renders <em>inline</em> with the cursor.</li>
 *   <li><b>{@code System.console() == null}</b> — a piped runner
 *       (IntelliJ's Maven tool window). The label is written through
 *       the Maven {@link Log} — the one channel a piped IDE console
 *       renders correctly and in order with {@code [INFO]} output —
 *       and input is read from {@code System.in}. The label lands on
 *       its own line: visible, ordered, answerable.</li>
 * </ul>
 *
 * <p>The Maven 4 {@code Prompter} service is intentionally not used:
 * it writes through JLine to raw file descriptors, uncoordinated with
 * the logger, and misrenders in piped runners.
 */
public final class ConsoleIkePrompter implements IkePrompter {

    private final Log log;
    private final boolean interactive;
    private BufferedReader pipedReader;

    /**
     * Creates a prompter.
     *
     * @param log         the Maven logger — used for the own-line
     *                    label in the piped (non-console) case
     * @param interactive whether Maven is in an interactive context;
     *                    pass {@code false} for batch mode (the
     *                    prompter then declines all prompts)
     */
    public ConsoleIkePrompter(Log log, boolean interactive) {
        this.log = log;
        this.interactive = interactive;
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public String prompt(String label) {
        if (!interactive) {
            return null;
        }
        Console console = System.console();
        String line;
        if (console != null) {
            // Real terminal — inline prompt through the Console.
            line = console.readLine("%s", label);
        } else {
            // Piped runner — label through the logging channel,
            // input from stdin (ike-issues#385).
            log.info(label);
            try {
                line = pipedReader().readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Could not read input from System.in", e);
            }
        }
        return (line == null || line.isBlank()) ? null : line.trim();
    }

    @Override
    public boolean confirm(String label, boolean defaultYes) {
        String suffix = defaultYes ? " [Y/n]: " : " [y/N]: ";
        String answer = prompt(label + suffix);
        if (answer == null) {
            return defaultYes;
        }
        return switch (answer.toLowerCase()) {
            case "y", "yes" -> true;
            case "n", "no" -> false;
            default -> defaultYes;
        };
    }

    @Override
    public String select(String label, List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        log.info(label);
        for (int i = 0; i < options.size(); i++) {
            log.info("  " + (i + 1) + ") " + options.get(i));
        }
        String answer = prompt("Select [1-" + options.size() + "]: ");
        if (answer == null) {
            return null;
        }
        try {
            int index = Integer.parseInt(answer.trim()) - 1;
            return (index >= 0 && index < options.size())
                    ? options.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BufferedReader pipedReader() {
        if (pipedReader == null) {
            pipedReader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        return pipedReader;
    }
}
