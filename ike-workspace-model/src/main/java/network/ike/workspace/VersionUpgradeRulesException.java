package network.ike.workspace;

/**
 * Thrown when a {@code versions-upgrade-rules.yaml} file cannot be
 * parsed or contains invalid structure.
 *
 * <p>Mirrors {@link VersionUpgradePlanException} in style: distinct
 * exception type so plugin code can catch ruleset loading errors
 * separately from plan loading errors and render targeted messages.
 */
public class VersionUpgradeRulesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a message.
     *
     * @param message the detail message
     */
    public VersionUpgradeRulesException(String message) {
        super(message);
    }

    /**
     * Construct with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause (typically an
     *                {@link java.io.IOException} or YAML parse error)
     */
    public VersionUpgradeRulesException(String message, Throwable cause) {
        super(message, cause);
    }
}
