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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The knowledge-base descriptor: what a store is, in one small file a program managing stores reads
 * without opening the store (the IKE Dashboard design, {@code dev-ike-dashboard} in ike-lab-documents).
 * Every store {@code ike:kb-assemble} writes carries it with its icon:
 *
 * <pre>
 * META-INF/ike/knowledge-base.properties   descriptorLevel, coordinate, displayName, layout, …, icon,
 *                                          the collection, when it is in one, the memory the
 *                                          assembly measured, when it did, and the inputs it was
 *                                          assembled from
 * META-INF/ike/icon.png, icon@2x.png       the knowledge base's icon
 * </pre>
 *
 * <p>Level {@value #LEVEL} requires every field this class writes but {@code resolvedVersion}, which
 * a build knows only for a release, and the icon file at the path {@code icon} names. A project
 * keeps its icon at {@value #ICON_SOURCE}; one that has none gets a monogram
 * ({@link KnowledgeBaseMonogram}), so every store reaches level 1. The same descriptor and icon
 * deploy beside the store zips as {@code properties:kb-descriptor} and {@code png:icon}.
 */
final class KnowledgeBaseDescriptor {

    /** The descriptor, relative to the store root. */
    static final String FILE = "META-INF/ike/knowledge-base.properties";

    /** The icon, relative to the store root. */
    static final String ICON = "META-INF/ike/icon.png";

    /** Where a knowledge-base project keeps its icon, relative to its base directory. */
    static final String ICON_SOURCE = "src/main/kb/icon.png";

    /** The descriptor level this class writes. */
    static final int LEVEL = 1;

    /** The classifier the descriptor deploys under, extension {@code properties}. */
    static final String CLASSIFIER = "kb-descriptor";

    /** The classifier the icon deploys under, extension {@code png}. */
    static final String ICON_CLASSIFIER = "icon";

    /**
     * The memory facts the assembly reports in the store's manifest, by manifest attribute, and the
     * descriptor entry each is carried in. They are optional at level {@value #LEVEL}; a program
     * sizing a store's heap reads them from Nexus before the store is downloaded (the IKE Dashboard
     * design, decision 10; IKE-Network/ike-issues#1324).
     */
    static final Map<String, String> MEMORY = memoryEntries();

    private static Map<String, String> memoryEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Ike-Stated-Axiom-Bytes", "statedAxiomBytes");
        entries.put("Ike-Loaded-Heap-Bytes", "loadedHeapBytes");
        entries.put("Ike-Reasoner-Heap-Bytes", "reasonerHeapBytes");
        return java.util.Collections.unmodifiableMap(entries);
    }

    /** Who may download the knowledge base: anyone, or only those the restricted repositories admit. */
    enum Access {
        /** Served to anyone. */
        PUBLIC,
        /** Served only to those the restricted repositories admit, as licensed content requires. */
        RESTRICTED;

        String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One input a store was assembled from, in the order kb-assemble took it, written as
     * {@code input.<n>=<role> <reference>} with {@code input.<n>.resolvedVersion} and
     * {@code input.<n>.importer} beside it when known. A program managing stores reads them to show
     * which knowledge bases a store includes (decision 18 of the IKE Dashboard design).
     *
     * @param role            how the assembly took it: {@code STORE_SEED}, {@code PB}, {@code CHANGESET}
     *                        or {@code SOURCE}
     * @param reference       the Maven coordinate as the build wrote it
     *                        ({@code g:a[:extension[:classifier]]:v}), or {@code file:<name>} for a file
     *                        that is no artifact of a repository
     * @param resolvedVersion the version the coordinate resolved to, when it differs from the one
     *                        written: a snapshot's deploy
     * @param importer        a {@code SOURCE} input's importer
     */
    record Input(String role, String reference, Optional<String> resolvedVersion, Optional<String> importer) {
    }

    /**
     * What a build knows about the knowledge base, the same for every store it assembles.
     *
     * @param groupId      the coordinate's groupId
     * @param artifactId   the coordinate's artifactId
     * @param version      the coordinate's version
     * @param displayName  the name a person reads, the project's {@code <name>}
     * @param entityFormat the entity format the store was written in
     * @param assembledAt  when the store was assembled
     * @param access       who may download it
     * @param memory       what the assembly measured, by descriptor entry, in bytes ({@link #MEMORY})
     * @param collection   the collection the knowledge base belongs to, such as {@code SOLOR}: the
     *                     knowledge bases one pipeline builds and publishes together, which a program
     *                     managing stores keeps at hand without being told to (decision 17 of the
     *                     IKE Dashboard design)
     * @param inputs       what the store was assembled from, in order
     */
    record Fields(String groupId, String artifactId, String version, String displayName, int entityFormat,
                  Instant assembledAt, Access access, Map<String, Long> memory, Optional<String> collection,
                  List<Input> inputs) {

        /**
         * Copies the memory facts and the inputs, and takes a blank collection for none.
         *
         * @param groupId      the coordinate's groupId
         * @param artifactId   the coordinate's artifactId
         * @param version      the coordinate's version
         * @param displayName  the name a person reads
         * @param entityFormat the entity format the store was written in
         * @param assembledAt  when the store was assembled
         * @param access       who may download it
         * @param memory       what the assembly measured, by descriptor entry
         * @param collection   the collection the knowledge base belongs to
         * @param inputs       what the store was assembled from, in order
         */
        Fields {
            memory = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(memory));
            collection = collection.map(String::strip).filter(name -> !name.isEmpty());
            inputs = List.copyOf(inputs);
        }

        /**
         * Fields with no inputs recorded.
         *
         * @param groupId      the coordinate's groupId
         * @param artifactId   the coordinate's artifactId
         * @param version      the coordinate's version
         * @param displayName  the name a person reads
         * @param entityFormat the entity format the store was written in
         * @param assembledAt  when the store was assembled
         * @param access       who may download it
         * @param memory       what the assembly measured, by descriptor entry
         * @param collection   the collection the knowledge base belongs to
         */
        Fields(String groupId, String artifactId, String version, String displayName, int entityFormat,
               Instant assembledAt, Access access, Map<String, Long> memory, Optional<String> collection) {
            this(groupId, artifactId, version, displayName, entityFormat, assembledAt, access, memory, collection,
                    List.of());
        }

        /**
         * Fields of a knowledge base in no collection.
         *
         * @param groupId      the coordinate's groupId
         * @param artifactId   the coordinate's artifactId
         * @param version      the coordinate's version
         * @param displayName  the name a person reads
         * @param entityFormat the entity format the store was written in
         * @param assembledAt  when the store was assembled
         * @param access       who may download it
         * @param memory       what the assembly measured, by descriptor entry
         */
        Fields(String groupId, String artifactId, String version, String displayName, int entityFormat,
               Instant assembledAt, Access access, Map<String, Long> memory) {
            this(groupId, artifactId, version, displayName, entityFormat, assembledAt, access, memory, Optional.empty());
        }

        /**
         * Fields with no memory measured.
         *
         * @param groupId      the coordinate's groupId
         * @param artifactId   the coordinate's artifactId
         * @param version      the coordinate's version
         * @param displayName  the name a person reads
         * @param entityFormat the entity format the store was written in
         * @param assembledAt  when the store was assembled
         * @param access       who may download it
         */
        Fields(String groupId, String artifactId, String version, String displayName, int entityFormat,
               Instant assembledAt, Access access) {
            this(groupId, artifactId, version, displayName, entityFormat, assembledAt, access, Map.of());
        }
    }

    /**
     * The memory facts among a store's manifest attributes, by descriptor entry; an attribute that is
     * not a whole number of bytes is left out.
     *
     * @param attributes the manifest attributes the assembly reported
     * @return the facts, in {@link #MEMORY}'s order
     */
    static Map<String, Long> memory(Map<String, String> attributes) {
        Map<String, Long> memory = new LinkedHashMap<>();
        MEMORY.forEach((attribute, entry) -> {
            String value = attributes.get(attribute);
            if (value != null) {
                try {
                    memory.put(entry, Long.parseLong(value.strip()));
                } catch (NumberFormatException e) {
                    // not a size: the descriptor leaves it out
                }
            }
        });
        return memory;
    }

    private KnowledgeBaseDescriptor() {
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

    /**
     * The store's layout, from its files: {@code rocks}, {@code spined-32} or {@code spined-64}.
     *
     * @param store the store directory
     * @return the layout
     * @throws IOException if the store's files name no layout this descriptor level knows
     */
    static String layout(Path store) throws IOException {
        if (Files.isDirectory(store.resolve("rocks"))) {
            return "rocks";
        }
        if (Files.isDirectory(store.resolve("nidToByteArrayMap"))) {
            return "spined-32";
        }
        Path format = store.resolve("store-format.properties");
        if (Files.isRegularFile(format)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(format)) {
                properties.load(in);
            }
            if ("64-bit".equals(properties.getProperty("layout"))) {
                return "spined-64";
            }
        }
        throw new IOException("No layout the knowledge-base descriptor knows (rocks, spined-32, spined-64) in " + store);
    }

    /**
     * The entity format a store's own files record, for a store whose assembly did not report one.
     *
     * @param store the store directory
     * @return the entity format {@code store-format.properties} records, if it does
     * @throws IOException if the file exists but cannot be read
     */
    static Optional<Integer> recordedEntityFormat(Path store) throws IOException {
        Path format = store.resolve("store-format.properties");
        if (!Files.isRegularFile(format)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(format)) {
            properties.load(in);
        }
        try {
            return Optional.ofNullable(properties.getProperty("entityFormat")).map(String::strip).map(Integer::valueOf);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Writes the descriptor and the icon into a store, replacing any an earlier build left.
     *
     * @param store  the store directory
     * @param fields what the build knows
     * @param icon   the icon to copy, with its {@code @2x} companion when there is one
     * @return the descriptor file written
     * @throws IOException if a file cannot be written, or the store names no known layout
     */
    static Path write(Path store, Fields fields, Path icon) throws IOException {
        Path storedIcon = store.resolve(ICON);
        Files.createDirectories(storedIcon.getParent());
        Files.deleteIfExists(storedIcon);
        Files.deleteIfExists(doubleResolution(storedIcon));
        Files.copy(icon, storedIcon);
        if (Files.isRegularFile(doubleResolution(icon))) {
            Files.copy(doubleResolution(icon), doubleResolution(storedIcon));
        }

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("descriptorLevel", Integer.toString(LEVEL));
        entries.put("groupId", fields.groupId());
        entries.put("artifactId", fields.artifactId());
        entries.put("version", fields.version());
        if (!fields.version().endsWith("-SNAPSHOT")) {
            // A snapshot's deploy is named only when it is deployed; a release is its own.
            entries.put("resolvedVersion", fields.version());
        }
        entries.put("displayName", fields.displayName());
        entries.put("layout", layout(store));
        entries.put("entityFormat", Integer.toString(fields.entityFormat()));
        entries.put("assembledAt", fields.assembledAt().toString());
        entries.put("access", fields.access().value());
        entries.put("icon", ICON);
        fields.collection().ifPresent(collection -> entries.put("collection", collection));
        fields.memory().forEach((entry, bytes) -> entries.put(entry, Long.toString(bytes)));
        for (int i = 0; i < fields.inputs().size(); i++) {
            Input input = fields.inputs().get(i);
            String key = "input." + (i + 1);
            entries.put(key, input.role() + " " + input.reference());
            input.resolvedVersion().ifPresent(version -> entries.put(key + ".resolvedVersion", version));
            input.importer().ifPresent(importer -> entries.put(key + ".importer", importer));
        }
        Path file = store.resolve(FILE);
        Files.writeString(file, text(entries), StandardCharsets.ISO_8859_1);
        return file;
    }

    /**
     * The entries in order, escaped as {@link Properties#store(java.io.OutputStream, String)}
     * escapes them, without its timestamp, so a rebuild writes the same bytes.
     */
    private static String text(Map<String, String> entries) throws IOException {
        StringBuilder text = new StringBuilder("#Knowledge-base descriptor, level " + LEVEL + "\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Properties one = new Properties();
            one.setProperty(entry.getKey(), entry.getValue());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            one.store(out, null);
            out.toString(StandardCharsets.ISO_8859_1).lines().filter(line -> !line.startsWith("#"))
                    .forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }

    /** The high-resolution companion of an icon: {@code icon@2x.png} beside {@code icon.png}. */
    static Path doubleResolution(Path icon) {
        String name = icon.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return icon.resolveSibling(name.substring(0, dot) + "@2x" + name.substring(dot));
    }
}
