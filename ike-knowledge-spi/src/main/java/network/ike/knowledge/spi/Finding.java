package network.ike.knowledge.spi;

import java.util.Objects;

/**
 * One verification finding.
 *
 * @param severity  how serious the finding is
 * @param check     the check that produced it
 * @param component what it is about — a public id, artifact path, or dimension key
 * @param message   the finding, stated for a reader who will act on it
 */
public record Finding(Severity severity, VerifyRequest.Check check, String component, String message) {

    /** Finding severities. A report passes when it has no {@link #ERROR}. */
    public enum Severity {
        /** The artifact must not release as-is. */
        ERROR,
        /** Worth attention; does not block. */
        WARNING,
        /** Context for the reader. */
        INFO
    }

    /**
     * Validates the finding.
     *
     * @param severity  how serious the finding is
     * @param check     the check that produced it
     * @param component what the finding is about
     * @param message   the finding text
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code component} or {@code message} is blank
     *                                  — blank text is not representable on the wire
     */
    public Finding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(check, "check");
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("A finding requires a component");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("A finding requires a message");
        }
    }
}
