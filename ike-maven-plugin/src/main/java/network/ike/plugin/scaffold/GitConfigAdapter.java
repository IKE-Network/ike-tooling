package network.ike.plugin.scaffold;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model adapter for git config files ({@code ~/.gitconfig} or a
 * repository's {@code .git/config}).
 *
 * <p>Git config is an INI-flavoured format: sections like
 * {@code [core]} and {@code [alias "co"]} (a subsection) contain
 * {@code key = value} pairs. This adapter parses the format
 * line-by-line, preserving comments and unknown sections verbatim,
 * and ensures named keys are present under named sections.
 *
 * <p>Supported {@code ensure} subtree:
 * <pre>{@code
 * ensure:
 *   core:
 *     autocrlf: "false"
 *     excludesfile: "~/.gitignore_global"
 *   "alias":
 *     st: "status -sb"
 * }</pre>
 *
 * <p>Keys under each section are ensured independently. If a key is
 * already present with any value, the user's value wins (we don't
 * overwrite), but the key is still recorded as managed. Missing keys
 * get appended to the end of the matching section, or a new section is
 * created at end of file if the section itself is missing.
 */
public final class GitConfigAdapter implements ModelAdapter {

    /** Model name matching {@link ManifestEntry#model()}. */
    public static final String MODEL_NAME = "git-config";

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public ModelPlanResult plan(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] currentContent,
            LockfileEntry priorEntry,
            String currentStandardsVersion) {

        Map<String, Map<String, String>> ensure =
                readEnsureSections(entry);
        Map<String, ManagedElement> priorByPath =
                indexByPath(priorEntry);

        boolean fresh = currentContent == null
                || currentContent.length == 0;
        GitConfigDoc doc = fresh
                ? GitConfigDoc.empty()
                : GitConfigDoc.parse(
                        new String(currentContent,
                                StandardCharsets.UTF_8));

        boolean changed = fresh;
        List<ManagedElement> managed = new ArrayList<>();
        List<String> userOverrides = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> sec
                : ensure.entrySet()) {
            String sectionName = sec.getKey();
            for (Map.Entry<String, String> kv
                    : sec.getValue().entrySet()) {
                String path = configPath(sectionName, kv.getKey());
                if (!doc.hasKey(sectionName, kv.getKey())) {
                    doc.setKey(
                            sectionName, kv.getKey(), kv.getValue());
                    changed = true;
                    managed.add(new ManagedElement(
                            path, Instant.now(),
                            currentStandardsVersion));
                } else {
                    String existing = doc.getKey(
                            sectionName, kv.getKey());
                    if (!kv.getValue().equals(existing)) {
                        userOverrides.add(path);
                    }
                    ManagedElement prior = priorByPath.get(path);
                    managed.add(prior != null
                            ? prior
                            : new ManagedElement(
                                    path, Instant.now(),
                                    currentStandardsVersion));
                }
            }
        }

        if (!changed) {
            byte[] currentBytes = currentContent;
            String sha = Sha256.of(currentBytes);
            if (!userOverrides.isEmpty()) {
                return new ModelPlanResult(
                        new TierAction.UserManaged(
                                entry, resolvedDest, sha, sha,
                                userOverrideReason(userOverrides)),
                        managed);
            }
            return new ModelPlanResult(
                    new TierAction.UpToDate(
                            entry, resolvedDest, sha, sha,
                            "up to date"),
                    managed);
        }

        byte[] newBytes = doc.render()
                .getBytes(StandardCharsets.UTF_8);
        String sha = Sha256.of(newBytes);
        TierAction.Write.Kind kind = fresh
                ? TierAction.Write.Kind.INSTALL
                : TierAction.Write.Kind.UPDATE;
        int priorCount = priorEntry == null
                ? 0
                : priorEntry.managedElements().size();
        int added = managed.size() - priorCount;
        String reason = fresh
                ? "install git config with "
                        + managed.size() + " key(s)"
                : "ensure " + Math.max(added, 0) + " key(s)";
        return new ModelPlanResult(
                new TierAction.Write(
                        entry, resolvedDest, newBytes,
                        sha, sha, kind, reason),
                managed);
    }

    // ── helpers ────────────────────────────────────────────────────

    private static String configPath(String section, String key) {
        return "[" + section + "]." + key;
    }

    private static String userOverrideReason(List<String> paths) {
        String first = paths.get(0);
        if (paths.size() == 1) {
            return "deferred to user value for " + first;
        }
        return "deferred to user values for " + first
                + " and " + (paths.size() - 1) + " other(s)";
    }

    private static Map<String, ManagedElement> indexByPath(
            LockfileEntry priorEntry) {
        if (priorEntry == null
                || priorEntry.managedElements().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ManagedElement> out = new LinkedHashMap<>();
        for (ManagedElement e : priorEntry.managedElements()) {
            out.put(e.path(), e);
        }
        return out;
    }

    private static Map<String, Map<String, String>> readEnsureSections(
            ManifestEntry entry) {
        Object ensure = entry.extras().get("ensure");
        if (ensure == null) {
            return Collections.emptyMap();
        }
        if (!(ensure instanceof Map<?, ?> ensureMap)) {
            throw new ScaffoldException(
                    "git-config entry '" + entry.dest()
                            + "': 'ensure' must be a mapping");
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> sec : ensureMap.entrySet()) {
            String sectionName = sec.getKey().toString();
            if (!(sec.getValue() instanceof Map<?, ?> kvMap)) {
                throw new ScaffoldException(
                        "git-config entry '" + entry.dest()
                                + "': 'ensure." + sectionName
                                + "' must be a mapping");
            }
            Map<String, String> kv = new LinkedHashMap<>();
            for (Map.Entry<?, ?> pair : kvMap.entrySet()) {
                kv.put(pair.getKey().toString(),
                        pair.getValue() == null
                                ? ""
                                : pair.getValue().toString());
            }
            out.put(sectionName, kv);
        }
        return out;
    }

    // ── minimal git-config document model ──────────────────────────

    /**
     * Very small git-config model. Sufficient for round-tripping
     * existing files and ensuring keys, not a full implementation of
     * git's config parser.
     */
    static final class GitConfigDoc {

        private final List<Line> lines;

        private GitConfigDoc(List<Line> lines) {
            this.lines = lines;
        }

        static GitConfigDoc empty() {
            return new GitConfigDoc(new ArrayList<>());
        }

        static GitConfigDoc parse(String text) {
            List<Line> out = new ArrayList<>();
            String currentSection = null;
            for (String raw : text.split("\n", -1)) {
                String trimmed = raw.trim();
                if (trimmed.startsWith("[")
                        && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(
                            1, trimmed.length() - 1).trim();
                    out.add(new Line(
                            Line.Type.SECTION,
                            currentSection, null, null, raw));
                } else if (trimmed.isEmpty()
                        || trimmed.startsWith("#")
                        || trimmed.startsWith(";")) {
                    out.add(new Line(
                            Line.Type.OTHER,
                            currentSection, null, null, raw));
                } else {
                    int eq = raw.indexOf('=');
                    if (eq < 0) {
                        out.add(new Line(
                                Line.Type.OTHER,
                                currentSection, null, null, raw));
                    } else {
                        String k = raw.substring(0, eq).trim();
                        String v = raw.substring(eq + 1).trim();
                        out.add(new Line(
                                Line.Type.KEY,
                                currentSection, k, v, raw));
                    }
                }
            }
            // trailing '\n' split produces an empty OTHER — drop only
            // if the original text ended exactly on a newline.
            if (text.endsWith("\n")
                    && !out.isEmpty()
                    && out.get(out.size() - 1).type == Line.Type.OTHER
                    && out.get(out.size() - 1).raw.isEmpty()) {
                out.remove(out.size() - 1);
            }
            return new GitConfigDoc(out);
        }

        boolean hasKey(String section, String key) {
            for (Line l : lines) {
                if (l.type == Line.Type.KEY
                        && section.equals(l.section)
                        && key.equals(l.key)) {
                    return true;
                }
            }
            return false;
        }

        String getKey(String section, String key) {
            for (Line l : lines) {
                if (l.type == Line.Type.KEY
                        && section.equals(l.section)
                        && key.equals(l.key)) {
                    return l.value;
                }
            }
            return null;
        }

        void setKey(String section, String key, String value) {
            int sectionIdx = -1;
            int afterLast = -1;
            for (int i = 0; i < lines.size(); i++) {
                Line l = lines.get(i);
                if (l.type == Line.Type.SECTION
                        && section.equals(l.section)) {
                    sectionIdx = i;
                }
                if (sectionIdx >= 0
                        && section.equals(l.section)) {
                    afterLast = i;
                }
            }
            String rendered = "\t" + key + " = " + value;
            if (sectionIdx < 0) {
                // append new section + key at end
                lines.add(new Line(
                        Line.Type.SECTION, section, null, null,
                        "[" + section + "]"));
                lines.add(new Line(
                        Line.Type.KEY, section, key, value, rendered));
            } else {
                lines.add(afterLast + 1, new Line(
                        Line.Type.KEY, section, key, value, rendered));
            }
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i).raw);
                if (i < lines.size() - 1) {
                    sb.append('\n');
                }
            }
            if (sb.length() == 0
                    || sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            return sb.toString();
        }

        static final class Line {
            enum Type { SECTION, KEY, OTHER }

            final Type type;
            final String section;
            final String key;
            final String value;
            final String raw;

            Line(Type type, String section, String key,
                 String value, String raw) {
                this.type = type;
                this.section = section;
                this.key = key;
                this.value = value;
                this.raw = raw;
            }
        }
    }
}
