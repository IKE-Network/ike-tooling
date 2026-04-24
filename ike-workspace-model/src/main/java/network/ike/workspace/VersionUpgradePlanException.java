package network.ike.workspace;

/**
 * Thrown when a {@code versions-upgrade-plan.yaml} cannot be read,
 * written, or validated.
 *
 * <p>Mirrors {@link ManifestException} for upgrade plans: callers
 * catch this single exception type for all plan-layer errors,
 * including malformed YAML, unknown {@link VersionUpgradeStatus} values,
 * missing required fields, and integrity-check failures.
 */
public class VersionUpgradePlanException extends RuntimeException {

    /**
     * Create an exception with a message only.
     *
     * @param message description of the failure
     */
    public VersionUpgradePlanException(String message) {
        super(message);
    }

    /**
     * Create an exception wrapping an underlying cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception
     */
    public VersionUpgradePlanException(String message, Throwable cause) {
        super(message, cause);
    }
}
