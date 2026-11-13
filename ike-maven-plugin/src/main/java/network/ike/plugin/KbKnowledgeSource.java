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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One artifact a new knowledge base loads, by Maven coordinate: a protobuf layer
 * ({@code groupId:artifactId:zip:classifier:version}) or a source release
 * ({@code groupId:artifactId:extension[:classifier]:version}). A layer starts as
 * requested and is then pinned (IKE-Network/ike-issues#1370, decision 9): a snapshot is
 * fixed to the exact timestamped deploy Nexus holds, so a later deploy under the same
 * snapshot version cannot change what the knowledge base is built from.
 *
 * @param groupId       the group
 * @param artifactId    the artifact
 * @param extension     the file extension, such as {@code zip}
 * @param classifier    the classifier, or an empty string for none
 * @param version       the version as requested
 * @param pinnedVersion the exact version to build from: a release as requested, a snapshot
 *                      by its timestamp; equal to {@code version} until pinned
 * @param sizeBytes     the artifact's size on the repository, or {@code -1} when not known
 */
record KbLayer(String groupId, String artifactId, String extension, String classifier,
               String version, String pinnedVersion, long sizeBytes) {

    /** The starter set's group: every knowledge base is built on it. */
    static final String STARTER_SET_GROUP = "network.ike.foundation";

    /** The starter set's artifact. */
    static final String STARTER_SET_ARTIFACT = "ike-starter-set";

    /** The classifier of an unclassified protobuf. */
    static final String UNREASONED = "unreasoned-pb";

    /** The classifier of a classified, standalone protobuf. */
    static final String REASONED = "reasoned-pb";

    /** A snapshot fixed to one deploy: {@code <base>-yyyyMMdd.HHmmss-<build>}. */
    private static final Pattern TIMESTAMPED = Pattern.compile("^(.+)-(\\d{8}\\.\\d{6})-(\\d+)$");

    /** The parts of a coordinate: no whitespace, no empty part. */
    private static final Pattern PART = Pattern.compile("[^:\\s]+");

    /**
     * Validates the parts.
     *
     * @throws IllegalArgumentException if a required part is blank
     */
    KbLayer {
        for (String part : new String[]{groupId, artifactId, extension, version, pinnedVersion}) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("A coordinate needs a groupId, artifactId,"
                        + " extension and version: " + groupId + ":" + artifactId + ":"
                        + extension + ":" + classifier + ":" + version);
            }
        }
        classifier = classifier == null ? "" : classifier;
    }

    /**
     * Reads a coordinate: {@code groupId:artifactId:extension:version} or
     * {@code groupId:artifactId:extension:classifier:version}.
     *
     * @param spec the coordinate
     * @return the unpinned layer
     * @throws IllegalArgumentException if the spec is not such a coordinate
     */
    static KbLayer parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("An empty coordinate");
        }
        String[] parts = spec.strip().split(":", -1);
        for (String part : parts) {
            if (!PART.matcher(part).matches()) {
                throw new IllegalArgumentException("Not a Maven coordinate: " + spec);
            }
        }
        return switch (parts.length) {
            case 4 -> new KbLayer(parts[0], parts[1], parts[2], "", parts[3], parts[3], -1);
            case 5 -> new KbLayer(parts[0], parts[1], parts[2], parts[3], parts[4], parts[4], -1);
            default -> throw new IllegalArgumentException("Not groupId:artifactId:extension[:classifier]:version: " + spec);
        };
    }

    /**
     * Whether a spec reads as a Maven coordinate rather than a file path.
     *
     * @param spec the spec
     * @return {@code true} for a coordinate
     */
    static boolean isCoordinate(String spec) {
        if (spec == null || spec.contains("/") || spec.contains("\\")) {
            return false;
        }
        try {
            parse(spec);
            return true;
        } catch (IllegalArgumentException notACoordinate) {
            return false;
        }
    }

    /**
     * This layer pinned to an exact version, with its size.
     *
     * @param exactVersion the exact version
     * @param bytes        the size on the repository
     * @return the pinned layer
     */
    KbLayer pinned(String exactVersion, long bytes) {
        return new KbLayer(groupId, artifactId, extension, classifier, version, exactVersion, bytes);
    }

    /**
     * Whether the requested version is a snapshot, timestamped or not.
     *
     * @return {@code true} for a snapshot
     */
    boolean snapshot() {
        return version.endsWith("-SNAPSHOT") || TIMESTAMPED.matcher(version).matches();
    }

    /**
     * Whether the requested version already names one deploy.
     *
     * @return {@code true} for a release or a timestamped snapshot
     */
    boolean exact() {
        return !version.endsWith("-SNAPSHOT");
    }

    /**
     * The folder a version lives in on a Maven repository: a timestamped snapshot lives
     * under its {@code -SNAPSHOT} base version.
     *
     * @param anyVersion a version of this artifact
     * @return the folder's version
     */
    static String baseVersion(String anyVersion) {
        Matcher timestamped = TIMESTAMPED.matcher(anyVersion);
        return timestamped.matches() ? timestamped.group(1) + "-SNAPSHOT" : anyVersion;
    }

    /**
     * The repository path of the artifact's folder for its requested version, ending in
     * {@code /}.
     *
     * @return such as {@code network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/}
     */
    String folderPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + baseVersion(version) + "/";
    }

    /**
     * The repository path of the artifact's file at its pinned version.
     *
     * @return such as {@code network/ike/data/snomedct-kb/20260601.2-SNAPSHOT/snomedct-kb-20260601.2-20261111.020518-1-unreasoned-pb.zip}
     */
    String filePath() {
        return folderPath() + artifactId + "-" + pinnedVersion
                + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
    }

    /**
     * The coordinate at the pinned version, as a {@code kb-assemble} input names it.
     *
     * @return {@code groupId:artifactId:extension[:classifier]:pinnedVersion}
     */
    String coordinate() {
        return coordinate("${" + versionProperty() + "}");
    }

    /**
     * The coordinate with a chosen version text.
     *
     * @param versionText the version, or a property reference to it
     * @return {@code groupId:artifactId:extension[:classifier]:versionText}
     */
    String coordinate(String versionText) {
        return groupId + ":" + artifactId + ":" + extension
                + (classifier.isEmpty() ? "" : ":" + classifier) + ":" + versionText;
    }

    /**
     * The typed-marker property that pins this layer's version, as
     * {@code ike-version-management-extension} names it.
     *
     * @return {@code <groupId>__GA__<artifactId>__VERSION}
     */
    String versionProperty() {
        return groupId + "__GA__" + artifactId + "__VERSION";
    }

    /**
     * Whether this layer is the IKE starter set.
     *
     * @return {@code true} for {@code network.ike.foundation:ike-starter-set}
     */
    boolean starterSet() {
        return STARTER_SET_GROUP.equals(groupId) && STARTER_SET_ARTIFACT.equals(artifactId);
    }

    /**
     * Whether this layer is a classified, standalone protobuf.
     *
     * @return {@code true} for the {@code reasoned-pb} classifier
     */
    boolean reasoned() {
        return REASONED.equals(classifier);
    }

    /**
     * Whether this layer is an unclassified protobuf.
     *
     * @return {@code true} for the {@code unreasoned-pb} classifier
     */
    boolean unreasoned() {
        return UNREASONED.equals(classifier);
    }
}
