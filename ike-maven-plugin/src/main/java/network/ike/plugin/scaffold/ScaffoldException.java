package network.ike.plugin.scaffold;

/**
 * Thrown when scaffold operations cannot complete: malformed
 * lockfile, malformed manifest, I/O failure, or invalid state
 * detected during draft/publish/revert.
 *
 * <p>Mojos translate this into {@code MojoException} at the plugin
 * boundary; the scaffold core stays Maven-agnostic.
 */
public class ScaffoldException extends RuntimeException {

    /**
     * @param message explanatory message; must be specific enough
     *                that a user reading the failure knows which file
     *                or operation failed
     */
    public ScaffoldException(String message) {
        super(message);
    }

    /**
     * @param message explanatory message
     * @param cause   underlying exception
     */
    public ScaffoldException(String message, Throwable cause) {
        super(message, cause);
    }
}
