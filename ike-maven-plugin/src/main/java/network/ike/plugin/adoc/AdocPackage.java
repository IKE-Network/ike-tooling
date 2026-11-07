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
 * One package a library ships: a directory under the library's root, named by the owning
 * group as one dotted segment and then the domain, such as {@code network.ike.lab/dev}. A
 * library whose group ends in its only domain uses the group directory itself, such as
 * {@code network.ike.kompendium.anf}.
 *
 * @param name the package path relative to the root, with forward slashes
 * @param kind {@value #KIND_TOPICS} for topics, {@value #KIND_LIBRARY} for a library's own
 *             rendered content such as its preview index and shared images
 */
public record AdocPackage(String name, String kind) {

    /** The kind of a package that holds topics. */
    public static final String KIND_TOPICS = "topics";

    /** The kind of a package that holds a library's own preview index and shared images. */
    public static final String KIND_LIBRARY = "library";

    /**
     * Checks that both parts are present.
     *
     * @param name the package path
     * @param kind the package kind
     */
    public AdocPackage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * Whether a path relative to the root is inside this package.
     *
     * @param path a path relative to the root, with forward slashes
     * @return true when the path is under this package's directory
     */
    public boolean contains(String path) {
        return path.startsWith(name + "/");
    }
}
