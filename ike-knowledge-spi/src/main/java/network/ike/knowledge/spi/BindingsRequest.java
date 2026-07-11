package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * A bindings-generation request: emit a compiler-visible constants class for a knowledge
 * set's declarations (IKE-Network/ike-issues#824) or for a loaded store's concepts and
 * patterns.
 *
 * @param mode               where the declarations come from
 * @param outputDirectory    the generated-sources root to write under
 * @param packageName        the generated class's package
 * @param className          the generated class's simple name
 * @param sourceClass        the ledger source class to compose when more than one is
 *                           discoverable ({@link Mode#LEDGER} only)
 * @param membershipPatterns for {@link Mode#STORE_SCAN}, restrict to members of these
 *                           patterns; empty means every concept and pattern
 * @param view               the view specification governing description selection;
 *                           {@link ViewSpec#empty()} means implementation defaults
 */
public record BindingsRequest(Mode mode, Path outputDirectory, String packageName, String className,
                              Optional<String> sourceClass, List<ConceptRef> membershipPatterns,
                              ViewSpec view) {

    /** Where bindings declarations come from. */
    public enum Mode {
        /** Push from a composed knowledge-set ledger — the authored-set idiom. */
        LEDGER,
        /**
         * Scan a loaded store's concepts and patterns — the idiom for third-party
         * knowledge bases and for ingest, optionally restricted by membership patterns.
         */
        STORE_SCAN
    }

    /**
     * Validates and defensively copies the request.
     *
     * @param mode               where the declarations come from
     * @param outputDirectory    the generated-sources root to write under
     * @param packageName        the generated class's package
     * @param className          the generated class's simple name
     * @param sourceClass        the ledger source class selection
     * @param membershipPatterns the membership restriction for {@link Mode#STORE_SCAN}
     * @param view               the view specification
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code packageName} or {@code className} is
     *                                  blank, membership patterns are given in
     *                                  {@link Mode#LEDGER} mode, or a source class is
     *                                  given in {@link Mode#STORE_SCAN} mode
     */
    public BindingsRequest {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(sourceClass, "sourceClass");
        Objects.requireNonNull(view, "view");
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("Bindings generation requires a package name");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Bindings generation requires a class name");
        }
        membershipPatterns = List.copyOf(Objects.requireNonNull(membershipPatterns, "membershipPatterns"));
        if (mode == Mode.LEDGER && !membershipPatterns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Membership patterns apply to STORE_SCAN mode only — a ledger already declares its members");
        }
        if (mode == Mode.STORE_SCAN && sourceClass.isPresent()) {
            throw new IllegalArgumentException(
                    "A ledger source class applies to LEDGER mode only — a store scan reads the store");
        }
    }

    /**
     * Encodes this request in the seam wire format.
     *
     * @return the request properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "mode", mode.name());
        PropCodec.put(properties, "outputDirectory", outputDirectory.toString());
        PropCodec.put(properties, "packageName", packageName);
        PropCodec.put(properties, "className", className);
        PropCodec.putOptional(properties, "sourceClass", sourceClass);
        for (int i = 0; i < membershipPatterns.size(); i++) {
            PropCodec.put(properties, "membershipPattern." + (i + 1), membershipPatterns.get(i).format());
        }
        PropCodec.putView(properties, view);
        return properties;
    }

    /**
     * Decodes a request from the seam wire format.
     *
     * @param properties the request properties
     * @return the decoded request
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static BindingsRequest fromProperties(Properties properties) {
        int count = PropCodec.indexedCount(properties, "membershipPattern.", "");
        List<ConceptRef> membershipPatterns = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            membershipPatterns.add(ConceptRef.parse(PropCodec.require(properties, "membershipPattern." + i)));
        }
        return new BindingsRequest(
                Mode.valueOf(PropCodec.require(properties, "mode")),
                PropCodec.requirePath(properties, "outputDirectory"),
                PropCodec.require(properties, "packageName"),
                PropCodec.require(properties, "className"),
                PropCodec.optional(properties, "sourceClass"),
                membershipPatterns,
                PropCodec.getView(properties));
    }
}
