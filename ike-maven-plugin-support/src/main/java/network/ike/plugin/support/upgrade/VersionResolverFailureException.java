package network.ike.plugin.support.upgrade;

/**
 * Thrown when a {@link CandidateVersionResolver} cannot reach its
 * backing repository or fails to resolve a coordinate.
 *
 * <p>Distinct from {@link network.ike.workspace.VersionUpgradePlanException}
 * (plan parsing) and {@link network.ike.workspace.VersionUpgradeRulesException}
 * (ruleset parsing) so the calling mojo can render a targeted error
 * — typically a "check your Nexus settings" hint rather than a YAML
 * parse failure.
 */
public class VersionResolverFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct with a message and underlying cause.
     *
     * @param message detail message
     * @param cause   underlying cause (network, resolver, etc.)
     */
    public VersionResolverFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
