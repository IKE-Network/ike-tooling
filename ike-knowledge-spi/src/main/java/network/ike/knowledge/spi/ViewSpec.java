package network.ike.knowledge.spi;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The view specification — dotted dimension keys mirroring the coordinate constituents
 * (stamp, language, logic, navigation, edit), stating only what differs from the
 * implementation's defaults (IKE-Network/ike-issues#849; normative schema:
 * IKE-KNOWLEDGE-VIEW, IKE-Network/ike-issues#851).
 *
 * <p>Values are strings: concept dimensions carry {@link ConceptRef} forms, list
 * dimensions carry comma-separated entries in preference order, and
 * {@link #STAMP_POSITION_TIME} carries {@code latest} or an ISO-8601 instant. Keys are
 * validated strictly against the published key set — an unknown key fails fast, so a
 * typo never silently becomes a default.
 *
 * <p>Additional language coordinates (ordered fallbacks) use an index segment:
 * {@code language.2.language}, {@code language.2.dialects}, … — the unindexed
 * {@code language.*} keys are the primary language coordinate.
 *
 * <p>Resolution — turning dimension values into coordinate records against a store and
 * its bindings — happens on the implementation side of the seam. This type is the
 * portable, engine-ignorant carrier; implementations report the fully resolved effective
 * view beside their outputs.
 */
public final class ViewSpec {

    /** Allowed status values, comma-separated (closed value set, rendered verbatim). */
    public static final String STAMP_ALLOWED_STATES = "stamp.allowedStates";
    /** Position time: {@code latest} or an ISO-8601 instant — pinning makes builds reproducible. */
    public static final String STAMP_POSITION_TIME = "stamp.position.time";
    /** Position path ({@link ConceptRef}). */
    public static final String STAMP_POSITION_PATH = "stamp.position.path";
    /** Included modules, comma-separated {@link ConceptRef}s; absent means any module. */
    public static final String STAMP_MODULES = "stamp.modules";
    /** Excluded modules, comma-separated {@link ConceptRef}s. */
    public static final String STAMP_EXCLUDED_MODULES = "stamp.excludedModules";
    /** Module priority order, comma-separated {@link ConceptRef}s. */
    public static final String STAMP_MODULE_PRIORITY = "stamp.modulePriority";

    /** Language concept ({@link ConceptRef}). */
    public static final String LANGUAGE_LANGUAGE = "language.language";
    /** Description patterns in preference order, comma-separated {@link ConceptRef}s. */
    public static final String LANGUAGE_PATTERNS = "language.patterns";
    /** Description-type preference order, comma-separated {@link ConceptRef}s. */
    public static final String LANGUAGE_DESCRIPTION_TYPES = "language.descriptionTypes";
    /** Dialect preference order, comma-separated {@link ConceptRef}s. */
    public static final String LANGUAGE_DIALECTS = "language.dialects";
    /** Module preference order for language resolution, comma-separated {@link ConceptRef}s. */
    public static final String LANGUAGE_MODULE_PREFERENCE = "language.modulePreference";

    /** Classifier concept ({@link ConceptRef}) — also the author of derived semantics. */
    public static final String LOGIC_CLASSIFIER = "logic.classifier";
    /** Description-logic profile concept ({@link ConceptRef}). */
    public static final String LOGIC_PROFILE = "logic.profile";
    /** Stated axioms pattern ({@link ConceptRef}). */
    public static final String LOGIC_STATED_AXIOMS_PATTERN = "logic.statedAxiomsPattern";
    /** Inferred axioms pattern ({@link ConceptRef}). */
    public static final String LOGIC_INFERRED_AXIOMS_PATTERN = "logic.inferredAxiomsPattern";
    /** Concept membership pattern ({@link ConceptRef}). */
    public static final String LOGIC_CONCEPT_MEMBER_PATTERN = "logic.conceptMemberPattern";
    /** Stated navigation pattern ({@link ConceptRef}). */
    public static final String LOGIC_STATED_NAVIGATION_PATTERN = "logic.statedNavigationPattern";
    /** Inferred navigation pattern ({@link ConceptRef}). */
    public static final String LOGIC_INFERRED_NAVIGATION_PATTERN = "logic.inferredNavigationPattern";
    /** Navigation root concept ({@link ConceptRef}). */
    public static final String LOGIC_ROOT = "logic.root";

    /** Navigation patterns to traverse, comma-separated {@link ConceptRef}s. */
    public static final String NAVIGATION_PATTERNS = "navigation.patterns";
    /** Allowed vertex states, comma-separated (closed value set). */
    public static final String NAVIGATION_VERTEX_STATES = "navigation.vertexStates";

    /** Author concept for authored content ({@link ConceptRef}). */
    public static final String EDIT_AUTHOR = "edit.author";
    /** Default module for new content ({@link ConceptRef}) — where derived semantics land. */
    public static final String EDIT_DEFAULT_MODULE = "edit.defaultModule";
    /** Destination module when modularizing ({@link ConceptRef}). */
    public static final String EDIT_DESTINATION_MODULE = "edit.destinationModule";
    /** Default authoring path ({@link ConceptRef}). */
    public static final String EDIT_DEFAULT_PATH = "edit.defaultPath";
    /** Promotion path ({@link ConceptRef}). */
    public static final String EDIT_PROMOTION_PATH = "edit.promotionPath";

    /**
     * Overrides the implementation's default bindings class for bare
     * {@code #CONSTANT} references (fully qualified class name, not a
     * {@link ConceptRef}).
     */
    public static final String REFS_DEFAULT_BINDING_CLASS = "refs.defaultBindingClass";

    private static final Set<String> KNOWN_KEYS = Set.of(
            STAMP_ALLOWED_STATES, STAMP_POSITION_TIME, STAMP_POSITION_PATH,
            STAMP_MODULES, STAMP_EXCLUDED_MODULES, STAMP_MODULE_PRIORITY,
            LANGUAGE_LANGUAGE, LANGUAGE_PATTERNS, LANGUAGE_DESCRIPTION_TYPES,
            LANGUAGE_DIALECTS, LANGUAGE_MODULE_PREFERENCE,
            LOGIC_CLASSIFIER, LOGIC_PROFILE, LOGIC_STATED_AXIOMS_PATTERN,
            LOGIC_INFERRED_AXIOMS_PATTERN, LOGIC_CONCEPT_MEMBER_PATTERN,
            LOGIC_STATED_NAVIGATION_PATTERN, LOGIC_INFERRED_NAVIGATION_PATTERN, LOGIC_ROOT,
            NAVIGATION_PATTERNS, NAVIGATION_VERTEX_STATES,
            EDIT_AUTHOR, EDIT_DEFAULT_MODULE, EDIT_DESTINATION_MODULE,
            EDIT_DEFAULT_PATH, EDIT_PROMOTION_PATH,
            REFS_DEFAULT_BINDING_CLASS);

    /** Indexed fallback-language keys: {@code language.<n>.<field>} with n ≥ 2. */
    private static final Pattern INDEXED_LANGUAGE_KEY = Pattern.compile(
            "language\\.([2-9]|[1-9][0-9]+)\\.(language|patterns|descriptionTypes|dialects|modulePreference)");

    private static final ViewSpec EMPTY = new ViewSpec(new TreeMap<>());

    private final TreeMap<String, String> dimensions;

    private ViewSpec(TreeMap<String, String> dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * The empty specification — every dimension at the implementation's default.
     *
     * @return the empty spec
     */
    public static ViewSpec empty() {
        return EMPTY;
    }

    /**
     * Creates a specification from dimension entries.
     *
     * @param dimensions dotted dimension keys to values
     * @return the spec
     * @throws IllegalArgumentException if a key is not in the published key set
     *                                  (including the indexed-language forms) or a value
     *                                  is null or blank
     */
    public static ViewSpec of(Map<String, String> dimensions) {
        TreeMap<String, String> validated = new TreeMap<>();
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            validated.put(requireKnownKey(entry.getKey()), requireValue(entry.getKey(), entry.getValue()));
        }
        return validated.isEmpty() ? EMPTY : new ViewSpec(validated);
    }

    /**
     * Returns a copy of this spec with one dimension set.
     *
     * @param key   the dotted dimension key
     * @param value the dimension value
     * @return a new spec with the dimension applied
     * @throws IllegalArgumentException if the key is unknown or the value blank
     */
    public ViewSpec withDimension(String key, String value) {
        TreeMap<String, String> copy = new TreeMap<>(dimensions);
        copy.put(requireKnownKey(key), requireValue(key, value));
        return new ViewSpec(copy);
    }

    /**
     * Reads one dimension.
     *
     * @param key the dotted dimension key
     * @return the value, if stated
     */
    public Optional<String> dimension(String key) {
        return Optional.ofNullable(dimensions.get(key));
    }

    /**
     * All stated dimensions, sorted by key.
     *
     * @return an unmodifiable sorted view of the stated dimensions
     */
    public SortedMap<String, String> dimensions() {
        return java.util.Collections.unmodifiableSortedMap(dimensions);
    }

    /**
     * Merges another spec over this one — the override's dimensions win.
     *
     * @param overrides the overriding spec
     * @return the merged spec
     */
    public ViewSpec merge(ViewSpec overrides) {
        if (overrides.dimensions.isEmpty()) {
            return this;
        }
        TreeMap<String, String> merged = new TreeMap<>(dimensions);
        merged.putAll(overrides.dimensions);
        return new ViewSpec(merged);
    }

    /**
     * Encodes the stated dimensions as properties, each key prefixed with the given
     * prefix (the request codecs use {@code view.}).
     *
     * @param prefix the key prefix, typically ending in a dot; may be empty
     * @return the encoded properties
     */
    public Properties toProperties(String prefix) {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            properties.setProperty(prefix + entry.getKey(), entry.getValue());
        }
        return properties;
    }

    /**
     * Decodes a spec from properties, taking every key under the given prefix.
     *
     * @param properties the source properties
     * @param prefix     the key prefix used at encoding time; may be empty
     * @return the decoded spec
     * @throws IllegalArgumentException if a prefixed key is not in the published key set
     */
    public static ViewSpec fromProperties(Properties properties, String prefix) {
        TreeMap<String, String> dimensions = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                String key = name.substring(prefix.length());
                dimensions.put(requireKnownKey(key), requireValue(key, properties.getProperty(name)));
            }
        }
        return dimensions.isEmpty() ? EMPTY : new ViewSpec(dimensions);
    }

    private static String requireKnownKey(String key) {
        if (key == null || (!KNOWN_KEYS.contains(key) && !INDEXED_LANGUAGE_KEY.matcher(key).matches())) {
            throw new IllegalArgumentException("Unknown view dimension key: \"" + key
                    + "\" — the valid keys are the IKE-KNOWLEDGE-VIEW standard's dimension keys "
                    + KNOWN_KEYS.stream().sorted().toList()
                    + " plus indexed fallback languages language.<n>.<field> (n >= 2)");
        }
        return key;
    }

    private static String requireValue(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("View dimension \"" + key + "\" requires a value");
        }
        return value.strip();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ViewSpec spec && dimensions.equals(spec.dimensions);
    }

    @Override
    public int hashCode() {
        return dimensions.hashCode();
    }

    @Override
    public String toString() {
        return "ViewSpec" + dimensions;
    }
}
