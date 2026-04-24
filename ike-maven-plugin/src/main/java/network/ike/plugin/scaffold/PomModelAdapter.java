package network.ike.plugin.scaffold;

import org.openrewrite.Tree;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model adapter for {@code pom.xml} via OpenRewrite's XML LST.
 *
 * <p>POM writes always go through OpenRewrite (not regex, not the
 * Maven 4 model API) so formatting, comments, and whitespace survive
 * round-trips.
 *
 * <p>Supported {@code ensure} subtree:
 * <pre>{@code
 * ensure:
 *   pluginManagement:
 *     - groupId: network.ike.tooling
 *       artifactId: ike-maven-plugin
 *       version: 127-SNAPSHOT
 * }</pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>If the destination POM is missing, publish skips with an
 *       informational message — creating a POM from scratch is
 *       outside scaffold's responsibility.</li>
 *   <li>For each ensured plugin, the adapter checks whether a matching
 *       {@code <plugin>} with the same {@code groupId + artifactId}
 *       exists anywhere under {@code /project/build/pluginManagement/plugins}.
 *       If absent it is appended (the whole {@code pluginManagement}
 *       scaffold is also created if missing). If present, the version
 *       is <em>not</em> changed — that is the job of
 *       {@code ws:align-publish}, not scaffold.</li>
 *   <li>Each ensured plugin is recorded as a {@link ManagedElement}
 *       with path
 *       {@code "/project/build/pluginManagement/plugins/plugin[groupId='G' and artifactId='A']"}.</li>
 * </ul>
 */
public final class PomModelAdapter implements ModelAdapter {

    /** Model name matching {@link ManifestEntry#model()}. */
    public static final String MODEL_NAME = "pom-openrewrite";

    private static final XmlParser PARSER = new XmlParser();

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

        List<PluginEnsure> ensured = readEnsuredPlugins(entry);

        if (currentContent == null || currentContent.length == 0) {
            // Creating a POM from scratch is not in scope.
            return new ModelPlanResult(
                    new TierAction.Skip(
                            entry, resolvedDest,
                            "POM does not exist; scaffold will not "
                                    + "create a pom.xml",
                            ""),
                    List.of());
        }

        String pomText = new String(
                currentContent, StandardCharsets.UTF_8);
        Xml.Document doc = parse(pomText);
        if (doc == null) {
            throw new ScaffoldException(
                    "Cannot parse POM at " + resolvedDest);
        }

        List<String> currentlyPresent =
                collectPluginCoordinates(doc);
        Map<String, ManagedElement> priorByPath =
                indexByPath(priorEntry);

        boolean changed = false;
        List<ManagedElement> managed = new ArrayList<>();
        Xml.Document updated = doc;
        for (PluginEnsure p : ensured) {
            String coord = p.groupId() + ":" + p.artifactId();
            String path = pluginPath(p);
            if (!currentlyPresent.contains(coord)) {
                updated = addPluginToPluginManagement(updated, p);
                changed = true;
                managed.add(new ManagedElement(
                        path, Instant.now(),
                        currentStandardsVersion));
            } else {
                ManagedElement prior = priorByPath.get(path);
                managed.add(prior != null
                        ? prior
                        : new ManagedElement(
                                path, Instant.now(),
                                currentStandardsVersion));
            }
        }

        if (!changed) {
            String sha = Sha256.of(currentContent);
            return new ModelPlanResult(
                    new TierAction.UpToDate(
                            entry, resolvedDest, sha, sha,
                            "up to date"),
                    managed);
        }

