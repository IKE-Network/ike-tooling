package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Static utility for interactive parameter resolution in mojos
 * that extend {@link org.apache.maven.plugin.AbstractMojo} directly
 * (no shared base class).
 *
 * <p>Tries {@code System.console()} first (real terminal), then
 * falls back to {@code System.in} (IntelliJ Maven runner — spawns
 * Maven as a child process with stdin wired to the Run console).
 * Fails fast with a clear error message when neither produces input
 * (CI, daemon mode).
 */
final class MojoParamSupport {

    private MojoParamSupport() {}

    /**
     * Resolve a required parameter: return the value if present,
     * prompt interactively if possible, or fail fast.
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

        // Try System.console() first (real terminal)
        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine("%s: ", promptLabel);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        } else {
            // IntelliJ's Maven runner spawns Maven as a child process
            // with stdin/stdout wired to the Run console panel. System.console()
            // is null (not a real terminal), but System.in is connected and
            // interactive — the same mechanism the Plexus Prompter uses.
            System.out.print(promptLabel + ": ");
            System.out.flush();
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in));
                String input = reader.readLine();
                if (input != null && !input.isBlank()) {
                    return input.trim();
                }
            } catch (java.io.IOException e) {
                // Fall through to error
            }
        }

        throw new MojoExecutionException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }
}
