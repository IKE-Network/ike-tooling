/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.plugin.schema;

import network.ike.plugin.schema.SchemaSignature.Enumeration;
import network.ike.plugin.schema.SchemaSignature.Position;
import network.ike.plugin.schema.SchemaSignature.PositionKind;
import network.ike.plugin.schema.SchemaSignature.TypeDefinition;
import network.ike.plugin.schema.SchemaSignature.TypeReference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads XML Schema files into a {@link SchemaSignature} with the JDK's own XML support and
 * nothing else (IKE-Network/ike-issues#1104). It reads what a signature is: named complex
 * types, their {@code extension base}, their documentation, their element and attribute
 * positions, and named simple types that enumerate values. Schema features a signature
 * does not need, choices, groups, and attribute groups, are refused rather than silently
 * dropped, so that a schema outside the reader's reach fails loudly.
 *
 * <p>The reader is namespace-aware and matches schema elements by the XML Schema
 * namespace, so the prefix a file uses, {@code xs} or {@code xsd}, does not matter. A
 * type is identified by its namespace and its name: a bare reference resolves to the
 * file's default namespace, or its target namespace when it declares none, and a prefixed
 * reference through the file's own namespace declarations, so {@code xs:string} is a
 * schema primitive and {@code a:CqlToElmBase} a type of another schema.
 */
public final class XmlSchemaReader {

    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    private final DocumentBuilder builder;

    /**
     * Creates a reader with a secure, namespace-aware document builder.
     *
     * @throws IllegalStateException when the JDK cannot provide a document builder
     */
    public XmlSchemaReader() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The JDK cannot provide a secure XML parser", e);
        }
    }

    /**
     * Reads one or more schema files into a single signature, in the order given. Types
     * are collected across all files, so a base declared in one file and extended in
     * another resolves.
     *
     * @param schemas the schema files
     * @return the signature the files declare
     * @throws IOException      when a file cannot be read or is not well-formed XML
     * @throws IllegalArgumentException when a file uses a schema feature the reader refuses
     */
    public SchemaSignature read(List<Path> schemas) throws IOException {
        List<TypeDefinition> types = new ArrayList<>();
        List<Enumeration> enumerations = new ArrayList<>();
        Map<TypeReference, Path> seen = new LinkedHashMap<>();
        for (Path schema : schemas) {
            try (InputStream in = Files.newInputStream(schema)) {
                Document document = builder.parse(in);
                Element root = document.getDocumentElement();
                refuseUnsupported(root, schema);
                Namespaces namespaces = new Namespaces(root);
                for (Element complexType : children(root, "complexType")) {
                    TypeDefinition type = readType(complexType, namespaces);
                    Path earlier = seen.putIfAbsent(type.reference(), schema);
                    if (earlier != null) {
                        throw new IllegalArgumentException("Type " + type.name() + " of namespace "
                                + type.namespace() + " is declared in " + earlier + " and again in " + schema);
                    }
                    types.add(type);
                }
                for (Element simpleType : children(root, "simpleType")) {
                    readEnumeration(simpleType, namespaces).ifPresent(enumerations::add);
                }
            } catch (org.xml.sax.SAXException e) {
                throw new IOException("Schema " + schema + " is not well-formed XML: " + e.getMessage(), e);
            }
        }
        return new SchemaSignature(types, enumerations);
    }

    private static void refuseUnsupported(Element root, Path schema) {
        for (String feature : List.of("choice", "group", "attributeGroup")) {
            if (root.getElementsByTagNameNS(XSD, feature).getLength() > 0) {
                throw new IllegalArgumentException("Schema " + schema + " uses xs:" + feature
                        + ", which the signature reader does not read");
            }
        }
    }

    private static TypeDefinition readType(Element complexType, Namespaces namespaces) {
        String name = complexType.getAttribute("name");
        boolean isAbstract = "true".equals(complexType.getAttribute("abstract"));
        String documentation = documentation(complexType);
        Optional<TypeReference> base = Optional.empty();
        Element content = complexType;
        Element complexContent = firstChild(complexType, "complexContent");
        if (complexContent != null) {
            Element extension = firstChild(complexContent, "extension");
            if (extension != null) {
                base = Optional.of(namespaces.resolve(extension.getAttribute("base")));
                content = extension;
            } else {
                Element restriction = firstChild(complexContent, "restriction");
                if (restriction != null) {
                    content = restriction;
                }
            }
        }
        List<Position> positions = new ArrayList<>();
        Element sequence = firstChild(content, "sequence");
        if (sequence != null) {
            for (Element element : children(sequence, "element")) {
                positions.add(readElement(element, namespaces));
            }
        }
        for (Element attribute : children(content, "attribute")) {
            positions.add(readAttribute(attribute, namespaces));
        }
        return new TypeDefinition(namespaces.target(), name, base, isAbstract, documentation, positions);
    }

    private static Position readElement(Element element, Namespaces namespaces) {
        String name = element.getAttribute("name");
        TypeReference type = element.hasAttribute("type")
                ? namespaces.resolve(element.getAttribute("type")) : TypeReference.primitive("anyType");
        int minimum = element.hasAttribute("minOccurs") ? Integer.parseInt(element.getAttribute("minOccurs")) : 1;
        int maximum = 1;
        if (element.hasAttribute("maxOccurs")) {
            String max = element.getAttribute("maxOccurs");
            maximum = "unbounded".equals(max) ? Position.UNBOUNDED : Integer.parseInt(max);
        }
        return new Position(name, PositionKind.ELEMENT, type, minimum, maximum, documentation(element));
    }

    private static Position readAttribute(Element attribute, Namespaces namespaces) {
        String name = attribute.getAttribute("name");
        TypeReference type = attribute.hasAttribute("type")
                ? namespaces.resolve(attribute.getAttribute("type")) : TypeReference.primitive("string");
        int minimum = "required".equals(attribute.getAttribute("use")) ? 1 : 0;
        return new Position(name, PositionKind.ATTRIBUTE, type, minimum, 1, documentation(attribute));
    }

    private static Optional<Enumeration> readEnumeration(Element simpleType, Namespaces namespaces) {
        Element restriction = firstChild(simpleType, "restriction");
        if (restriction == null) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        for (Element enumeration : children(restriction, "enumeration")) {
            values.add(enumeration.getAttribute("value"));
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Enumeration(namespaces.target(), simpleType.getAttribute("name"),
                documentation(simpleType), values));
    }

    /** One schema file's namespace declarations, for resolving the names it uses. */
    private record Namespaces(String target, String defaultNamespace, Element root) {
        Namespaces(Element root) {
            this(root.hasAttribute("targetNamespace") ? root.getAttribute("targetNamespace") : "",
                    root.lookupNamespaceURI(null), root);
        }

        /**
         * Resolves a possibly prefixed name the way XML Schema does: a prefix through the
         * file's declarations, no prefix through the default namespace, or the target
         * namespace when the file declares no default.
         */
        TypeReference resolve(String qualified) {
            int colon = qualified.indexOf(':');
            if (colon < 0) {
                return new TypeReference(defaultNamespace != null ? defaultNamespace : target, qualified);
            }
            String prefix = qualified.substring(0, colon);
            String local = qualified.substring(colon + 1);
            String namespace = root.lookupNamespaceURI(prefix);
            if (namespace == null) {
                throw new IllegalArgumentException("Prefix " + prefix + " in " + qualified
                        + " is not declared by the schema");
            }
            return new TypeReference(namespace, local);
        }
    }

    private static String documentation(Element owner) {
        Element annotation = firstChild(owner, "annotation");
        if (annotation == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Element documentation : children(annotation, "documentation")) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(documentation.getTextContent());
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static Element firstChild(Element parent, String localName) {
        for (Element child : children(parent, localName)) {
            return child;
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && XSD.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                matches.add((Element) node);
            }
        }
        return matches;
    }
}
