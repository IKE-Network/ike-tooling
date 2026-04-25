package network.ike.plugin.support.upgrade;

/**
 * Thrown when a POM file cannot be read or parsed by
 * {@link PomScanner}.
 *
 * <p>Distinct from {@link VersionResolverFailureException} so a mojo
 * can render a precise message — POM-side problems are typically the
 * user's POM, while resolver problems are typically the user's Nexus.
 */
public class PomScanException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a message.
     *
     * @param message detail message
     */
    public PomScanException(String message) {
        super(message);
    }

    /**
     * Construct with a message and underlying cause.
     *
     * @param message detail message
     * @param cause   underlying cause (typically I/O or parse error)
     */
    public PomScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
