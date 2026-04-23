package network.ike.plugin.support;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

/**
 * Static utility for interactive parameter resolution in mojos
 * that implement {@link org.apache.maven.api.plugin.Mojo} directly
 * (no shared base class).
 *
 * <p>Tries {@code System.console()} first (real terminal), then
 * falls back to {@code System.in} (IntelliJ Maven runner — spawns
 * Maven as a child process with stdin wired to the Run console).
 * Fails fast with a clear error message when neither produces input
 * (CI, daemon mode).
 *
 * <p>Moved from {@code network.ike.plugin} as part of the plugin
 * split (ike-issues #215) so both ike-maven-plugin and
 * ike-doc-maven-plugin can share it.
 */
public final class MojoParamSupport {

    private MojoParamSupport() {}

    /**
     * Resolve a required parameter: return the value if present,
     * prompt interactively if possible, or fail fast.
     *
     * @param currentValue the value from the {@code @Parameter} field (may be null)
     * @param propertyName the {@code -D} property name (for the error message)
     * @param promptLabel  human-readable label shown in the prompt
     * @param log          Maven logger — prompt goes through the logger so
     *                     IntelliJ renders it without a {@code [stdout]} prefix
     * @return the resolved value — either the original or user-supplied
     * @throws MojoException if no value can be obtained
     */
    public static String requireParam(String currentValue, String propertyName,
                                       String promptLabel, Log log)
            throws MojoException {
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
            // Use System.out.print (not log.info) so the cursor stays on the
            // prompt line — log.info adds a newline, forcing input onto a new line.
            System.out.print("\u001B[33m" + promptLabel + ": \u001B[0m");
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

        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }
}
