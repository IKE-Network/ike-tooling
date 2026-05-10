package network.ike.plugin.scaffold;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read a scaffold manifest from YAML.
 *
 * <p>The manifest is shipped by {@code ike-build-standards}; the
 * scaffold plugin never writes it, only reads it. Parsing is lenient
 * about extra top-level keys (future extensions) but strict about
 * every key that drives behaviour.
 *
 * <p>Raw adapter-specific configuration (under keys like
 * {@code ensure}, {@code never-touch}, {@code block-begin},
 * {@code block-end}) is preserved verbatim in
 * {@link ManifestEntry#extras()} so tier handlers and model adapters
 * can parse their own subtrees.
 */
public final class ScaffoldManifestIo {

    /**
     * Keys consumed by the core manifest parser; every other key in
     * a file entry is carried through into
     * {@link ManifestEntry#extras()} for adapter consumption.
     */
    private static final Set<String> CORE_ENTRY_KEYS = Set.of(
            "dest", "scope", "tier", "source", "model");

    private ScaffoldManifestIo() {}

    /**
     * Parse a manifest from a file path.
     *
     * @param path path to the YAML manifest
     * @return the parsed manifest
     * @throws ScaffoldException if the file cannot be read, is empty,
     *                           has an unsupported schema version, or
     *                           contains an invalid entry
     */
    public static ScaffoldManifest read(Path path) {
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "Cannot read scaffold manifest " + path, e);
        }
    }

    /**
     * Parse a manifest from a {@link Reader}.
     *
     * @param reader source of YAML text
     * @return the parsed manifest
     */
    public static ScaffoldManifest read(Reader reader) {
        Object raw = new Yaml().load(reader);
        if (raw == null) {
            throw new ScaffoldException(
                    "Empty scaffold manifest");
        }
        if (!(raw instanceof Map<?, ?> rootMap)) {
            throw new ScaffoldException(
                    "Scaffold manifest root must be a YAML mapping, "
                            + "got " + raw.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rootMap;

        int schema = intField(root, "schema",
                ScaffoldManifest.CURRENT_SCHEMA);
        if (schema != ScaffoldManifest.CURRENT_SCHEMA) {
            throw new ScaffoldException(
                    "Unsupported scaffold manifest schema: " + schema
                            + " (supported: "
                            + ScaffoldManifest.CURRENT_SCHEMA + ")");
        }

        String standardsVersion = stringField(
                root, "standards-version", null);
        if (standardsVersion == null) {
            throw new ScaffoldException(
                    "Scaffold manifest missing required "
                            + "'standards-version'");
        }

        Object filesObj = root.get("files");
        if (filesObj == null) {
            throw new ScaffoldException(
                    "Scaffold manifest missing required 'files' list");
        }
        if (!(filesObj instanceof List<?> filesList)) {
            throw new ScaffoldException(
                    "'files' must be a YAML list");
        }

        List<ManifestEntry> entries = new ArrayList<>();
        int idx = 0;
        for (Object o : filesList) {
            entries.add(parseEntry(o, idx++));
        }

        // #345: optional foundation: section. Picking up scaffold
        // version N gives the consumer the parent + property pins
        // that ike-tooling N saw as the latest-released at its
        // release time — a tested-together compatibility snapshot.
        ScaffoldManifest.Foundation foundation = parseFoundation(
                root.get("foundation"));

        return new ScaffoldManifest(schema, standardsVersion,
                entries, foundation);
    }

    @SuppressWarnings("unchecked")
    private static ScaffoldManifest.Foundation parseFoundation(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ScaffoldException(
                    "'foundation' must be a YAML mapping");
        }
        Map<String, Object> foundationMap = (Map<String, Object>) map;

        Object parentObj = foundationMap.get("parent");
        ScaffoldManifest.ParentRef parent = null;
        if (parentObj != null) {
            if (!(parentObj instanceof Map<?, ?> pm)) {
                throw new ScaffoldException(
                        "'foundation.parent' must be a YAML mapping");
            }
            Map<String, Object> parentMap = (Map<String, Object>) pm;
            String g = stringField(parentMap, "groupId", null);
            String a = stringField(parentMap, "artifactId", null);
            String v = stringField(parentMap, "version", null);
            if (g == null || a == null || v == null) {
                throw new ScaffoldException(
                        "'foundation.parent' requires groupId, "
                                + "artifactId, and version");
            }
            parent = new ScaffoldManifest.ParentRef(g, a, v);
        }

        Object propsObj = foundationMap.get("properties");
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        if (propsObj != null) {
            if (!(propsObj instanceof Map<?, ?> pm)) {
                throw new ScaffoldException(
                        "'foundation.properties' must be a YAML mapping");
            }
            Map<String, Object> propsMap = (Map<String, Object>) pm;
            for (Map.Entry<String, Object> e : propsMap.entrySet()) {
                if (e.getValue() == null) continue;
                properties.put(e.getKey(), e.getValue().toString());
            }
        }

        return new ScaffoldManifest.Foundation(parent, properties);
    }

    private static ManifestEntry parseEntry(Object raw, int index) {
        if (!(raw instanceof Map<?, ?> entryMap)) {
            throw new ScaffoldException(
                    "files[" + index + "] must be a YAML mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) entryMap;

        String dest = stringField(entry, "dest", null);
        String scopeStr = stringField(entry, "scope", null);
        String tierStr = stringField(entry, "tier", null);
        String source = stringField(entry, "source", null);
        String model = stringField(entry, "model", null);

        if (dest == null) {
            throw new ScaffoldException(
                    "files[" + index + "] missing 'dest'");
        }
        if (scopeStr == null) {
            throw new ScaffoldException(
                    "files[" + index + "] ('" + dest
                            + "') missing 'scope'");
        }
        if (tierStr == null) {
            throw new ScaffoldException(
                    "files[" + index + "] ('" + dest
                            + "') missing 'tier'");
        }

        ScaffoldScope scope;
        ScaffoldTier tier;
        try {
            scope = ScaffoldScope.fromManifestValue(scopeStr);
            tier = ScaffoldTier.fromManifestValue(tierStr);
        } catch (IllegalArgumentException e) {
            throw new ScaffoldException(
                    "files[" + index + "] ('" + dest + "'): "
                            + e.getMessage(), e);
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            if (!CORE_ENTRY_KEYS.contains(e.getKey())) {
                extras.put(e.getKey(), e.getValue());
            }
        }

        try {
            return new ManifestEntry(
                    dest, scope, tier, source, model, extras);
        } catch (IllegalArgumentException e) {
            throw new ScaffoldException(
                    "files[" + index + "] ('" + dest + "'): "
                            + e.getMessage(), e);
        }
    }

    // ── YAML helpers ────────────────────────────────────────────────

    private static String stringField(
            Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        return v.toString();
    }

    private static int intField(
            Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ScaffoldException(
                    "Expected integer for '" + key + "', got " + v);
        }
    }
}
