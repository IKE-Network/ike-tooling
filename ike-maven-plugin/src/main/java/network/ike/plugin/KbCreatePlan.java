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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The genesis plan of a new knowledge-base project (IKE-Network/ike-issues#1370): its
 * coordinates, its pinned layers, whether it builds a Rocks store and with what heap, and
 * the files it writes, rendered from the {@code kb/} templates of ike-build-standards.
 * Pure: the layers arrive already pinned, and nothing here reads the network or the disk,
 * so the draft and publish goals render the same facts and the plan is unit-testable.
 *
 * <p>The sizing rules are the issue's decision 6, from the knowledge-base descriptors on
 * Nexus (2026-11-11): a spined store's loaded heap comes to about
 * {@value #LOADED_HEAP_PER_INPUT_BYTE} times the total size of its input protobufs
 * (3.3 to 3.9 measured), so an assembly builds a Rocks store when its layers total
 * {@value #ROCKS_THRESHOLD_GIB} GiB or more: an estimated loaded heap of 8 GiB, past what
 * an ordinary machine gives a spined store to open and classify.
 */
final class KbCreatePlan {

    /** Bytes in a gibibyte. */
    static final long GIB = 1L << 30;

    /** Input protobufs, in GiB, from which an assembly builds a Rocks store (decision 6). */
    static final long ROCKS_THRESHOLD_GIB = 2;

    /** Loaded heap per byte of input protobuf: 3.3 to 3.9 measured, rounded up. */
    static final double LOADED_HEAP_PER_INPUT_BYTE = 4.0;

    /**
     * Heap per byte of live heap: the collector's room above the live set, the dashboard's
     * measured term ({@code HeapSizing.DEFAULT_MEASURED_ROOM}, solor-gudid-kb, 2026-10-25).
     */
    static final double COLLECTOR_ROOM = 1.35;

    /**
     * What the ELK reasoner adds to an assembly's live heap, per byte loaded: the upper
     * bound measured on solor-gudid-kb (43.9 GiB over 13.6 loaded, 2026-11-11).
     */
    static final double REASONER_PER_LOADED_BYTE = 3.2;

    /**
     * What an import adds to a source layer's live heap, per byte loaded. Not measured;
     * the existing source layers run with heaps of 2 to 10 times their loaded store.
     */
    static final double IMPORT_PER_LOADED_BYTE = 1.0;

    /** The least heap a generated project is given, in GiB. */
    static final long MINIMUM_HEAP_GIB = 8;

    /** An artifactId: lower-case words joined by hyphens. */
    private static final Pattern ARTIFACT_ID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    /** A groupId: dot-separated Java-like segments. */
    private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    /** A {@code @token@} left after expansion. */
    private static final Pattern LEFTOVER = Pattern.compile("@([a-zA-Z]+)@");

    /**
     * Everything the plan is made from.
     *
     * @param kind                  what the project builds
     * @param groupId               the project's group
     * @param artifactId            the project's artifact, which also names its folder
     * @param version               the project's version
     * @param displayName           the POM {@code <name>}, or {@code null} to derive it
     * @param description           the POM {@code <description>}, or {@code null} to derive it
     * @param parentVersion         the {@code ike-parent} version
     * @param standalone            whether the project is its own Maven root
     *                              ({@code root="true"}); {@code false} inside a workspace
     * @param scmUrl                the {@code file:} URL of the project's own folder
     * @param layers                the protobuf layers, pinned, in load order
     * @param importer              the source importer's name, such as {@code snomed-rf2},
     *                              for a source layer
     * @param release               the source release, pinned, for a source layer given
     *                              by coordinate
     * @param releasePath           the source release as a file path, for a source layer
     *                              given by path
     * @param importerOptions       the importer's {@code name=value} options, or {@code null}
     * @param collection            the dashboard collection, or {@code null} for none
     * @param distributionId        the deploy repositories' server id
     * @param releasesUrl           the releases repository
     * @param snapshotsUrl          the snapshots repository
     * @param siteUrl               the site deploy URL ike-parent's enforcer requires
     * @param rocks                 {@code true} or {@code false} to force the Rocks store
     *                              either way, {@code null} to decide by size
     * @param heap                  the build heap, such as {@code -Xmx24g}, or {@code null}
     *                              to size it from the layers
     * @param editModule            the knowledge base's module UUID, whose versions alone
     *                              the authoring keeps, or {@code null} to keep every version
     * @param providerVersion       the {@code ike-knowledge-provider} version
     * @param tinkarCoreVersion     the {@code tinkar-core} version, for the importer
     * @param rocksKbVersion        the {@code rocks-kb} version, for a Rocks store
     * @param rocksdbjniVersion     the {@code rocksdbjni} version, for a Rocks store
     * @param rocksdbjniClassifier  the {@code rocksdbjni} platform classifier
     */
    record Inputs(KbKind kind, String groupId, String artifactId, String version,
                  String displayName, String description, String parentVersion,
                  boolean standalone, String scmUrl, List<KbLayer> layers,
                  String importer, KbLayer release, String releasePath, String importerOptions,
                  String collection, String distributionId, String releasesUrl,
                  String snapshotsUrl, String siteUrl, Boolean rocks, String heap,
                  String editModule, String providerVersion, String tinkarCoreVersion,
                  String rocksKbVersion, String rocksdbjniVersion, String rocksdbjniClassifier) {

        /** Copies the layer list. */
        Inputs {
            layers = List.copyOf(layers);
        }
    }

    private final Inputs in;
    private final boolean rocks;
    private final String heap;
    private final String displayName;
    private final String description;

    /**
     * Builds and checks the plan.
     *
     * @param inputs everything it is made from
     * @throws IllegalArgumentException if the inputs break a rule of a knowledge base:
     *                                  a malformed name, a source layer without its
     *                                  importer and release, an assembly with one, a
     *                                  layer twice, a starter set out of first place, or
     *                                  reasoned and unreasoned protobufs together
     */
    KbCreatePlan(Inputs inputs) {
        this.in = Objects.requireNonNull(inputs, "inputs");
        check(inputs);
        this.rocks = inputs.kind() == KbKind.ASSEMBLY
                && (inputs.rocks() != null ? inputs.rocks() : layerBytes() >= ROCKS_THRESHOLD_GIB * GIB);
        this.heap = inputs.heap() != null && !inputs.heap().isBlank()
                ? inputs.heap().strip()
                : "-Xmx" + estimatedHeapGib() + "g";
        this.displayName = inputs.displayName() != null && !inputs.displayName().isBlank()
                ? inputs.displayName().strip()
                : displayNameOf(inputs.artifactId());
        this.description = inputs.description() != null && !inputs.description().isBlank()
                ? inputs.description().strip()
                : defaultDescription();
    }

    /**
     * Puts the starter set first when the layers need it: every knowledge base loads the
     * IKE starter set before anything else, unless a {@code reasoned-pb} layer is its base,
     * which is standalone and already carries it.
     *
     * @param requested         the layers as requested, in load order
     * @param starterSetVersion the starter set's version when it is added
     * @return the layers to pin, in load order
     */
    static List<KbLayer> withStarterSet(List<KbLayer> requested, String starterSetVersion) {
        boolean present = requested.stream().anyMatch(KbLayer::starterSet);
        boolean standaloneBase = requested.stream().anyMatch(KbLayer::reasoned);
        if (present || standaloneBase) {
            return List.copyOf(requested);
        }
        List<KbLayer> layers = new ArrayList<>();
        layers.add(KbLayer.parse(KbLayer.STARTER_SET_GROUP + ":" + KbLayer.STARTER_SET_ARTIFACT
                + ":zip:" + KbLayer.UNREASONED + ":" + starterSetVersion));
        layers.addAll(requested);
        return List.copyOf(layers);
    }

    private static void check(Inputs in) {
        if (in.artifactId() == null || !ARTIFACT_ID.matcher(in.artifactId()).matches()) {
            throw new IllegalArgumentException("-Dname must be an artifactId: lower-case letters and"
                    + " digits joined by hyphens, such as my-kb; got " + in.artifactId());
        }
        if (in.groupId() == null || !GROUP_ID.matcher(in.groupId()).matches()) {
            throw new IllegalArgumentException("-DgroupId is not a Maven groupId: " + in.groupId());
        }
        if (in.version() == null || in.version().isBlank()) {
            throw new IllegalArgumentException("-Dversion is blank");
        }
        if (in.layers().isEmpty()) {
            throw new IllegalArgumentException("A knowledge base needs at least one layer");
        }
        boolean source = in.importer() != null && !in.importer().isBlank();
        boolean release = in.release() != null || (in.releasePath() != null && !in.releasePath().isBlank());
        if (in.kind() == KbKind.SOURCE_LAYER && (!source || !release)) {
            throw new IllegalArgumentException("A source layer needs -Dimporter (such as snomed-rf2)"
                    + " and -Drelease (the release's coordinate or path)");
        }
        if (in.kind() == KbKind.ASSEMBLY && (source || release)) {
            throw new IllegalArgumentException("An assembly imports no release: -Dimporter and -Drelease"
                    + " belong to -Dkind=" + KbKind.SOURCE_LAYER.parameterValue());
        }
        if (in.kind() == KbKind.SOURCE_LAYER && Boolean.TRUE.equals(in.rocks())) {
            throw new IllegalArgumentException("A source layer is loaded only under an assembly and"
                    + " builds no Rocks store; -Drocks=true belongs to an assembly");
        }
        Set<String> seen = new HashSet<>();
        for (KbLayer layer : in.layers()) {
            if (!seen.add(layer.groupId() + ":" + layer.artifactId())) {
                throw new IllegalArgumentException("A layer is listed twice: "
                        + layer.groupId() + ":" + layer.artifactId());
            }
        }
        for (int index = 1; index < in.layers().size(); index++) {
            if (in.layers().get(index).starterSet()) {
                throw new IllegalArgumentException("The starter set loads first, before every"
                        + " layer that resolves to its identities; it is listed at position " + (index + 1));
            }
        }
        boolean reasoned = in.layers().stream().anyMatch(KbLayer::reasoned);
        boolean unreasoned = in.layers().stream().anyMatch(KbLayer::unreasoned);
        if (reasoned && unreasoned) {
            throw new IllegalArgumentException("Reasoned and unreasoned protobufs are refused together"
                    + " until kb-assemble is confirmed to accept the mix (IKE-Network/ike-issues#1370,"
                    + " decision 10): " + in.layers().stream()
                    .map(layer -> layer.artifactId() + ":" + layer.classifier())
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * What the project is made from.
     *
     * @return the inputs
     */
    Inputs inputs() {
        return in;
    }

    /**
     * Whether the project builds a Rocks store.
     *
     * @return {@code true} when it does
     */
    boolean rocks() {
        return rocks;
    }

    /**
     * Why the project does or does not build a Rocks store.
     *
     * @return a sentence for the report
     */
    String rocksReason() {
        if (in.kind() == KbKind.SOURCE_LAYER) {
            return "a source layer is loaded only under an assembly";
        }
        if (in.rocks() != null) {
            return "set by -Drocks=" + in.rocks();
        }
        String total = gib(layerBytes());
        String known = unknownSizes() == 0 ? "" : " (" + unknownSizes() + " layer size(s) unknown)";
        return rocks
                ? "the layers total " + total + known + ", at or above " + ROCKS_THRESHOLD_GIB + " GiB"
                : "the layers total " + total + known + ", below " + ROCKS_THRESHOLD_GIB + " GiB";
    }

    /**
     * The build heap, as a JVM argument.
     *
     * @return such as {@code -Xmx16g}
     */
    String heap() {
        return heap;
    }

    /**
     * The POM {@code <name>}.
     *
     * @return the display name
     */
    String displayName() {
        return displayName;
    }

    /**
     * The POM {@code <description>}.
     *
     * @return the description
     */
    String description() {
        return description;
    }

    /**
     * The total size of the protobuf layers whose size the repository reported.
     *
     * @return bytes
     */
    long layerBytes() {
        return in.layers().stream().mapToLong(KbLayer::sizeBytes).filter(bytes -> bytes > 0).sum();
    }

    /**
     * How many layers' sizes the repository did not report.
     *
     * @return the count
     */
    long unknownSizes() {
        return in.layers().stream().filter(layer -> layer.sizeBytes() < 0).count();
    }

    /**
     * The loaded heap a spined store of these layers is estimated to hold.
     *
     * @return bytes
     */
    long estimatedLoadedBytes() {
        return (long) Math.ceil(LOADED_HEAP_PER_INPUT_BYTE * layerBytes());
    }

    /**
     * The heap the build is given when none is set: the estimated loaded heap, plus what
     * the reasoner (an assembly) or the import (a source layer) adds, with the collector's
     * room above it, never under {@value #MINIMUM_HEAP_GIB} GiB.
     *
     * @return whole GiB
     */
    long estimatedHeapGib() {
        double added = in.kind() == KbKind.ASSEMBLY ? REASONER_PER_LOADED_BYTE : IMPORT_PER_LOADED_BYTE;
        double bytes = COLLECTOR_ROOM * (1 + added) * estimatedLoadedBytes();
        return Math.max(MINIMUM_HEAP_GIB, (long) Math.ceil(bytes / GIB));
    }

    /**
     * The classifier of the protobuf the project publishes.
     *
     * @return {@code unreasoned-pb} or {@code reasoned-pb}
     */
    String publishedClassifier() {
        return in.kind().publishedClassifier();
    }

    /**
     * The coordinates the project publishes.
     *
     * @return one coordinate per published file
     */
    List<String> publishes() {
        List<String> published = new ArrayList<>();
        String base = in.groupId() + ":" + in.artifactId() + ":zip:";
        published.add(base + publishedClassifier() + ":" + in.version());
        if (rocks) {
            published.add(base + "rkb:" + in.version());
        }
        return published;
    }

    /**
     * Renders every file the project starts with, from the {@code kb/} templates.
     *
     * @param templates the templates, by path within {@code kb/}
     * @return project-relative path to content, in writing order
     * @throws IllegalStateException if a template is missing or a token is left unexpanded
     */
    Map<String, String> render(Map<String, String> templates) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", expand(template(templates, "pom.xml"), pomTokens(templates)));
        files.put("README.adoc", expand(template(templates, "files/README.adoc"), readmeTokens()));
        files.put(".mvn/jvm.config", template(templates, "files/jvm.config"));
        if (rocks) {
            files.put("src/assembly/rkb.xml", expand(template(templates, "files/rkb.xml"),
                    Map.of("artifactId", in.artifactId())));
        }
        if (in.kind() == KbKind.ASSEMBLY) {
            files.put("changesets/.gitkeep", template(templates, "files/gitkeep"));
        }
        return files;
    }

    private Map<String, String> pomTokens(Map<String, String> templates) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("rootAttribute", in.standalone() ? "\n         root=\"true\"" : "");
        tokens.put("parentVersion", in.parentVersion());
        tokens.put("groupId", in.groupId());
        tokens.put("artifactId", in.artifactId());
        tokens.put("version", in.version());
        tokens.put("displayName", xml(displayName));
        tokens.put("description", xml(description));
        tokens.put("scmUrl", xml(in.scmUrl()));
        tokens.put("distributionId", in.distributionId());
        tokens.put("releasesUrl", xml(in.releasesUrl()));
        tokens.put("snapshotsUrl", xml(in.snapshotsUrl()));
        tokens.put("siteUrl", xml(in.siteUrl()));
        tokens.put("properties", properties(templates));
        tokens.put("dependencies", dependencies(templates));
        tokens.put("plugins", plugins(templates));
        tokens.put("profiles", in.kind() == KbKind.ASSEMBLY
                ? expand(template(templates, "fragments/komet-profile.xml"),
                Map.of("changeSetModules", changeSetModules("                                    ")))
                : "");
        return tokens;
    }

    private String properties(Map<String, String> templates) {
        String fragment = template(templates, "fragments/property.xml");
        StringBuilder out = new StringBuilder();
        if (in.collection() != null && !in.collection().isBlank()) {
            out.append(property(fragment, "ike.kbAssemble.collection", in.collection().strip(),
                    "The collection named in every store's descriptor: the IKE Dashboard keeps each"
                            + " knowledge base of a collection it tracks at hand, downloaded or not."));
        }
        out.append(property(fragment, "network.ike.knowledge__GA__ike-knowledge-provider__VERSION",
                in.providerVersion(), "The assembler kb-assemble runs."));
        if (in.kind() == KbKind.SOURCE_LAYER) {
            out.append(property(fragment, "dev.ikm.tinkar__GA__tinkar-core__VERSION", in.tinkarCoreVersion(),
                    "The " + in.importer().strip() + " importer's release train."));
        }
        for (KbLayer layer : in.layers()) {
            out.append(property(fragment, layer.versionProperty(), layer.pinnedVersion(),
                    layerComment(layer)));
        }
        if (in.kind() == KbKind.SOURCE_LAYER) {
            String release = in.release() != null
                    ? in.release().coordinate(in.release().pinnedVersion())
                    : in.releasePath().strip();
            String comment = in.release() != null
                    ? "The source release, pinned to the deploy of " + in.release().version()
                    + " on the repository when this project was created" + size(in.release()) + "."
                    : "The source release, read in place; nothing licensed is committed here.";
            out.append(property(fragment, "source.release", release, comment));
        }
        if (rocks) {
            out.append(property(fragment, "dev.ikm.ike__GA__rocks-kb__VERSION", in.rocksKbVersion(),
                    "The Rocks KB engine, for the RocksDB store built beside the protobuf."));
            out.append(property(fragment, "rocksdbjni.version", in.rocksdbjniVersion(),
                    "RocksDB's native library, for the platform the build runs on."));
        }
        out.append(property(fragment, "kb.heap", heap, heapComment()));
        if (in.kind() == KbKind.ASSEMBLY) {
            out.append(property(fragment, "kb.changesets", "${project.basedir}/changesets",
                    "Every change set harvested from Komet, committed; the build folds them into one."));
            out.append(property(fragment, "kb.edits",
                    "${project.build.directory}/${project.artifactId}-${project.version}-edits.zip",
                    "The folded change set, loaded after the layers so its versions win."));
            out.append(property(fragment, "kb.install", "${user.home}/Solor/" + in.artifactId() + "-authoring",
                    "Where -Pkomet installs the store Komet edits the knowledge base in: not"
                            + " ~/Solor/" + in.artifactId() + ", where the IKE Dashboard unpacks a published store."));
            if (in.editModule() != null && !in.editModule().isBlank()) {
                out.append(property(fragment, "kb.modules", in.editModule().strip(),
                        "The knowledge base's module: only versions stamped in it are kept."));
            }
        }
        return trimTrailingNewline(out.toString());
    }

    private String dependencies(Map<String, String> templates) {
        String fragment = template(templates, "fragments/dependency.xml");
        StringBuilder out = new StringBuilder();
        out.append(dependency(fragment, "network.ike.knowledge", "ike-knowledge-provider",
                "${network.ike.knowledge__GA__ike-knowledge-provider__VERSION}", "", "runtime",
                "The assembler."));
        if (in.kind() == KbKind.SOURCE_LAYER) {
            out.append(dependency(fragment, "dev.ikm.tinkar", "importer-" + in.importer().strip(),
                    "${dev.ikm.tinkar__GA__tinkar-core__VERSION}", "", "runtime",
                    "The importer the SOURCE input names."));
        }
        if (rocks) {
            out.append(dependency(fragment, "dev.ikm.ike", "rocks-kb-engine",
                    "${dev.ikm.ike__GA__rocks-kb__VERSION}", "", "runtime",
                    "The RocksDB store controller, which the rocks-store execution selects."));
            out.append(dependency(fragment, "dev.ikm.jpms", "rocksdbjni", "${rocksdbjni.version}",
                    "\n            <classifier>" + in.rocksdbjniClassifier() + "</classifier>", "runtime",
                    "The engine declares its platform natives optional, so they are named here."));
        }
        for (KbLayer layer : in.layers()) {
            out.append(dependency(fragment, layer.groupId(), layer.artifactId(), "${" + layer.versionProperty() + "}",
                    "\n            <type>" + layer.extension() + "</type>"
                            + (layer.classifier().isEmpty() ? ""
                            : "\n            <classifier>" + layer.classifier() + "</classifier>"),
                    "provided", "A layer, resolved before assembly."));
        }
        return trimTrailingNewline(out.toString());
    }

    private String plugins(Map<String, String> templates) {
        String input = template(templates, "fragments/input.xml");
        StringBuilder inputs = new StringBuilder();
        StringBuilder baseInputs = new StringBuilder();
        for (KbLayer layer : in.layers()) {
            inputs.append(expand(input, Map.of("element", "input", "input", "PB " + layer.coordinate())));
            baseInputs.append(expand(input, Map.of("element", "baseInput", "input", "PB " + layer.coordinate())));
        }
        if (in.kind() == KbKind.SOURCE_LAYER) {
            String options = in.importerOptions() == null || in.importerOptions().isBlank()
                    ? "" : " " + in.importerOptions().strip();
            inputs.append(expand(input, Map.of("element", "input",
                    "input", xml("SOURCE " + in.importer().strip() + " ${source.release}" + options))));
            return trimTrailingNewline(expand(template(templates, "fragments/source-layer-plugins.xml"),
                    Map.of("inputs", inputs.toString(), "baseInputs", baseInputs.toString())));
        }
        inputs.append(expand(input, Map.of("element", "input", "input", "PB ${kb.edits}")));
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("inputs", inputs.toString());
        tokens.put("changeSetModules", changeSetModules("                            "));
        tokens.put("rocksExecution", rocks ? template(templates, "fragments/rocks-execution.xml") : "");
        tokens.put("rocksAssemblyPlugin", rocks ? template(templates, "fragments/rocks-assembly-plugin.xml") : "");
        return trimTrailingNewline(expand(template(templates, "fragments/assembly-plugins.xml"), tokens));
    }

    private Map<String, String> readmeTokens() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("displayName", displayName);
        tokens.put("description", description);
        tokens.put("groupId", in.groupId());
        tokens.put("artifactId", in.artifactId());
        tokens.put("version", in.version());
        tokens.put("kindLabel", in.kind().label());
        tokens.put("publishes", publishes().stream().map(coordinate -> "`" + coordinate + "`")
                .collect(Collectors.joining(", ")));
        StringBuilder layers = new StringBuilder();
        for (KbLayer layer : in.layers()) {
            layers.append(". `").append(layer.coordinate(layer.pinnedVersion())).append("`\n");
        }
        if (in.kind() == KbKind.SOURCE_LAYER) {
            layers.append(". the ").append(in.importer().strip()).append(" release, `source.release`\n");
        } else {
            layers.append(". the edits harvested from Komet into `changesets/`\n");
        }
        tokens.put("layerList", trimTrailingNewline(layers.toString()));
        tokens.put("distributionId", in.distributionId());
        tokens.put("heap", heap);
        tokens.put("authoringSection", in.kind() != KbKind.ASSEMBLY ? "" : """

                == Authoring

                `./mvnw install -Pkomet` harvests the change sets Komet captured in the authoring store
                into `changesets/`, then installs the classified store at `~/Solor/%s-authoring` for
                Komet to open. Commit `changesets/`: the next build folds it in.
                """.formatted(in.artifactId()));
        return tokens;
    }

    /**
     * The report the draft and publish goals both print.
     *
     * @param target where the project is written
     * @return the report's lines
     */
    List<String> report(String target) {
        List<String> lines = new ArrayList<>();
        lines.add("Knowledge base: " + displayName + " (" + in.kind().label() + ")");
        lines.add("  Coordinates:  " + in.groupId() + ":" + in.artifactId() + ":" + in.version());
        lines.add("  Publishes:    " + String.join(", ", publishes()));
        lines.add("  Folder:       " + target + (in.standalone() ? " (its own Maven root)" : " (a workspace member)"));
        lines.add("  Parent:       network.ike.platform:ike-parent:" + in.parentVersion());
        lines.add("  Layers, in load order, pinned:");
        for (KbLayer layer : in.layers()) {
            lines.add("    " + layer.coordinate(layer.pinnedVersion()) + size(layer));
        }
        if (in.kind() == KbKind.SOURCE_LAYER) {
            lines.add("  Source:       " + in.importer().strip() + " "
                    + (in.release() != null ? in.release().coordinate(in.release().pinnedVersion()) + size(in.release())
                    : in.releasePath().strip())
                    + (in.importerOptions() == null || in.importerOptions().isBlank() ? "" : " " + in.importerOptions().strip()));
        }
        lines.add("  Layers total: " + gib(layerBytes()) + "; estimated loaded heap " + gib(estimatedLoadedBytes()));
        lines.add("  Rocks store:  " + (rocks ? "yes" : "no") + " — " + rocksReason());
        lines.add("  Build heap:   " + heap + (in.heap() != null && !in.heap().isBlank() ? " (set by -Dheap)" : " (estimated)"));
        lines.add("  Deploys to:   " + in.distributionId() + " " + in.snapshotsUrl());
        lines.add("  Collection:   " + (in.collection() == null || in.collection().isBlank() ? "none" : in.collection().strip()));
        lines.add("  SCM:          " + in.scmUrl() + " (until a remote is added)");
        return lines;
    }

    private String heapComment() {
        if (in.heap() != null && !in.heap().isBlank()) {
            return "The build's heap, as set when the project was created.";
        }
        return "Sized when the project was created: the layers total " + gib(layerBytes())
                + ", a spined store about " + (long) LOADED_HEAP_PER_INPUT_BYTE + " times that loaded ("
                + gib(estimatedLoadedBytes()) + "), plus room for "
                + (in.kind() == KbKind.ASSEMBLY ? "the reasoner" : "the import")
                + " and the collector. Replace it with what the descriptor measures after a build.";
    }

    private String layerComment(KbLayer layer) {
        return layer.coordinate(layer.version()) + ", pinned to the deploy on the repository when this"
                + " project was created" + size(layer) + ".";
    }

    private String changeSetModules(String indent) {
        if (in.editModule() == null || in.editModule().isBlank()) {
            return "";
        }
        return "\n" + indent + "<changeSetModules>${kb.modules}</changeSetModules>";
    }

    private String defaultDescription() {
        String coordinate = in.groupId() + ":" + in.artifactId() + ":zip:" + publishedClassifier();
        String layers = in.layers().stream().map(KbLayer::artifactId).collect(Collectors.joining(", "));
        if (in.kind() == KbKind.SOURCE_LAYER) {
            return displayName + ": a release read by the " + in.importer().strip() + " importer onto "
                    + layers + ", exported unclassified as a Tinkar protobuf (format 3) change set,"
                    + " published as " + coordinate + ". The release is read in place; nothing"
                    + " licensed is committed here.";
        }
        return displayName + ": " + layers + ", with the edits authored in Komet, classified and"
                + " exported as a reasoned protobuf change set (format 3) that loads into a Spined"
                + " Array or RocksDB knowledge base, published as " + coordinate + ".";
    }

    /**
     * Derives a display name from an artifactId: title case, and a trailing {@code kb}
     * spelled out.
     *
     * @param artifactId the artifactId
     * @return such as {@code Gudid Nuva Knowledge Base} for {@code gudid-nuva-kb}
     */
    static String displayNameOf(String artifactId) {
        String title = StarterSetPlan.titleCase(artifactId.replace('.', '-'));
        return title.endsWith(" Kb")
                ? title.substring(0, title.length() - 3) + " Knowledge Base"
                : title.equals("Kb") ? "Knowledge Base" : title;
    }

    private static String property(String fragment, String name, String value, String comment) {
        return expand(fragment, Map.of("comment", xmlComment(comment), "name", name, "value", xml(value)));
    }

    private static String dependency(String fragment, String groupId, String artifactId, String version,
                                     String typeAndClassifier, String scope, String comment) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("comment", xmlComment(comment));
        tokens.put("groupId", groupId);
        tokens.put("artifactId", artifactId);
        tokens.put("version", version);
        tokens.put("typeAndClassifier", typeAndClassifier);
        tokens.put("scope", scope);
        return expand(fragment, tokens);
    }

    private static String template(Map<String, String> templates, String path) {
        String template = templates.get(path);
        if (template == null) {
            throw new IllegalStateException("The kb templates carry no " + path
                    + " — ike-build-standards and ike-maven-plugin versions must match");
        }
        return template;
    }

    /**
     * Expands every {@code @token@} of a template, refusing any left over.
     *
     * @param template the template
     * @param tokens   the values, by token name
     * @return the expanded text
     * @throws IllegalStateException if a token is left unexpanded
     */
    static String expand(String template, Map<String, String> tokens) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = LEFTOVER.matcher(template);
        int from = 0;
        while (matcher.find()) {
            String value = tokens.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Unknown template token: @" + matcher.group(1) + "@");
            }
            out.append(template, from, matcher.start()).append(value);
            from = matcher.end();
        }
        return out.append(template.substring(from)).toString();
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String xmlComment(String text) {
        return text.replace("--", "- -");
    }

    private static String trimTrailingNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private static String size(KbLayer layer) {
        return layer.sizeBytes() < 0 ? "" : ", " + gib(layer.sizeBytes());
    }

    /**
     * A size for a person to read.
     *
     * @param bytes the size
     * @return such as {@code 2.74 GiB} or {@code 461.6 MiB}
     */
    static String gib(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (double) GIB);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
    }

    /**
     * Whether a release spec is a coordinate, for parameters that accept either form.
     *
     * @param spec the spec
     * @return the coordinate, when it is one
     */
    static Optional<KbLayer> coordinateOf(String spec) {
        return KbLayer.isCoordinate(spec) ? Optional.of(KbLayer.parse(spec)) : Optional.empty();
    }
}
