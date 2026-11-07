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

import java.util.Objects;

/**
 * The Maven coordinates of a topic library: the artifact that ships a set of packages. The
 * classifier is always {@code adoc} and the type always {@code zip}, so neither is carried.
 *
 * @param groupId    the group, for example {@code network.ike.lab}
 * @param artifactId the artifact, for example {@code topics}
 * @param version    the version the library was declared or found at
 */
public record AdocCoordinates(String groupId, String artifactId, String version) {

    /** The classifier every topic library zip carries. */
    public static final String CLASSIFIER = "adoc";

    /** The type every topic library zip carries. */
    public static final String TYPE = "zip";

    /** The reserved area a library's own metadata is under, keyed by its coordinates. */
    public static final String META_INF_IKE = "META-INF/ike";

    /**
     * Checks that every part is present.
     *
     * @param groupId    the group
     * @param artifactId the artifact
     * @param version    the version
     */
    public AdocCoordinates {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
    }

    /**
     * The group and artifact, {@code group:artifact}, which is how a manifest names them.
     *
     * @return the group and artifact joined by a colon
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * The full coordinate as a build log prints it,
     * {@code group:artifact:zip:adoc:version}.
     *
     * @return the coordinate with classifier and type
     */
    public String gav() {
        return groupId + ":" + artifactId + ":" + TYPE + ":" + CLASSIFIER + ":" + version;
    }

    /**
     * The library's metadata directory inside a zip or the union, relative to the root:
     * {@code META-INF/ike/<group>/<artifact>}.
     *
     * @return the relative path, with forward slashes
     */
    public String metaInfPath() {
        return META_INF_IKE + "/" + groupId + "/" + artifactId;
    }

    /**
     * The library's manifest inside a zip or the union, relative to the root.
     *
     * @return the relative path of {@code manifest.yaml}, with forward slashes
     */
    public String manifestPath() {
        return metaInfPath() + "/" + AdocManifest.FILE_NAME;
    }

    /**
     * The {@code <dependency>} block that declares this library, indented for a module's
     * {@code pom.xml}. This is what the build prints for an include that nothing declared
     * ships.
     *
     * @return the XML block, eight spaces in, ending with a newline
     */
    public String dependencyBlock() {
        return """
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>
                            <classifier>%s</classifier>
                            <type>%s</type>
                        </dependency>
                """.formatted(groupId, artifactId, version, CLASSIFIER, TYPE);
    }
}
