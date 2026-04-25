package network.ike.workspace;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a {@code versions-upgrade-rules.yaml} file into a typed
 * {@link VersionUpgradeRules}.
 *
 * <p>Mirrors {@link VersionUpgradePlanReader} in style: static factory
 * methods, fail-fast on malformed input with
 * {@link VersionUpgradeRulesException}, no silent tolerance for
 * unknown fields.
 *
 * <p>The {@code match} field combines groupId and artifactId as
 * {@code "groupIdPattern:artifactIdPattern"}. A bare pattern with no
 * colon is treated as the groupId pattern and {@code "*"} is used for
 * artifactId. This keeps the common case (allow a whole group) terse.
 */
public final class VersionUpgradeRulesReader {

    private VersionUpgradeRulesReader() {}

    /**
     * Read a ruleset from a YAML file.
     *
     * @param path path to {@code versions-upgrade-rules.yaml}
     * @return the parsed ruleset
     * @throws VersionUpgradeRulesException if the file cannot be read or
     *                                      has invalid structure
     */
    public static VersionUpgradeRules read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return read(reader);
        } catch (IOException e) {
            throw new VersionUpgradeRulesException(
                    "Cannot read " + path, e);
        }
    }

    /**
     * Read a ruleset from a YAML source.
     *
     * @param reader YAML source
     * @return the parsed ruleset
     * @throws VersionUpgradeRulesException if the YAML has invalid
     *                                      structure
     */
    public static VersionUpgradeRules read(Reader reader) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(reader);
        if (root == null) {
            throw new VersionUpgradeRulesException("Empty ruleset");
        }
        return parseRules(root);
    }

    private static VersionUpgradeRules parseRules(Map<String, Object> root) {
        String schemaVersion = stringField(root, "schema-version", "1.0");
        VersionUpgradeRule.Action defaultAction = parseAction(
                stringField(root, "default-action", "block"));
        List<VersionUpgradeRule> rules = parseRuleList(
                listOfMaps(root, "rules"));
        return new VersionUpgradeRules(schemaVersion, defaultAction, rules);
    }

    private static List<VersionUpgradeRule> parseRuleList(
            List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<VersionUpgradeRule> result = new ArrayList<>(raw.size());
        int index = 0;
        for (Map<String, Object> entry : raw) {
            result.add(parseRule(entry, index));
            index++;
        }
        return result;
    }

    private static VersionUpgradeRule parseRule(
            Map<String, Object> entry, int index) {
        String match = requireString(entry, "match",
                "rule[" + index + "]");
        String[] split = splitMatch(match, index);
        String groupIdPattern = split[0];
        String artifactIdPattern = split[1];
        VersionUpgradeRule.Action action = parseAction(
                requireString(entry, "action", "rule[" + index + "]"));
        String pinnedVersion = stringField(entry, "version", null);
        String reason = stringField(entry, "reason", null);
        if (action == VersionUpgradeRule.Action.PIN
                && (pinnedVersion == null || pinnedVersion.isBlank())) {
            throw new VersionUpgradeRulesException(
                    "rule[" + index + "] action 'pin' requires a "
                            + "'version' field");
        }
        return new VersionUpgradeRule(groupIdPattern, artifactIdPattern,
                action, pinnedVersion, reason);
    }

    private static String[] splitMatch(String match, int index) {
        int colon = match.indexOf(':');
        if (colon < 0) {
            return new String[]{match, "*"};
        }
        if (match.indexOf(':', colon + 1) >= 0) {
            throw new VersionUpgradeRulesException(
                    "rule[" + index + "] match '" + match + "' has more "
                            + "than one ':'; expected "
                            + "'groupIdPattern:artifactIdPattern'");
        }
        String groupIdPattern = match.substring(0, colon);
        String artifactIdPattern = match.substring(colon + 1);
        if (groupIdPattern.isEmpty() || artifactIdPattern.isEmpty()) {
            throw new VersionUpgradeRulesException(
                    "rule[" + index + "] match '" + match + "' must have "
                            + "non-empty groupIdPattern and "
                            + "artifactIdPattern");
        }
        return new String[]{groupIdPattern, artifactIdPattern};
    }

    private static VersionUpgradeRule.Action parseAction(String raw) {
        if (raw == null) {
            throw new VersionUpgradeRulesException(
                    "Missing required field: action");
        }
        String canon = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return VersionUpgradeRule.Action.valueOf(canon);
        } catch (IllegalArgumentException e) {
            throw new VersionUpgradeRulesException(
                    "Unknown action '" + raw + "' — expected 'allow', "
                            + "'block', or 'pin'", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(
            Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new VersionUpgradeRulesException(
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

    private static String requireString(Map<String, Object> map,
                                        String key, String context) {
        Object value = map.get(key);
        if (value == null) {
            throw new VersionUpgradeRulesException(
                    context + " missing required field: " + key);
        }
        return value.toString();
    }
}
