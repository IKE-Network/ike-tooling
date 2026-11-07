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
package network.ike.plugin.adoc;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a module of the reactor by its pom, under the reactor root. A {@code -pl} build's
 * session lists only the selected projects, so a library that is a sibling module but not in
 * the build is found here instead, by the {@code groupId} and {@code artifactId} its pom
 * declares or inherits from its parent. Nothing is resolved: the answer is a directory.
 */
public final class ReactorLayout {

    /** Directories never walked: build output, hidden trees, sources and dependencies. */
    private static final Set<String> SKIPPED = Set.of("target", "src", "node_modules");

    /** How deep under the root a module's pom may be. */
    private static final int MAX_DEPTH = 6;

    private ReactorLayout() {
    }

    /**
     * The base directory of the module whose pom declares the given coordinates, or empty.
     *
     * @param root       the reactor root
     * @param groupId    the group
     * @param artifactId the artifact
     * @return the module's directory, when its pom is under the root
     */
    public static Optional<Path> memberBasedir(Path root, String groupId, String artifactId) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        // A skipped directory is never entered, not just filtered out afterwards: in a
        // parallel build another module's clean deletes its target while this walk runs,
        // and a walk inside it fails on the vanished entries (IKE-Network/ike-issues#1348).
        Path[] found = new Path[1];
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && skipped(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().equals("pom.xml")
                            && declares(file, groupId, artifactId)) {
                        found[0] = file.getParent();
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // An entry another build deleted, or one that cannot be read, holds no
                    // module this search could use.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.ofNullable(found[0]);
    }

    private static boolean skipped(String name) {
        return SKIPPED.contains(name) || name.startsWith(".");
    }

    /** Whether a pom's own or inherited group and its artifact are the ones asked for. */
    static boolean declares(Path pom, String groupId, String artifactId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(pom.toFile());
            Element project = document.getDocumentElement();
            if (project == null || !"project".equals(project.getLocalName() == null
                    ? project.getTagName() : project.getLocalName())) {
                return false;
            }
            String artifact = childText(project, "artifactId");
            if (!artifactId.equals(artifact)) {
                return false;
            }
            String group = childText(project, "groupId");
            if (group == null) {
                Element parent = child(project, "parent");
                group = parent == null ? null : childText(parent, "groupId");
            }
            return groupId.equals(group);
        } catch (Exception e) {
            // A pom that does not parse is not the module looked for.
            return false;
        }
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                String local = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
                if (name.equals(local)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }
}
