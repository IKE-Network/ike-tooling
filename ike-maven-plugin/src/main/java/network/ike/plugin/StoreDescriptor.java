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
package network.ike.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * What an assembled store says about itself, in the files any Maven or JDK tool already
 * reads, so that a program with no Tinkar code can tell which build a folder under
 * {@code ~/Solor} is (the IKE Dashboard design, {@code dev-ike-dashboard} in
 * ike-lab-documents):
 *
 * <pre>
 * META-INF/maven/&lt;groupId&gt;/&lt;artifactId&gt;/pom.properties   groupId, artifactId, version
 * META-INF/maven/&lt;groupId&gt;/&lt;artifactId&gt;/pom.xml          the project's POM
 * META-INF/MANIFEST.MF                                  Created-By and the Ike-* attributes
 * META-INF/ike/icon.png, icon@2x.png                    the knowledge base's icon, when it has one
 * </pre>
 *
 * <p>The descriptor is the one maven-archiver writes into every jar, at the same path; the
 * coordinate it holds is the one the store's zips publish under, without a classifier. The
 * manifest is merged: attributes already there are kept unless written again.
 */
final class StoreDescriptor {

    /** The manifest attribute naming the version of the Komet launch interface the store follows. */
    static final String LAUNCH_INTERFACE_VERSION = "Ike-Launch-Interface-Version";

    /** The version of the Komet launch interface stores are written to. */
    static final String LAUNCH_INTERFACE = "1";

    /** The manifest attribute holding when the store was assembled. */
    static final String ASSEMBLED_AT = "Ike-Assembled-At";

    /** The manifest attribute naming the reasoner that classified the store. */
    static final String REASONER = "Ike-Reasoner";

    /** The manifest attribute naming the store's icon, a path relative to the store root. */
    static final String ICON = "Ike-Icon";

    /** Where a store carries its icon, relative to the store root. */
    static final String ICON_ENTRY = "META-INF/ike/icon.png";

    /** Where a knowledge-base project keeps its icon, relative to its base directory. */
    static final String ICON_SOURCE = "src/main/kb/icon.png";

    private StoreDescriptor() {
    }

    /**
     * The icon a knowledge-base project keeps at {@value #ICON_SOURCE}, when it has one.
     *
     * @param basedir the project's base directory
     * @return the icon file, or empty when the project has none
     */
    static Optional<Path> icon(Path basedir) {
        return Optional.of(basedir.resolve(ICON_SOURCE)).filter(Files::isRegularFile);
    }

    /** The high-resolution companion of an icon: {@code icon@2x.png} beside {@code icon.png}. */
    private static Path doubleResolution(Path icon) {
        String name = icon.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return icon.resolveSibling(name.substring(0, dot) + "@2x" + name.substring(dot));
    }

    /**
     * Writes the Maven descriptor and merges the manifest attributes into a store directory.
     *
     * @param store      the store directory
     * @param groupId    the coordinate's groupId
     * @param artifactId the coordinate's artifactId
     * @param version    the coordinate's version
     * @param pom        the POM to copy beside {@code pom.properties}, when there is one
     * @param createdBy  the tool that wrote the store, for the manifest's {@code Created-By}
     * @param icon       the knowledge base's icon, copied to {@value #ICON_ENTRY} with its
     *                   {@code @2x} companion and named by {@value #ICON}; when empty, an icon
     *                   an earlier build left is removed
     * @param attributes the {@code Ike-*} attributes, by name
     * @throws IOException if a file cannot be written
     */
    static void write(Path store, String groupId, String artifactId, String version, Optional<Path> pom,
                      String createdBy, Optional<Path> icon, Map<String, String> attributes) throws IOException {
        Path maven = store.resolve("META-INF").resolve("maven").resolve(groupId).resolve(artifactId);
        Files.createDirectories(maven);
        // Written as maven-archiver writes it: no timestamp, so a rebuild of the same project
        // writes the same bytes.
        Files.writeString(maven.resolve("pom.properties"), "#Created by " + createdBy + "\n"
                + "artifactId=" + artifactId + "\n"
                + "groupId=" + groupId + "\n"
                + "version=" + version + "\n", StandardCharsets.ISO_8859_1);
        if (pom.isPresent()) {
            Files.copy(pom.get(), maven.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
        }

        Path storedIcon = store.resolve(ICON_ENTRY);
        Path storedDouble = doubleResolution(storedIcon);
        Files.deleteIfExists(storedIcon);
        Files.deleteIfExists(storedDouble);
        if (icon.isPresent()) {
            Files.createDirectories(storedIcon.getParent());
            Files.copy(icon.get(), storedIcon);
            Path sourceDouble = doubleResolution(icon.get());
            if (Files.isRegularFile(sourceDouble)) {
                Files.copy(sourceDouble, storedDouble);
            }
        }

        Path file = store.resolve("META-INF").resolve("MANIFEST.MF");
        Manifest manifest = new Manifest();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                manifest.read(in);
            }
        }
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Created-By", createdBy);
        new TreeMap<>(attributes).forEach(main::putValue);
        if (icon.isPresent()) {
            main.putValue(ICON, ICON_ENTRY);
        } else {
            main.remove(new Attributes.Name(ICON));
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            manifest.write(out);
        }
    }
}
