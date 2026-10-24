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

    private StoreDescriptor() {
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
     * @param attributes the {@code Ike-*} attributes, by name
     * @throws IOException if a file cannot be written
     */
    static void write(Path store, String groupId, String artifactId, String version, Optional<Path> pom,
                      String createdBy, Map<String, String> attributes) throws IOException {
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
        try (OutputStream out = Files.newOutputStream(file)) {
            manifest.write(out);
        }
    }
}
