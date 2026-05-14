package network.ike.plugin.support;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.Prompter;
import org.apache.maven.api.services.PrompterException;

/**
 * Static utility for interactive parameter resolution in mojos
 * that implement {@link org.apache.maven.api.plugin.Mojo} directly
 * (no shared base class).
 *
 * <p>Delegates to Maven 4's {@link Prompter} service for the
 * interactive read, so the prompt label and input cursor render
 * inline across terminal sessions and IntelliJ's Maven runner.
 * Mojos that need to prompt must {@code @Inject} a Prompter and
 * pass it in.
 *
 * <p>Moved from {@code network.ike.plugin} as part of the plugin
 * split (ike-issues#215) so both ike-maven-plugin and
 * ike-doc-maven-plugin can share it. Migrated to the Maven 4
 * Prompter service in ike-issues#385 to match {@code ws:}-goal
 * prompt UX — pre-migration this used {@code System.console} /
 * {@code System.in} directly and bypassed the Prompter abstraction.
 */
public final class MojoParamSupport {

    private MojoParamSupport() {}

    /**
     * Resolve a required parameter: return the value if present,
     * prompt interactively via the {@link Prompter} when not, or
     * fail fast with a clear error when no value can be obtained.
     *
     * @param currentValue the value from the {@code @Parameter} field
     *                     (may be null)
     * @param propertyName the {@code -D} property name (for the error message)
     * @param promptLabel  human-readable label shown in the prompt;
     *                     the Prompter appends its own ": " separator
     * @param prompter     the Maven 4 prompter service (DI-injected by
     *                     the calling mojo); when null, falls back to
     *                     a fail-fast error
     * @param log          Maven logger (currently unused; kept for
     *                     signature compatibility and future logging)
     * @return the resolved value — either the original or user-supplied
     * @throws MojoException if no value can be obtained
     */
    public static String requireParam(String currentValue, String propertyName,
                                       String promptLabel, Prompter prompter,
                                       Log log)
            throws MojoException {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue.trim();
        }

        if (prompter == null) {
            throw new MojoException(
                    propertyName + " is required. Specify -D" + propertyName
                            + "=<value> (no Prompter wired in this context).");
        }

        try {
            String input = prompter.prompt(promptLabel);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        } catch (PrompterException e) {
            throw new MojoException(
                    propertyName + " is required. Specify -D" + propertyName
                            + "=<value> or run interactively. ("
                            + e.getMessage() + ")");
        }

        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }
}
