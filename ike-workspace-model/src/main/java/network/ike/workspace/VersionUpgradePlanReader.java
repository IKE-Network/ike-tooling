package network.ike.workspace;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@code versions-upgrade-plan.yaml} file into a typed
 * {@link VersionUpgradePlan}.
 *
 * <p>SnakeYAML parses into untyped {@code Map<String,Object>}; this
 * class maps that onto immutable records with validation. Unknown
 * fields are rejected — plans are short-lived and machine-generated,
 * so silent tolerance is more likely to mask bugs than to help.
 *
 * <p>Matches {@link ManifestReader} in style: static factory
 * methods, small parse helpers per record, fail-fast on malformed
 * input with {@link VersionUpgradePlanException}.
 */
public final class VersionUpgradePlanReader {

    private VersionUpgradePlanReader() {}

    /**
     * Read a plan from a YAML file.
     *
     * @param path path to {@code versions-upgrade-plan.yaml}
     * @return the parsed plan
     * @throws VersionUpgradePlanException if the file cannot be read or has
     *                              invalid structure
     */
    public static VersionUpgradePlan read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return read(reader);
        } catch (IOException e) {
            throw new VersionUpgradePlanException("Cannot read " + path, e);
        }
    }

    /**
     * Read a plan from a YAML source.
     *
     * @param reader YAML source
     * @return the parsed plan
     * @throws VersionUpgradePlanException if the YAML has invalid structure
     */
    public static VersionUpgradePlan read(Reader reader) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(reader);
        if (root == null) {
            throw new VersionUpgradePlanException("Empty plan");
        }
        return parsePlan(root);
    }

    private static VersionUpgradePlan parsePlan(Map<String, Object> root) {
        String schemaVersion = stringField(root, "schema-version", "1.0");
        String generated = stringField(root, "generated", null);
        VersionUpgradeScope scope = parseScope(stringField(root, "scope", null));
        String planHash = stringField(root, "plan-hash", null);
        String pomFingerprint = stringField(root, "pom-fingerprint", null);
        String ikeToolingVersion =
                stringField(root, "ike-tooling-version", null);

        Map<String, NodeVersionUpgrade> nodes =
                parseNodes(mapField(root, "nodes"));

        return new VersionUpgradePlan(schemaVersion, generated, scope, planHash,
                pomFingerprint, ikeToolingVersion, nodes);
    }

    private static VersionUpgradeScope parseScope(String raw) {
        if (raw == null) {
            throw new VersionUpgradePlanException(
                    "Plan is missing required field: scope");
        }
        try {
            return VersionUpgradeScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new VersionUpgradePlanException(
                    "Unknown scope '" + raw + "' — expected 'module' or "
                            + "'workspace'", e);
        }
    }

    private static Map<String, NodeVersionUpgrade> parseNodes(
            Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, NodeVersionUpgrade> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) entry.getValue();
            if (fields == null) {
                throw new VersionUpgradePlanException(
                        "Node '" + name + "' has no body");
            }
            result.put(name, parseNode(name, fields));
        }
        return Collections.unmodifiableMap(result);
    }

    private static NodeVersionUpgrade parseNode(String name,
                                                Map<String, Object> fields) {
        ParentVersionUpgrade parent = parseParent(mapField(fields, "parent"));
        List<PropertyVersionUpgrade> properties =
                parseProperties(mapField(fields, "properties"));
        List<LiteralVersionUpgrade> literals =
                parseLiterals(listOfMaps(fields, "literals"));
        return new NodeVersionUpgrade(name, parent, properties, literals);
    }

    private static ParentVersionUpgrade parseParent(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return new ParentVersionUpgrade(
                requireString(map, "groupId"),
                requireString(map, "artifactId"),
                requireString(map, "from"),
                requireString(map, "to"),
                parseStatus(requireString(map, "status")),
                stringField(map, "reason", null));
    }

    private static List<PropertyVersionUpgrade> parseProperties(
            Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<PropertyVersionUpgrade> result = new ArrayList<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String propertyName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) entry.getValue();
            if (fields == null) {
                throw new VersionUpgradePlanException(
                        "Property '" + propertyName + "' has no body");
            }
            result.add(new PropertyVersionUpgrade(
                    propertyName,
                    requireString(fields, "from"),
                    requireString(fields, "to"),
                    parseStatus(requireString(fields, "status")),
                    stringField(fields, "reason", null)));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<LiteralVersionUpgrade> parseLiterals(
            List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<LiteralVersionUpgrade> result = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            result.add(new LiteralVersionUpgrade(
                    requireString(entry, "groupId"),
                    requireString(entry, "artifactId"),
                    stringField(entry, "location", ""),
                    requireString(entry, "from"),
                    requireString(entry, "to"),
                    parseStatus(requireString(entry, "status")),
                    stringField(entry, "reason", null)));
        }
        return Collections.unmodifiableList(result);
    }

    private static VersionUpgradeStatus parseStatus(String raw) {
        String canon = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return VersionUpgradeStatus.valueOf(canon);
        } catch (IllegalArgumentException e) {
            throw new VersionUpgradePlanException(
                    "Unknown status '" + raw + "' — expected 'ready', "
                            + "'blocked', or 'pending-upstream'", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> map,
                                                String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            throw new VersionUpgradePlanException(
                    "Expected a mapping for '" + key + "' but got "
                            + value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(
            Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new VersionUpgradePlanException(
                    "Expected a list for '" + key + "' but got "
                            + value.getClass().getSimpleName());
        }
        return (List<Map<String, Object>>) value;
    }

    private static String stringField(Map<String, Object> map, String key,
                                      String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new VersionUpgradePlanException(
                    "Missing required field: " + key);
        }
        return value.toString();
    }
}
