package network.ike.plugin.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compare a project's POM against the IKE-foundation pins captured
 * in a scaffold manifest's {@code foundation:} section (#345).
 *
 * <p>The scaffold zip embeds the parent + property values that
 * {@code ike-tooling N} saw as the latest-released at its release
 * moment. Picking up a newer scaffold means picking up that
 * tested-together compatibility snapshot. This class identifies the
 * deltas: where the consumer's POM lags behind, where it's ahead
 * (operator manually bumped), and where it's already in sync.
 *
 * <p>Pure-function — no I/O once a {@code pomContent} string is
 * provided. The {@link #checkPomFile} convenience reads from disk
 * for callers who want path-based access.
 *
 * @see ScaffoldManifest.Foundation
 */
public final class FoundationDriftChecker {

    private FoundationDriftChecker() {}

    /**
     * Compute the drift between a POM file's parent + properties
     * and the scaffold manifest's {@code foundation:} pins.
     *
     * @param pomFile    path to the project's pom.xml
     * @param foundation the scaffold manifest's foundation pins; if
     *                   {@code null}, returns an empty drift list
     * @return ordered list of {@link Entry} records, one per
     *         drifted or aligned property/parent comparison
     * @throws IOException if the POM cannot be read
     */
    public static List<Entry> checkPomFile(
            Path pomFile,
            ScaffoldManifest.Foundation foundation) throws IOException {
        if (foundation == null || !Files.isRegularFile(pomFile)) {
            return List.of();
        }
        String content = Files.readString(pomFile, StandardCharsets.UTF_8);
        return check(content, foundation);
    }

    /**
     * Compute the drift between POM content and foundation pins.
     *
     * @param pomContent the POM XML as a string
     * @param foundation the foundation pins (non-null)
     * @return ordered drift entries
     */
    public static List<Entry> check(String pomContent,
                                     ScaffoldManifest.Foundation foundation) {
        if (pomContent == null || foundation == null) return List.of();

        List<Entry> entries = new ArrayList<>();

        // Parent comparison
        if (foundation.parent() != null) {
            String actualVersion = extractParentVersionMatching(
                    pomContent,
                    foundation.parent().groupId(),
                    foundation.parent().artifactId());
            entries.add(new Entry(
                    Kind.PARENT,
                    foundation.parent().groupId() + ":"
                            + foundation.parent().artifactId(),
                    actualVersion,
                    foundation.parent().version()));
        }

        // Property comparisons
        Map<String, String> projectProps = extractProperties(pomContent);
        for (Map.Entry<String, String> expected
                : foundation.properties().entrySet()) {
            entries.add(new Entry(
                    Kind.PROPERTY,
                    expected.getKey(),
                    projectProps.get(expected.getKey()),
                    expected.getValue()));
        }

        return entries;
    }

    /**
     * Extract the {@code <parent><version>} when the parent's GA
     * matches the given coordinates; otherwise return {@code null}
     * (parent absent OR different GA).
     */
    static String extractParentVersionMatching(String pomContent,
                                                 String groupId,
                                                 String artifactId) {
        java.util.regex.Matcher parentMatcher = java.util.regex.Pattern.compile(
                "(?s)<parent\\b[^>]*>(.*?)</parent>").matcher(pomContent);
        if (!parentMatcher.find()) return null;
        String block = parentMatcher.group(1);

        java.util.regex.Matcher gMatch = java.util.regex.Pattern.compile(
                "<groupId>\\s*([^<]+?)\\s*</groupId>").matcher(block);
        java.util.regex.Matcher aMatch = java.util.regex.Pattern.compile(
                "<artifactId>\\s*([^<]+?)\\s*</artifactId>").matcher(block);
        if (!gMatch.find() || !aMatch.find()) return null;
        if (!gMatch.group(1).trim().equals(groupId)
                || !aMatch.group(1).trim().equals(artifactId)) {
            return null;
        }
        java.util.regex.Matcher vMatch = java.util.regex.Pattern.compile(
                "<version>\\s*([^<]+?)\\s*</version>").matcher(block);
        return vMatch.find() ? vMatch.group(1).trim() : null;
    }

    /**
     * Extract project-level {@code <properties>} as a map. Repeated
     * declarations: the last one wins (matches Maven model semantics).
     */
    static Map<String, String> extractProperties(String pomContent) {
        Map<String, String> result = new LinkedHashMap<>();
        java.util.regex.Matcher blockMatcher = java.util.regex.Pattern.compile(
                "(?s)<properties>\\s*(.*?)\\s*</properties>")
                .matcher(pomContent);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            java.util.regex.Matcher entryMatcher = java.util.regex.Pattern.compile(
                    "(?s)<([\\w.-]+)>\\s*([^<]*?)\\s*</\\1>")
                    .matcher(block);
            while (entryMatcher.find()) {
                result.put(entryMatcher.group(1),
                        entryMatcher.group(2).trim());
            }
        }
        return result;
    }

    /** Classification of a drift entry. */
    public enum Kind {
        /** Workspace-level {@code <parent>} declaration. */
        PARENT,
        /** Project-level {@code <properties>} declaration. */
        PROPERTY
    }

    /** Classification of a drift entry's state. */
    public enum State {
        /** Project's value equals the foundation's. */
        ALIGNED,
        /** Project's value is absent (foundation knows it; project doesn't). */
        ABSENT,
        /** Project's value differs from the foundation's. */
        DIFFERS
    }

    /**
     * One drift comparison result.
     *
     * @param kind     parent or property
     * @param name     the parent coordinates ({@code groupId:artifactId})
     *                 for {@link Kind#PARENT}, or the property name
     *                 for {@link Kind#PROPERTY}
     * @param actual   the value found in the project's POM, or
     *                 {@code null} when absent
     * @param expected the value the foundation pins to
     */
    public record Entry(Kind kind,
                         String name,
                         String actual,
                         String expected) {

        /**
         * Classify this comparison.
         *
         * @return whether the project is aligned, missing the value,
         *         or differs from the foundation
         */
        public State state() {
            if (actual == null) return State.ABSENT;
            if (actual.equals(expected)) return State.ALIGNED;
            return State.DIFFERS;
        }

        /**
         * True when this entry indicates real drift the operator
         * should act on (DIFFERS or ABSENT).
         *
         * @return true when not aligned
         */
        public boolean isDrifted() {
            return state() != State.ALIGNED;
        }
    }
}
