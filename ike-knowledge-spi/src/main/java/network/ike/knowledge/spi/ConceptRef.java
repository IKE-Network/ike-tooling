package network.ike.knowledge.spi;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A concept reference as written in a POM or ViewSpec — the three forms of the
 * IKE-KNOWLEDGE-VIEW reference grammar (IKE-Network/ike-issues#849, #851):
 *
 * <ul>
 *   <li>{@code uuid:<uuid>[,<uuid>...]} — exact identity; a list, because public ids are
 *       UUID sets ({@link Uuids});</li>
 *   <li>{@code <BindingsClass>#<CONSTANT>} or bare {@code #<CONSTANT>} — a bindings-class
 *       constant, resolved by reflective static-field read on the implementation side;
 *       with no class named, the implementation's default bindings class applies
 *       (override per spec via {@link ViewSpec#REFS_DEFAULT_BINDING_CLASS})
 *       ({@link Binding});</li>
 *   <li>{@code text:<name>} or any other bare string — a description text, resolved as a
 *       unique live fully qualified or regular name against the store; ambiguity or
 *       no-match fails the build ({@link Text}).</li>
 * </ul>
 *
 * <p>Parsing and formatting round-trip: {@code parse(format(ref)).equals(ref)} for every
 * reference. A bare string containing {@code #} or starting with a reserved prefix must
 * use the explicit {@code text:} form to be read as text.
 *
 * <p>Resolution happens on the implementation side of the seam — this type carries the
 * reference, never the resolved identity.
 */
public sealed interface ConceptRef permits ConceptRef.Uuids, ConceptRef.Binding, ConceptRef.Text {

    /** The {@code uuid:} form's prefix. */
    String UUID_PREFIX = "uuid:";

    /** The explicit {@code text:} form's prefix. */
    String TEXT_PREFIX = "text:";

    /**
     * Formats this reference in its canonical string form, such that
     * {@link #parse(String)} reproduces it exactly.
     *
     * @return the canonical string form
     */
    String format();

    /**
     * Parses a concept reference from its string form.
     *
     * @param value the reference string, in any of the three forms
     * @return the parsed reference
     * @throws IllegalArgumentException if {@code value} is null, blank, or malformed
     *                                  (empty uuid list, invalid UUID, blank constant
     *                                  name, or blank text)
     */
    static ConceptRef parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A concept reference requires a value");
        }
        String trimmed = value.strip();
        if (trimmed.startsWith(UUID_PREFIX)) {
            String list = trimmed.substring(UUID_PREFIX.length());
            List<UUID> uuids = java.util.Arrays.stream(list.split(","))
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .map(UUID::fromString)
                    .toList();
            return new Uuids(uuids);
        }
        if (trimmed.startsWith(TEXT_PREFIX)) {
            return new Text(trimmed.substring(TEXT_PREFIX.length()).strip());
        }
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            String className = trimmed.substring(0, hash).strip();
            String constant = trimmed.substring(hash + 1).strip();
            return new Binding(className.isEmpty() ? null : className, constant);
        }
        return new Text(trimmed);
    }

    /**
     * An exact-identity reference: one or more UUIDs forming the public id.
     *
     * @param uuids the public id's UUIDs, in order; never empty
     */
    record Uuids(List<UUID> uuids) implements ConceptRef {

        /**
         * Validates and defensively copies the UUID list.
         *
         * @param uuids the public id's UUIDs, in order; never empty
         * @throws IllegalArgumentException if {@code uuids} is null or empty
         */
        public Uuids {
            if (uuids == null || uuids.isEmpty()) {
                throw new IllegalArgumentException("A uuid: reference requires at least one UUID");
            }
            uuids = List.copyOf(uuids);
        }

        @Override
        public String format() {
            return UUID_PREFIX + uuids.stream().map(UUID::toString).collect(Collectors.joining(","));
        }
    }

    /**
     * A bindings-class constant reference, resolved implementation-side by reflective
     * static-field read.
     *
     * @param className the bindings class; {@code null} means the implementation's
     *                  default bindings class (see {@link ViewSpec#REFS_DEFAULT_BINDING_CLASS})
     * @param constant  the constant name; never blank
     */
    record Binding(String className, String constant) implements ConceptRef {

        /**
         * Validates the constant name and normalizes a blank class name to {@code null}.
         *
         * @param className the bindings class, or {@code null}/blank for the default
         * @param constant  the constant name; never blank
         * @throws IllegalArgumentException if {@code constant} is null or blank, or
         *                                  {@code className} contains {@code #} or
         *                                  {@code :} (not legal in a class name, and
         *                                  they would break the round-trip invariant)
         */
        public Binding {
            if (constant == null || constant.isBlank()) {
                throw new IllegalArgumentException("A bindings reference requires a constant name");
            }
            className = (className == null || className.isBlank()) ? null : className.strip();
            if (className != null && (className.indexOf('#') >= 0 || className.indexOf(':') >= 0)) {
                throw new IllegalArgumentException(
                        "A bindings class name cannot contain '#' or ':': " + className);
            }
            constant = constant.strip();
        }

        @Override
        public String format() {
            return (className == null ? "" : className) + "#" + constant;
        }
    }

    /**
     * A description-text reference, resolved as a unique live fully qualified or regular
     * name against the store.
     *
     * @param text the description text; never blank
     */
    record Text(String text) implements ConceptRef {

        /**
         * Validates the text.
         *
         * @param text the description text; never blank
         * @throws IllegalArgumentException if {@code text} is null or blank
         */
        public Text {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("A text reference requires a name");
            }
            text = text.strip();
        }

        @Override
        public String format() {
            boolean needsPrefix = text.indexOf('#') >= 0
                    || text.startsWith(UUID_PREFIX)
                    || text.startsWith(TEXT_PREFIX);
            return needsPrefix ? TEXT_PREFIX + text : text;
        }
    }

    /**
     * Convenience factory for a single-UUID reference.
     *
     * @param uuid the concept's UUID
     * @return the reference
     * @throws NullPointerException if {@code uuid} is null
     */
    static ConceptRef ofUuid(UUID uuid) {
        return new Uuids(List.of(Objects.requireNonNull(uuid, "uuid")));
    }
}
