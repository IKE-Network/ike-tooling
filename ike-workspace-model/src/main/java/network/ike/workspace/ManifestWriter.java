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
     * Update the branch field for one or more subprojects.
     *
     * @param manifestPath path to workspace.yaml
     * @param branchUpdates map of subproject name to new branch value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateBranches(Path manifestPath, Map<String, String> branchUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : branchUpdates.entrySet()) {
            String subprojectName = entry.getKey();
            String newBranch = entry.getValue();
            content = updateSubprojectBranch(content, subprojectName, newBranch);
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
     * Update the maven-version field for one or more subprojects.
     *
     * @param manifestPath   path to workspace.yaml
     * @param versionUpdates map of subproject name to new maven-version value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateMavenVersions(Path manifestPath, Map<String, String> versionUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : versionUpdates.entrySet()) {
            String subprojectName = entry.getKey();
            String newVersion = entry.getValue();
            content = updateSubprojectField(content, subprojectName, "maven-version", newVersion);
        }

        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Strip a branch qualifier from every {@code version:} field in the
     * manifest, restoring the base {@code -SNAPSHOT} form. This is the inverse
     * of the version qualification {@code ws:feature-start} applies;
     * {@code ws:feature-finish} calls it so the manifest's recorded versions —
     * each subproject's {@code version:} and, schema 1.1+, the
     * {@code workspace-root:} {@code version:} — return to base when a feature
     * lands, instead of being stranded on {@code X-<feature>-SNAPSHOT}
     * (ike-issues#768/#763).
     *
     * <p>Only {@code version:} field values carrying the exact qualifier are
     * touched: {@code maven-version:}, {@code branch:}, and unrelated fields
     * are left alone, as are versions with a different (or no) qualifier. The
     * operation is idempotent.
     *
     * @param manifestPath path to workspace.yaml
     * @param qualifier    the branch qualifier to strip (e.g.
     *                     {@code "view-options-popup"})
     * @throws IOException if the file cannot be read or written
     */
    public static void stripVersionQualifiers(Path manifestPath, String qualifier)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        // A `version:` field line (any indent) whose value ends in
        // `-<qualifier>-SNAPSHOT`, optionally quoted; drop just the qualifier.
        // `\s*version:` will not match `maven-version:` (the `maven-` prefix is
        // non-whitespace), so that field is safe.
        Pattern p = Pattern.compile(
                "(?m)^(\\s*version:\\s*\"?.*?)-"
                        + Pattern.quote(qualifier) + "-SNAPSHOT(\"?\\s*)$");
        content = p.matcher(content).replaceAll("$1-SNAPSHOT$2");
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update the sha field for one or more subprojects. If the sha field
     * does not exist in the subproject block, it is inserted after the
     * branch field.
     *
     * @param manifestPath path to workspace.yaml
     * @param shaUpdates   map of subproject name to SHA value
     * @throws IOException if the file cannot be read or written
     */
    public static void updateShas(Path manifestPath, Map<String, String> shaUpdates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : shaUpdates.entrySet()) {
            String subprojectName = entry.getKey();
            String sha = entry.getValue();
            content = addOrUpdateSubprojectField(content, subprojectName,
                    "sha", "\"" + sha + "\"", "branch");
        }

        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update a field in a subproject block, or insert it after a reference
     * field if it doesn't exist yet.
     *
     * <p>If the field exists multiple times in the block (a corrupted state
     * from a prior version of this writer, see #387), all duplicates are
     * collapsed into a single entry with the new value.
     *
     * @param yaml           full YAML content
     * @param subprojectName the subproject key
     * @param field          the field name to update or insert
     * @param newValue       the new value (pre-quoted if needed)
     * @param afterField     insert after this field if the target field is absent
     * @return updated YAML content
     */
    public static String addOrUpdateSubprojectField(String yaml, String subprojectName,
                                                    String field, String newValue,
                                                    String afterField) {
        SubprojectBlockBounds bounds = findSubprojectBlockBounds(yaml, subprojectName);
        if (bounds == null) return yaml;

        int blockStart = bounds.start();
        int blockEnd = bounds.end();
        String block = yaml.substring(blockStart, blockEnd);

        // Strip ALL existing occurrences of the field in this block. Handles
        // both the normal "one existing entry" case and the corrupted
        // "multiple duplicate entries" case (#387) idempotently.
        Pattern fieldLine = Pattern.compile(
            "(?m)^    " + Pattern.quote(field) + ":[^\\n]*\\n?"
        );
        String stripped = fieldLine.matcher(block).replaceAll("");

        // Insert one canonical entry after the reference field.
        Pattern afterLine = Pattern.compile(
            "(?m)^    " + Pattern.quote(afterField) + ":[^\\n]*$"
        );
        Matcher afterMatcher = afterLine.matcher(stripped);
        String rebuilt;
        if (afterMatcher.find()) {
            rebuilt = stripped.substring(0, afterMatcher.end())
                    + "\n    " + field + ": " + newValue
                    + stripped.substring(afterMatcher.end());
        } else {
            // Reference field not present in block — fall back to inserting
            // the new field at the end of the block content (before the next
            // sibling subproject's start).
            String trimmed = stripped.replaceAll("\\s+$", "");
            rebuilt = trimmed + "\n    " + field + ": " + newValue + "\n";
        }

        return yaml.substring(0, blockStart) + rebuilt + yaml.substring(blockEnd);
    }

    /**
     * Half-open character offsets of a subproject's body in the YAML
     * text. {@code start} is the position immediately after the
     * {@code "  <name>:"} header line's newline, so
     * {@code yaml.substring(start, end)} contains only the field
     * lines under the subproject.
     *
     * @param start inclusive start offset (first byte of body)
     * @param end   exclusive end offset (first byte of next sibling
     *              subproject, next top-level key, or end of file)
     */
    public record SubprojectBlockBounds(int start, int end) {}

    /**
     * Locate a subproject's body in the YAML text.
     *
     * @param yaml           full YAML content
     * @param subprojectName the subproject key to locate
     * @return bounds of the subproject's body, or null if absent
     */
    static SubprojectBlockBounds findSubprojectBlockBounds(String yaml,
                                                            String subprojectName) {
        Pattern header = Pattern.compile(
            "(?m)^  " + Pattern.quote(subprojectName) + ":\\s*$"
        );
        Matcher headerMatcher = header.matcher(yaml);
        if (!headerMatcher.find()) return null;
        int start = headerMatcher.end();
        // Skip the newline that terminates the header line, if present.
        if (start < yaml.length() && yaml.charAt(start) == '\n') {
            start++;
        }

        // Block ends at the next sibling subproject ("^  <key>:") or any
        // top-level (zero-indent) key, whichever comes first.
        Pattern boundary = Pattern.compile(
            "(?m)^(?:  \\S[^:\\n]*:\\s*$|\\S)"
        );
        Matcher boundaryMatcher = boundary.matcher(yaml);
        int end = yaml.length();
        if (boundaryMatcher.find(start)) {
            end = boundaryMatcher.start();
        }
        return new SubprojectBlockBounds(start, end);
    }

    /**
     * Return whether the given field exists in the given subproject's
     * block. Block-bounded scan so a same-named field in a sibling
     * subproject does not produce a false positive.
     *
     * <p>This is the explicit replacement for the historical pattern
     * of calling {@link #updateSubprojectField} and comparing the
     * result to the original yaml — that approach (still used inside
     * the pre-fix {@link #addOrUpdateSubprojectField}) couldn't
     * distinguish "field absent" from "field present with same value"
     * and produced the duplicate-key bug fixed in
     * IKE-Network/ike-issues#387.
     *
     * @param yaml           full YAML content
     * @param subprojectName the subproject key
     * @param field          the field name
     * @return true if the field appears at least once in the subproject's block
     */
    public static boolean subprojectFieldExists(String yaml, String subprojectName,
                                                  String field) {
        SubprojectBlockBounds bounds = findSubprojectBlockBounds(yaml, subprojectName);
        if (bounds == null) return false;
        String block = yaml.substring(bounds.start(), bounds.end());
        Pattern fieldLine = Pattern.compile(
            "(?m)^    " + Pattern.quote(field) + ":"
        );
        return fieldLine.matcher(block).find();
    }

    /**
     * Collapse duplicate field entries in every subproject block.
     *
     * <p>For each subproject, if any field name appears more than once
     * in the block, keep only the LAST occurrence (matches YAML
     * last-wins semantics for duplicate keys) and remove the rest.
     *
     * <p>Safety-net cleanup for workspaces affected by the pre-fix
     * duplicate-key bug (#387). Idempotent: running on a clean file
     * is a no-op.
     *
     * @param yaml full YAML content
     * @return yaml with duplicates collapsed
     */
    public static String collapseDuplicateSubprojectFields(String yaml) {
        // Find every subproject header to know the block bounds.
        Pattern subprojectHeader = Pattern.compile(
            "(?m)^  (\\S[^:\\n]*):\\s*$"
        );
        Matcher headerMatcher = subprojectHeader.matcher(yaml);
        java.util.List<String> names = new java.util.ArrayList<>();
        while (headerMatcher.find()) {
            names.add(headerMatcher.group(1));
        }

        String result = yaml;
        for (String name : names) {
            result = collapseDuplicatesInBlock(result, name);
        }
        return result;
    }

    /**
     * Helper for {@link #collapseDuplicateSubprojectFields}: collapse
     * duplicate field keys in one subproject block, keeping the last
     * occurrence of each field.
     */
    private static String collapseDuplicatesInBlock(String yaml, String subprojectName) {
        SubprojectBlockBounds bounds = findSubprojectBlockBounds(yaml, subprojectName);
        if (bounds == null) return yaml;
        int blockStart = bounds.start();
        int blockEnd = bounds.end();
        String block = yaml.substring(blockStart, blockEnd);

        // Find every "    <field>: <value>" line in encounter order,
        // tracking the LAST occurrence per field name.
        Pattern fieldLine = Pattern.compile(
            "(?m)^    (\\S[^:\\n]*):[^\\n]*$"
        );
        Matcher fieldMatcher = fieldLine.matcher(block);
        // Map field name → list of {start, end, lineWithNewline} for each occurrence.
        java.util.Map<String, java.util.List<int[]>> occurrences =
                new java.util.LinkedHashMap<>();
        while (fieldMatcher.find()) {
            String name = fieldMatcher.group(1);
            int s = fieldMatcher.start();
            int e = fieldMatcher.end();
            // Include trailing newline if present so removal is clean.
            if (e < block.length() && block.charAt(e) == '\n') {
                e++;
            }
            occurrences.computeIfAbsent(name, k -> new java.util.ArrayList<>())
                    .add(new int[]{s, e});
        }

        // Determine which character ranges to remove: all but the last
        // occurrence for each duplicated field.
        java.util.List<int[]> toRemove = new java.util.ArrayList<>();
        for (java.util.List<int[]> list : occurrences.values()) {
            if (list.size() <= 1) continue;
            for (int i = 0; i < list.size() - 1; i++) {
                toRemove.add(list.get(i));
            }
        }
        if (toRemove.isEmpty()) return yaml;

        toRemove.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(block);
        for (int[] r : toRemove) {
            sb.delete(r[0], r[1]);
        }
        return yaml.substring(0, blockStart) + sb + yaml.substring(blockEnd);
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
     * @param yaml           full YAML content
     * @param subprojectName the subproject key to find
     * @param field          the field name within the subproject block
     * @param newValue       the new value
     * @return updated YAML content
     */
    public static String updateSubprojectField(String yaml, String subprojectName,
                                        String field, String newValue) {
        String escapedName = Pattern.quote(subprojectName);
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
     * Update the branch field for a single subproject in the YAML text.
     * If the subproject block does not yet declare a {@code branch:} field,
     * it is inserted after the {@code repo:} line so the manifest and git
     * state stay in sync (see issue #159).
     *
     * @param yaml           full YAML content
     * @param subprojectName the subproject key to find
     * @param newBranch      the new branch value
     * @return updated YAML content (unchanged if the subproject is absent)
     */
    public static String updateSubprojectBranch(String yaml, String subprojectName, String newBranch) {
        return addOrUpdateSubprojectField(yaml, subprojectName, "branch", newBranch, "repo");
    }
}
