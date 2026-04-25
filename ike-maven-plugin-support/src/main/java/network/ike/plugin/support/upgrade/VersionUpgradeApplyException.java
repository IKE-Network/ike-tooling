package network.ike.plugin.support.upgrade;

/**
 * Thrown when {@link VersionUpgradePlanApplier} cannot apply a plan
 * entry — typically because the POM no longer matches what the plan
 * was drafted against.
 *
 * <p>Distinct exception so the calling mojo can render a "your POM
 * was edited; regenerate the plan" hint rather than a generic
 * failure.
 */
public class VersionUpgradeApplyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a message.
     *
     * @param message detail message
     */
    public VersionUpgradeApplyException(String message) {
        super(message);
    }

    /**
     * Construct with a message and underlying cause.
     *
     * @param message detail message
     * @param cause   underlying cause (typically I/O)
     */
    public VersionUpgradeApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