        byte[] newBytes = updated.printAll()
                .getBytes(StandardCharsets.UTF_8);
        String sha = Sha256.of(newBytes);
        int priorCount = priorEntry == null
                ? 0
                : priorEntry.managedElements().size();
        int added = managed.size() - priorCount;
        return new ModelPlanResult(
                new TierAction.Write(
                        entry, resolvedDest, newBytes,
                        sha, sha,
                        TierAction.Write.Kind.UPDATE,
                        "ensure " + Math.max(added, 0)
                                + " plugin(s) in pluginManagement"),
                managed);
    }

    // ── helpers ────────────────────────────────────────────────────

    private record PluginEnsure(
            String groupId, String artifactId, String version) {
    }

    private static String pluginPath(PluginEnsure p) {
        return "/project/build/pluginManagement/plugins/"
                + "plugin[groupId='" + p.groupId()
                + "' and artifactId='" + p.artifactId() + "']";
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

    private static List<PluginEnsure> readEnsuredPlugins(
            ManifestEntry entry) {
        Object ensure = entry.extras().get("ensure");
        if (ensure == null) {
            return Collections.emptyList();
        }
        if (!(ensure instanceof Map<?, ?> ensureMap)) {
            throw new ScaffoldException(
                    "pom-openrewrite entry '" + entry.dest()
                            + "': 'ensure' must be a mapping");
        }
        Object pm = ensureMap.get("pluginManagement");
        if (pm == null) {
            return Collections.emptyList();
        }
        if (!(pm instanceof List<?> pmList)) {
            throw new ScaffoldException(
                    "pom-openrewrite entry '" + entry.dest()
                            + "': 'ensure.pluginManagement' must be "
                            + "a list");
        }
        List<PluginEnsure> out = new ArrayList<>();
        int idx = 0;
        for (Object o : pmList) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ScaffoldException(
                        "pom-openrewrite entry '" + entry.dest()
                                + "': ensure.pluginManagement["
                                + idx + "] must be a mapping");
            }
            String g = stringVal(m, "groupId");
            String a = stringVal(m, "artifactId");
            String v = stringVal(m, "version");
            if (g == null || a == null) {
                throw new ScaffoldException(
                        "pom-openrewrite entry '" + entry.dest()
                                + "': ensure.pluginManagement["
                                + idx + "] missing groupId/artifactId");
            }
            out.add(new PluginEnsure(g, a, v == null ? "" : v));
            idx++;
        }
        return out;
    }

    private static String stringVal(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Xml.Document parse(String text) {
        return PARSER.parse(text)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElse(null);
    }

    private static List<String> collectPluginCoordinates(
            Xml.Document doc) {
        List<String> found = new ArrayList<>();
        new XmlVisitor<List<String>>() {
            @Override
            public Xml visitTag(Xml.Tag tag, List<String> ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"plugin".equals(t.getName())) {
                    return t;
                }
                Optional<String> g = t.getChildValue("groupId");
                Optional<String> a = t.getChildValue("artifactId");
                g.ifPresent(gid -> a.ifPresent(aid ->
                        ctx.add(gid + ":" + aid)));
                return t;
            }
        }.visit(doc, found);
        return found;
    }

    /**
     * Append a {@code <plugin>} entry under
     * {@code /project/build/pluginManagement/plugins}, creating any
     * missing ancestor tags.
     */
    private static Xml.Document addPluginToPluginManagement(
            Xml.Document doc, PluginEnsure p) {
        Xml.Tag pluginTag = buildPluginTag(p);

        return (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"project".equals(t.getName())) {
                    return t;
                }
                Xml.Tag build = childOrCreate(t, "build");
                Xml.Tag pluginManagement =
                        childOrCreate(build, "pluginManagement");
                Xml.Tag plugins =
                        childOrCreate(pluginManagement, "plugins");
                Xml.Tag appendedPlugins = appendChild(
                        plugins, pluginTag);
                Xml.Tag newPluginManagement = replaceChild(
                        pluginManagement, plugins, appendedPlugins);
                Xml.Tag newBuild = replaceChild(
                        build, pluginManagement, newPluginManagement);
                return replaceChild(t, build, newBuild);
            }
        }.visitNonNull(doc, 0);
    }

    private static Xml.Tag buildPluginTag(PluginEnsure p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<plugin>\n");
        sb.append("    <groupId>").append(p.groupId())
                .append("</groupId>\n");
        sb.append("    <artifactId>").append(p.artifactId())
                .append("</artifactId>\n");
        if (!p.version().isBlank()) {
            sb.append("    <version>").append(p.version())
                    .append("</version>\n");
        }
        sb.append("</plugin>");
        Xml.Document frag = PARSER.parse(sb.toString())
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElseThrow(() -> new ScaffoldException(
                        "cannot build <plugin> fragment"));
        return frag.getRoot();
    }

    private static Xml.Tag childOrCreate(
            Xml.Tag parent, String name) {
        return parent.getChild(name)
                .orElseGet(() -> emptyTag(name));
    }

    private static Xml.Tag emptyTag(String name) {
        Xml.Document frag = PARSER.parse(
                "<" + name + "/>").findFirst()
                .map(t -> (Xml.Document) t)
                .orElseThrow(() -> new ScaffoldException(
                        "cannot build empty <" + name + "/> tag"));
        return frag.getRoot();
    }

    private static Xml.Tag appendChild(Xml.Tag parent, Xml.Tag child) {
        List<org.openrewrite.xml.tree.Content> content =
                new ArrayList<>();
        if (parent.getContent() != null) {
            content.addAll(parent.getContent());
        }
        content.add(child);
        return parent.withContent(content);
    }

    private static Xml.Tag replaceChild(
            Xml.Tag parent,
            Xml.Tag oldChild,
            Xml.Tag newChild) {
        if (oldChild == newChild) {
            if (parent.getContent() == null
                    || !parent.getContent().contains(oldChild)) {
                return appendChild(parent, newChild);
            }
            return parent;
        }
        List<? extends org.openrewrite.xml.tree.Content> oldContent =
                parent.getContent() == null
                        ? List.of()
                        : parent.getContent();
        if (!oldContent.contains(oldChild)) {
            return appendChild(parent, newChild);
        }
        List<org.openrewrite.xml.tree.Content> newContent =
                new ArrayList<>(oldContent.size());
        for (org.openrewrite.xml.tree.Content c : oldContent) {
            newContent.add(c == oldChild ? newChild : c);
        }
        return parent.withContent(newContent);
    }

    /** Unused reference to silence analyzers. */
    @SuppressWarnings("unused")
    private static final Class<?> TREE_REF = Tree.class;
}
