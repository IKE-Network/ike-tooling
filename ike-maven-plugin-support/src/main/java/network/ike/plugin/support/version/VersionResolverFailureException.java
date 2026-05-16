package network.ike.plugin.support.version;

/**
 * Thrown when a {@link CandidateVersionResolver} cannot reach its
 * backing repository or fails to resolve a coordinate.
 *
 * <p>Surfaced rather than swallowed so the calling goal can render a
 * targeted error — typically a "check your remote repository settings"
 * hint — instead of silently treating an unreachable repository as
 * "no released versions".
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
