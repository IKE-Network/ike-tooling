package network.ike.plugin.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic {@link IkePrompter} that replays a fixed script of
 * answers (IKE-Network/ike-issues#385).
 *
 * <p>Primary use is unit-testing goals that prompt — construct one
 * with the answers the test wants, inject it, and the goal runs
 * without any real I/O. Each {@code prompt} / {@code confirm} /
 * {@code select} call consumes the next scripted answer.
 *
 * <p>It is shipped (not test-scoped) so both plugins' test suites can
 * share it.
 */
public final class ScriptedIkePrompter implements IkePrompter {

    private final Deque<String> answers;

    /**
     * Creates a prompter that replays the given answers in order.
     *
     * @param answers the answers each prompt call returns, in order
     */
    public ScriptedIkePrompter(String... answers) {
        this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public boolean isInteractive() {
        return true;
    }

    @Override
    public String prompt(String label) {
        return answers.poll();
    }

    @Override
    public boolean confirm(String label, boolean defaultYes) {
        String answer = answers.poll();
        if (answer == null) {
            return defaultYes;
        }
        return answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    @Override
    public String select(String label, List<String> options) {
        String answer = answers.poll();
        if (answer == null || options == null) {
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
}
