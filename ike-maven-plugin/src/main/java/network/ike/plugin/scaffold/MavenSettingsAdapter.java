package network.ike.plugin.scaffold;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model adapter for {@code ~/.m2/settings.xml} (Maven Settings 1.2.0).
 *
 * <p>Supported {@code ensure} subtree:
 * <pre>{@code
 * ensure:
 *   pluginGroups:
 *     - network.ike.tooling
 * }</pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>If the file does not exist, a minimal
 *       {@code <settings xmlns="...">} document is created.</li>
 *   <li>Each {@code pluginGroup} in {@code ensure.pluginGroups} that
 *       is not already present is appended. Existing entries — and any
 *       unrelated elements (servers, profiles, …) — are left
 *       untouched.</li>
 *   <li>Each installed or re-confirmed {@code pluginGroup} is recorded
 *       as a {@link ManagedElement} with path
 *       {@code "/settings/pluginGroups/pluginGroup[text()='G']"}.</li>
 * </ul>
 *
 * <p>DOM-based so unmanaged content (comments, whitespace outside the
 * managed region, unrelated child elements) is preserved on round-trip
 * to the extent the Transformer supports it.
 */
public final class MavenSettingsAdapter implements ModelAdapter {

    /** Model name matching {@link ManifestEntry#model()}. */
    public static final String MODEL_NAME = "maven-settings-4";

    static final String SETTINGS_NS =
            "http://maven.apache.org/SETTINGS/1.2.0";

    /**
     * Construct a stateless Maven-settings adapter. Instances are safe
     * to share across planning calls; all per-invocation state lives
     * on method parameters.
     */
    public MavenSettingsAdapter() {
    }

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

        List<String> ensuredGroups = readEnsuredPluginGroups(entry);

        boolean fresh = currentContent == null
                || currentContent.length == 0;
        Document doc = fresh
                ? newSettingsDocument()
                : parseXml(currentContent);
        Element root = doc.getDocumentElement();

        Element pluginGroupsEl = findOrCreateChild(
                doc, root, "pluginGroups");

        List<String> existingGroups = readChildValues(
                pluginGroupsEl, "pluginGroup");
        Map<String, ManagedElement> priorByPath =
                indexByPath(priorEntry);

        boolean changed = fresh;
        List<ManagedElement> managed = new ArrayList<>();
        for (String group : ensuredGroups) {
            String path = pluginGroupPath(group);
            if (!existingGroups.contains(group)) {
                Element pg = doc.createElementNS(
                        SETTINGS_NS, "pluginGroup");
                pg.setTextContent(group);
                pluginGroupsEl.appendChild(pg);
                changed = true;
                managed.add(new ManagedElement(
                        path, Instant.now(), currentStandardsVersion));
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
            byte[] currentBytes = currentContent;
            String currentSha = Sha256.of(currentBytes);
            return new ModelPlanResult(
                    new TierAction.UpToDate(
                            entry, resolvedDest,
                            currentSha, currentSha,
                            "up to date"),
                    managed);
        }

        byte[] newBytes = serialize(doc);
        String newSha = Sha256.of(newBytes);
        TierAction.Write.Kind kind = fresh
                ? TierAction.Write.Kind.INSTALL
                : TierAction.Write.Kind.UPDATE;
        int added = managed.size()
                - (priorEntry == null
                        ? 0
                        : priorEntry.managedElements().size());
        String reason = fresh
                ? "install settings.xml with " + managed.size()
                        + " pluginGroup(s)"
                : (added > 0
                        ? "ensure " + added + " pluginGroup(s)"
                        : "refresh settings.xml");

        return new ModelPlanResult(
                new TierAction.Write(
                        entry, resolvedDest, newBytes,
                        newSha, newSha, kind, reason),
                managed);
    }

    // ── helpers ────────────────────────────────────────────────────

    private static String pluginGroupPath(String group) {
        return "/settings/pluginGroups/pluginGroup[text()='"
                + group + "']";
    }

    private static List<String> readEnsuredPluginGroups(
            ManifestEntry entry) {
        Object ensure = entry.extras().get("ensure");
        if (ensure == null) {
            return Collections.emptyList();
        }
        if (!(ensure instanceof Map<?, ?> ensureMap)) {
            throw new ScaffoldException(
                    "maven-settings-4 entry '" + entry.dest()
                            + "': 'ensure' must be a mapping");
        }
        Object groups = ensureMap.get("pluginGroups");
        if (groups == null) {
            return Collections.emptyList();
        }
        if (!(groups instanceof List<?> groupList)) {
            throw new ScaffoldException(
                    "maven-settings-4 entry '" + entry.dest()
                            + "': 'ensure.pluginGroups' must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object g : groupList) {
            if (g == null || g.toString().isBlank()) {
                throw new ScaffoldException(
                        "maven-settings-4 entry '" + entry.dest()
                                + "': blank pluginGroup entry");
            }
            out.add(g.toString());
        }
        return out;
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

    private static Document newSettingsDocument() {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            Document doc = builder.newDocument();
            Element root = doc.createElementNS(
                    SETTINGS_NS, "settings");
            root.setAttributeNS(
                    XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                    "xmlns:xsi",
                    "http://www.w3.org/2001/XMLSchema-instance");
            root.setAttributeNS(
                    "http://www.w3.org/2001/XMLSchema-instance",
                    "xsi:schemaLocation",
                    SETTINGS_NS + " https://maven.apache.org/xsd/"
                            + "settings-1.2.0.xsd");
            doc.appendChild(root);
            return doc;
        } catch (ParserConfigurationException e) {
            throw new ScaffoldException(
                    "cannot create settings document", e);
        }
    }

    private static Document parseXml(byte[] bytes) {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            return builder.parse(new InputSource(
                    new StringReader(new String(
                            bytes, StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new ScaffoldException(
                    "cannot parse settings.xml: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder newDocumentBuilder()
            throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(
                XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature(
                "http://apache.org/xml/features/"
                        + "disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f.newDocumentBuilder();
    }

    private static Element findOrCreateChild(
            Document doc, Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        Element created = doc.createElementNS(
                SETTINGS_NS, localName);
        parent.appendChild(created);
        return created;
    }

    private static List<String> readChildValues(
            Element parent, String localName) {
        List<String> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(n.getLocalName())) {
                String text = n.getTextContent();
                out.add(text == null ? "" : text.trim());
            }
        }
        return out;
    }

    private static byte[] serialize(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance()
                    .newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(
                    "{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(
                    OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            String out = sw.toString();
            if (!out.endsWith("\n")) {
                out = out + "\n";
            }
            return out.getBytes(StandardCharsets.UTF_8);
        } catch (TransformerException e) {
            throw new ScaffoldException(
                    "cannot serialize settings.xml", e);
        }
    }
}
