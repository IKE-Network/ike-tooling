package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Shared helpers for the properties wire format the requests and results use across the
 * forked-JVM seam. Lists encode as 1-based contiguous indexed keys — decoding fails
 * closed on a gap or a blank entry, never silently truncates. Absence encodes as key
 * absence; values are whitespace-normalized (stripped), so leading and trailing
 * whitespace is not significant on the wire.
 *
 * <p>The top-level key {@link IkeServiceBootstrap#IMPLEMENTATION_KEY} is reserved for
 * implementation selection and must not be used by any request codec.
 */
final class PropCodec {

    private PropCodec() {
    }

    static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required request key: " + key);
        }
        return value.strip();
    }

    static Optional<String> optional(Properties properties, String key) {
        String value = properties.getProperty(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.strip());
    }

    static Path requirePath(Properties properties, String key) {
        return Path.of(require(properties, key));
    }

    static Optional<Path> optionalPath(Properties properties, String key) {
        return optional(properties, key).map(Path::of);
    }

    static boolean getBoolean(Properties properties, String key, boolean fallback) {
        Optional<String> value = optional(properties, key);
        if (value.isEmpty()) {
            return fallback;
        }
        String token = value.get();
        if (token.equalsIgnoreCase("true")) {
            return true;
        }
        if (token.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Key " + key + " must be true or false: " + token);
    }

    static long requireLong(Properties properties, String key) {
        String value = require(properties, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Key " + key + " is not a number: " + value, e);
        }
    }

    static void put(Properties properties, String key, String value) {
        properties.setProperty(key, value);
    }

    static void putOptional(Properties properties, String key, Optional<String> value) {
        value.ifPresent(present -> properties.setProperty(key, present));
    }

    static void putInputs(Properties properties, String prefix, List<ArtifactInput> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            ArtifactInput input = inputs.get(i);
            properties.setProperty(prefix + (i + 1) + ".role", input.role().name());
            properties.setProperty(prefix + (i + 1) + ".path", input.path().toString());
        }
    }

    static List<ArtifactInput> getInputs(Properties properties, String prefix) {
        int count = indexedCount(properties, prefix, "role");
        List<ArtifactInput> inputs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            inputs.add(new ArtifactInput(ArtifactInput.Role.valueOf(require(properties, prefix + i + ".role")),
                    Path.of(require(properties, prefix + i + ".path"))));
        }
        return List.copyOf(inputs);
    }

    /**
     * Counts a 1-based indexed list and enforces contiguity — decoding fails closed:
     * a gap ({@code prefix1, prefix3}) or an out-of-sequence start is an error, never a
     * silent truncation. {@code field} is the per-entry key that marks presence; empty
     * means the index is the whole key remainder.
     */
    static int indexedCount(Properties properties, String prefix, String field) {
        java.util.TreeSet<Integer> present = new java.util.TreeSet<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String rest = name.substring(prefix.length());
            String indexPart;
            if (field.isEmpty()) {
                indexPart = rest;
            } else {
                int dot = rest.indexOf('.');
                if (dot <= 0 || !rest.substring(dot + 1).equals(field)) {
                    continue;
                }
                indexPart = rest.substring(0, dot);
            }
            try {
                present.add(Integer.parseInt(indexPart));
            } catch (NumberFormatException notAnIndex) {
                // Not an indexed entry of this list.
            }
        }
        int max = present.isEmpty() ? 0 : present.last();
        for (int i = 1; i <= max; i++) {
            if (!present.contains(i)) {
                String key = field.isEmpty() ? prefix + i : prefix + i + "." + field;
                throw new IllegalArgumentException("Indexed list has a gap — missing " + key
                        + " (indexed lists are 1-based and contiguous; decoding never truncates silently)");
            }
        }
        return max;
    }

    static void putView(Properties properties, ViewSpec view) {
        properties.putAll(view.toProperties("view."));
    }

    static ViewSpec getView(Properties properties) {
        return ViewSpec.fromProperties(properties, "view.");
    }

    static void putCounts(Properties properties, String prefix, EntityCounts counts) {
        properties.setProperty(prefix + "concepts", Long.toString(counts.concepts()));
        properties.setProperty(prefix + "semantics", Long.toString(counts.semantics()));
        properties.setProperty(prefix + "patterns", Long.toString(counts.patterns()));
        properties.setProperty(prefix + "stamps", Long.toString(counts.stamps()));
    }

    static EntityCounts getCounts(Properties properties, String prefix) {
        return new EntityCounts(
                requireLong(properties, prefix + "concepts"),
                requireLong(properties, prefix + "semantics"),
                requireLong(properties, prefix + "patterns"),
                requireLong(properties, prefix + "stamps"));
    }
}
