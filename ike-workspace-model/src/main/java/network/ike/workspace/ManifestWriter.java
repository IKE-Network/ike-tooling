package network.ike.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates specific fields in workspace.yaml while preserving
 * comments, formatting, and structure.
 *
 * <p>Uses targeted text replacement rather than YAML serialization
 * to avoid stripping comments or reordering keys.
 */
public final class ManifestWriter {

    private ManifestWriter() {}

    /**
     * Update the branch field for one or more components.
     *
     * @param manifestPath path to workspace.yaml
     * @param branchUpdates map of component name to new branch value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateBranches(Path manifestPath, Map<String, String> branchUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : branchUpdates.entrySet()) {
            String componentName = entry.getKey();
            String newBranch = entry.getValue();
            content = updateComponentBranch(content, componentName, newBranch);
        }

        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update the maven-version field in the defaults section.
     *
     * @param manifestPath  path to workspace.yaml
     * @param newVersion    the new Maven version string
     * @throws IOException if the file cannot be read or written
     */
    public static void updateDefaultMavenVersion(Path manifestPath, String newVersion)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        content = updateDefaultField(content, "maven-version", newVersion);
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update the maven-version field for one or more components.
     *
     * @param manifestPath   path to workspace.yaml
     * @param versionUpdates map of component name to new maven-version value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateMavenVersions(Path manifestPath, Map<String, String> versionUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : versionUpdates.entrySet()) {
            String componentName = entry.getKey();
            String newVersion = entry.getValue();
            content = updateComponentField(content, componentName, "maven-version", newVersion);
        }

        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update the sha field for one or more components. If the sha field
     * does not exist in the subproject block, it is inserted after the
     * branch field.
     *
     * @param manifestPath path to workspace.yaml
     * @param shaUpdates   map of component name to SHA value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateShas(Path manifestPath, Map<String, String> shaUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : shaUpdates.entrySet()) {
            String componentName = entry.getKey();
            String sha = entry.getValue();
            content = addOrUpdateComponentField(content, componentName,
                    "sha", "\"" + sha + "\"", "branch");
        }

        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update a field in a subproject block, or insert it after a reference
     * field if it doesn't exist yet.
     *
     * @param yaml          full YAML content
     * @param componentName the subproject key
     * @param field         the field name to update or insert
     * @param newValue      the new value (pre-quoted if needed)
     * @param afterField    insert after this field if the target field is absent
     * @return updated YAML content
     */
    public static String addOrUpdateComponentField(String yaml, String componentName,
                                                    String field, String newValue,
                                                    String afterField) {
        // Try update first
        String updated = updateComponentField(yaml, componentName, field, newValue);
        if (!updated.equals(yaml)) {
            return updated; // field existed and was updated
        }

        // Field doesn't exist — insert after afterField
        String escapedName = Pattern.quote(componentName);
        String escapedAfter = Pattern.quote(afterField);

        Pattern insertPattern = Pattern.compile(
            "(^  " + escapedName + ":\\s*$.*?^    " + escapedAfter + ":\\s*\\S+.*?)$",
            Pattern.MULTILINE | Pattern.DOTALL
        );

        Matcher m = insertPattern.matcher(yaml);
        if (m.find()) {
            String insertion = m.group(0) + "\n    " + field + ": " + newValue;
            return yaml.substring(0, m.start()) + insertion + yaml.substring(m.end());
        }
        return yaml;
    }

    /**
     * Update a field in the defaults section of the YAML text.
     *
     * @param yaml     full YAML content
     * @param field    the field name to update
     * @param newValue the new value
     * @return updated YAML content
     */
    static String updateDefaultField(String yaml, String field, String newValue) {
        String escapedField = Pattern.quote(field);
        Pattern pattern = Pattern.compile(
            "(^  " + escapedField + ":\\s*)(\\S+.*?)$",
            Pattern.MULTILINE
        );
        Matcher m = pattern.matcher(yaml);
        if (m.find()) {
            return m.replaceFirst("$1" + Matcher.quoteReplacement(newValue));
        }
        return yaml;
    }

    /**
     * Update a named field within a subproject block in the YAML text.
     *
     * @param yaml          full YAML content
     * @param componentName the subproject key to find
     * @param field         the field name within the subproject block
     * @param newValue      the new value
     * @return updated YAML content
     */
    public static String updateComponentField(String yaml, String componentName,
                                        String field, String newValue) {
        String escapedName = Pattern.quote(componentName);
        String escapedField = Pattern.quote(field);

        Pattern blockPattern = Pattern.compile(
            "(^  " + escapedName + ":\\s*$.*?^    " + escapedField + ":\\s*)(\\S+.*?)$",
            Pattern.MULTILINE | Pattern.DOTALL
        );

        Matcher m = blockPattern.matcher(yaml);
        if (m.find()) {
            return m.replaceFirst("$1" + Matcher.quoteReplacement(newValue));
        }
        return yaml;
    }

    /**
     * Update the branch field for a single component in the YAML text.
     * If the subproject block does not yet declare a {@code branch:} field,
     * it is inserted after the {@code type:} line so the manifest and git
     * state stay in sync (see issue #159).
     *
     * @param yaml          full YAML content
     * @param componentName the subproject key to find
     * @param newBranch     the new branch value
     * @return updated YAML content (unchanged if the subproject is absent)
     */
    public static String updateComponentBranch(String yaml, String componentName, String newBranch) {
        return addOrUpdateComponentField(yaml, componentName, "branch", newBranch, "type");
    }
}
