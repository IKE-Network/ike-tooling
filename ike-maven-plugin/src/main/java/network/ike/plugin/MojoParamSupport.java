package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Static utility for interactive parameter resolution in mojos
 * that extend {@link org.apache.maven.plugin.AbstractMojo} directly
 * (no shared base class).
 *
 * <p>Uses {@code System.console()} only — no {@code System.in}
 * fallback. When {@code System.console()} is null (IDE Maven tool
 * window, CI), the method fails fast with a clear error message
 * telling the user to pass the property via {@code -D}.
 */
final class MojoParamSupport {

    private MojoParamSupport() {}

    /**
     * Resolve a required parameter: return the value if present,
     * prompt interactively if a console is available, or fail fast.
     *
     * @param currentValue  the value from the {@code @Parameter} field (may be null)
     * @param propertyName  the {@code -D} property name (for the error message)
     * @param promptLabel   human-readable label shown in the prompt
     * @return the resolved value — either the original or user-supplied
     * @throws MojoExecutionException if no value can be obtained
     */
    static String requireParam(String currentValue, String propertyName,
                               String promptLabel)
            throws MojoExecutionException {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue.trim();
        }

        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine("%s: ", promptLabel);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }

        throw new MojoExecutionException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run from a terminal for interactive input.");
    }
}
