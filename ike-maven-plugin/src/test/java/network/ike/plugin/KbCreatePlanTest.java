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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The knowledge-base genesis plan, rendered from the real {@code kb/} templates of
 * ike-build-standards in this reactor.
 */
class KbCreatePlanTest {

    /** The scaffold sources of the sibling module, the templates that ship. */
    static final Path SCAFFOLD = Path.of("..", "ike-build-standards", "src", "main", "scaffold");

    private static Map<String, String> templates;

    private static final long MIB = 1L << 20;

    @BeforeAll
    static void loadTemplates() {
        templates = KbCreateSupport.templates(SCAFFOLD);
    }

    private static KbLayer layer(String spec, String pinned, long bytes) {
        return KbLayer.parse(spec).pinned(pinned, bytes);
    }

    private static KbLayer starterSet() {
        return layer("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT", "1-20261107.045844-2", MIB);
    }

    private static KbLayer snomed() {
        return layer("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                "20260601.2-20261111.020518-1", 462 * MIB);
    }

    private static KbLayer gudid() {
        return layer("network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT",
                "20261001.2-20261111.021610-1", 2809 * MIB);
    }

    private static KbCreatePlan.Inputs assembly(List<KbLayer> layers, Boolean rocks) {
        return new KbCreatePlan.Inputs(KbKind.ASSEMBLY, "network.ike.data", "my-kb", "1-SNAPSHOT",
                null, null, "224", true, "file:///work/my-kb/", layers, null, null, null, null, null,
                "ike-restricted", "https://repo/releases/", "https://repo/snapshots/", "https://ike.network/my-kb/",
                rocks, null, null, "7-SNAPSHOT", "1.127.7-SNAPSHOT", "0.1.5-SNAPSHOT", "10.4.2-r2", "osx");
    }

    private static KbCreatePlan.Inputs sourceLayer(String importer, KbLayer release, String releasePath, Boolean rocks) {
        return new KbCreatePlan.Inputs(KbKind.SOURCE_LAYER, "network.ike.data", "nuva-kb", "20250509.1-SNAPSHOT",
                "SNOMED CT NUVA Extension", null, "224", true, "file:///work/nuva-kb/", List.of(starterSet(), snomed()),
                importer, release, releasePath, "release=full author=\"Snomed NUVA Extension Author\"", null,
                "ike-restricted", "https://repo/releases/", "https://repo/snapshots/", "https://ike.network/nuva-kb/",
                rocks, null, null, "7-SNAPSHOT", "1.127.7-SNAPSHOT", "0.1.5-SNAPSHOT", "10.4.2-r2", "osx");
    }

    private static Document xml(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> texts(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            texts.add(nodes.item(index).getTextContent().strip());
        }
        return texts;
    }

    private static String property(Document pom, String name) {
        NodeList nodes = pom.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    @Test
    @DisplayName("The starter set goes first unless listed or a reasoned base carries it")
    void starterSetFirst() {
        List<KbLayer> requested = List.of(KbLayer.parse("network.ike.data:snomedct-kb:zip:unreasoned-pb:1-SNAPSHOT"));
        List<KbLayer> layers = KbCreatePlan.withStarterSet(requested, "1-SNAPSHOT");
        assertThat(layers).hasSize(2);
        assertThat(layers.getFirst().starterSet()).isTrue();
        assertThat(layers.getFirst().classifier()).isEqualTo("unreasoned-pb");

        List<KbLayer> listed = List.of(KbLayer.parse("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:2-SNAPSHOT"));
        assertThat(KbCreatePlan.withStarterSet(listed, "1-SNAPSHOT")).isEqualTo(listed);

        List<KbLayer> reasoned = List.of(KbLayer.parse("network.ike.data:solor-kb:zip:reasoned-pb:1-SNAPSHOT"));
        assertThat(KbCreatePlan.withStarterSet(reasoned, "1-SNAPSHOT")).isEqualTo(reasoned);
    }

    @Test
    @DisplayName("Malformed names and misplaced or repeated layers are refused")
    void refusesBrokenInputs() {
        KbCreatePlan.Inputs good = assembly(List.of(starterSet(), snomed()), null);
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(new KbCreatePlan.Inputs(
                good.kind(), good.groupId(), "My KB", good.version(), null, null, good.parentVersion(), true,
                good.scmUrl(), good.layers(), null, null, null, null, null, good.distributionId(), good.releasesUrl(),
                good.snapshotsUrl(), good.siteUrl(), null, null, null, good.providerVersion(), good.tinkarCoreVersion(),
                good.rocksKbVersion(), good.rocksdbjniVersion(), good.rocksdbjniClassifier())))
                .withMessageContaining("artifactId");
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(assembly(List.of(), null)))
                .withMessageContaining("at least one layer");
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(assembly(List.of(starterSet(), snomed(), snomed()), null)))
                .withMessageContaining("listed twice");
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(assembly(List.of(snomed(), starterSet()), null)))
                .withMessageContaining("loads first");
    }

    @Test
    @DisplayName("Reasoned and unreasoned protobufs are refused together (decision 10)")
    void refusesMixedProtobufs() {
        KbLayer reasoned = layer("network.ike.data:solor-kb:zip:reasoned-pb:1-SNAPSHOT", "1-20261111.021342-1", 721 * MIB);
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(assembly(List.of(reasoned, snomed()), null)))
                .withMessageContaining("decision 10")
                .withMessageContaining("solor-kb:reasoned-pb")
                .withMessageContaining("snomedct-kb:unreasoned-pb");
        assertThat(new KbCreatePlan(assembly(List.of(reasoned), null)).rocks()).isFalse();
    }

    @Test
    @DisplayName("A source layer needs its importer and release; an assembly takes neither")
    void kindRules() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(sourceLayer(null, null, "/r.zip", null)))
                .withMessageContaining("-Dimporter");
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(sourceLayer("snomed-rf2", null, null, null)))
                .withMessageContaining("-Drelease");
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(sourceLayer("snomed-rf2", null, "/r.zip", true)))
                .withMessageContaining("no Rocks store");
        KbCreatePlan.Inputs good = assembly(List.of(starterSet()), null);
        assertThatIllegalArgumentException().isThrownBy(() -> new KbCreatePlan(new KbCreatePlan.Inputs(
                good.kind(), good.groupId(), good.artifactId(), good.version(), null, null, good.parentVersion(), true,
                good.scmUrl(), good.layers(), "snomed-rf2", null, "/r.zip", null, null, good.distributionId(),
                good.releasesUrl(), good.snapshotsUrl(), good.siteUrl(), null, null, null, good.providerVersion(),
                good.tinkarCoreVersion(), good.rocksKbVersion(), good.rocksdbjniVersion(), good.rocksdbjniClassifier())))
                .withMessageContaining("An assembly imports no release");
    }

    @Test
    @DisplayName("Rocks is built from 2 GiB of layers, below it not, and either way when forced (decision 6)")
    void rocksThreshold() {
        KbLayer justUnder = layer("network.ike.data:big-kb:zip:unreasoned-pb:1", "1", 2 * KbCreatePlan.GIB - MIB - 1);
        assertThat(new KbCreatePlan(assembly(List.of(starterSet(), justUnder), null)).rocks()).isFalse();
        KbLayer atThreshold = layer("network.ike.data:big-kb:zip:unreasoned-pb:1", "1", 2 * KbCreatePlan.GIB - MIB);
        KbCreatePlan at = new KbCreatePlan(assembly(List.of(starterSet(), atThreshold), null));
        assertThat(at.rocks()).isTrue();
        assertThat(at.rocksReason()).contains("at or above 2 GiB");

        assertThat(new KbCreatePlan(assembly(List.of(starterSet(), snomed(), gudid()), false)).rocks()).isFalse();
        KbCreatePlan forced = new KbCreatePlan(assembly(List.of(starterSet()), true));
        assertThat(forced.rocks()).isTrue();
        assertThat(forced.rocksReason()).isEqualTo("set by -Drocks=true");
        assertThat(new KbCreatePlan(sourceLayer("snomed-rf2", null, "/r.zip", null)).rocksReason())
                .contains("source layer");
    }

    @Test
    @DisplayName("The heap follows the layers: 4× loaded, room for the reasoner and collector, 8 GiB at least")
    void heapEstimate() {
        KbCreatePlan small = new KbCreatePlan(assembly(List.of(starterSet()), null));
        assertThat(small.heap()).isEqualTo("-Xmx8g");

        KbCreatePlan large = new KbCreatePlan(assembly(List.of(starterSet(), snomed(), gudid()), null));
        long loaded = (long) Math.ceil(4.0 * (1 + 462 + 2809) * MIB);
        long expected = (long) Math.ceil(1.35 * (1 + 3.2) * loaded / KbCreatePlan.GIB);
        assertThat(large.estimatedLoadedBytes()).isEqualTo(loaded);
        assertThat(large.heap()).isEqualTo("-Xmx" + expected + "g");
        assertThat(expected).isBetween(60L, 90L);
    }

    @Test
    @DisplayName("Unknown sizes are left out of the total and said so")
    void unknownSizes() {
        KbLayer unknown = KbLayer.parse("network.ike.data:mystery-kb:zip:unreasoned-pb:1");
        KbCreatePlan plan = new KbCreatePlan(assembly(List.of(starterSet(), unknown), null));
        assertThat(plan.unknownSizes()).isEqualTo(1);
        assertThat(plan.layerBytes()).isEqualTo(MIB);
        assertThat(plan.rocksReason()).contains("1 layer size(s) unknown");
    }

    @Test
    @DisplayName("An assembly with Rocks renders a well-formed Maven 4 POM, its Rocks assembly and changesets")
    void rendersAssemblyWithRocks() throws Exception {
        KbCreatePlan plan = new KbCreatePlan(assembly(List.of(starterSet(), snomed(), gudid()), null));
        Map<String, String> files = plan.render(templates);

        assertThat(files).containsOnlyKeys("pom.xml", "README.adoc", ".mvn/jvm.config",
                "src/assembly/rkb.xml", "changesets/.gitkeep");
        String pomText = files.get("pom.xml");
        assertThat(pomText).doesNotContainPattern("@[a-zA-Z]+@");
        Document pom = xml(pomText);
        Element project = pom.getDocumentElement();
        assertThat(project.getAttribute("root")).isEqualTo("true");
        assertThat(texts(pom, "modelVersion")).containsExactly("4.1.0");
        assertThat(texts(pom, "relativePath")).containsExactly("");
        assertThat(texts(pom, "packaging")).containsExactly("pom");
        assertThat(texts(pom, "url")).contains("file:///work/my-kb/");
        assertThat(texts(pom, "connection")).containsExactly("scm:git:file:///work/my-kb/");
        assertThat(property(pom, "network.ike.data__GA__gudid-kb__VERSION")).isEqualTo("20261001.2-20261111.021610-1");
        assertThat(property(pom, "network.ike.foundation__GA__ike-starter-set__VERSION")).isEqualTo("1-20261107.045844-2");
        assertThat(property(pom, "ike.publishToCentral")).isEqualTo("false");
        assertThat(property(pom, "kb.heap")).isEqualTo(plan.heap());
        assertThat(property(pom, "ike.kbAssemble.collection")).isNull();
        assertThat(texts(pom, "input")).containsExactly(
                "PB network.ike.foundation:ike-starter-set:zip:unreasoned-pb:${network.ike.foundation__GA__ike-starter-set__VERSION}",
                "PB network.ike.data:snomedct-kb:zip:unreasoned-pb:${network.ike.data__GA__snomedct-kb__VERSION}",
                "PB network.ike.data:gudid-kb:zip:unreasoned-pb:${network.ike.data__GA__gudid-kb__VERSION}",
                "PB ${kb.edits}",
                "PB ${project.build.directory}/${project.artifactId}-${project.version}-reasoned-pb.zip");
        assertThat(texts(pom, "id")).contains("fold-changesets", "assemble-knowledge-base", "rocks-store",
                "verify-reasoned-roundtrip", "rocks-store-zip", "komet", "harvest-sessions");
        assertThat(texts(pom, "artifactId")).contains("rocks-kb-engine", "rocksdbjni", "maven-assembly-plugin");
        assertThat(texts(pom, "scope")).containsExactly("runtime", "runtime", "runtime", "provided", "provided", "provided");
        assertThat(texts(pom, "changeSetModules")).isEmpty();
        assertThat(files.get("src/assembly/rkb.xml")).contains("<outputDirectory>my-kb</outputDirectory>");
        xml(files.get("src/assembly/rkb.xml"));
        assertThat(files.get(".mvn/jvm.config")).isEqualTo("--enable-preview\n");
        assertThat(files.get("README.adoc")).contains("= My Knowledge Base")
                .contains("`network.ike.data:my-kb:zip:reasoned-pb:1-SNAPSHOT`, `network.ike.data:my-kb:zip:rkb:1-SNAPSHOT`")
                .contains("== Authoring")
                .doesNotContainPattern("@[a-zA-Z]+@");
    }

    @Test
    @DisplayName("A small assembly renders no Rocks store; a module and a collection appear when given")
    void rendersSmallAssembly() throws Exception {
        KbCreatePlan.Inputs base = assembly(List.of(starterSet()), null);
        KbCreatePlan plan = new KbCreatePlan(new KbCreatePlan.Inputs(base.kind(), base.groupId(), base.artifactId(),
                base.version(), "Tom & Jerry <KB>", "Cats & mice", base.parentVersion(), false, base.scmUrl(),
                base.layers(), null, null, null, null, "SOLOR", base.distributionId(), base.releasesUrl(),
                base.snapshotsUrl(), base.siteUrl(), null, "-Xmx24g", "84cb2ae1-2790-4f0d-967d-0193b5d3f2d3",
                base.providerVersion(), base.tinkarCoreVersion(), base.rocksKbVersion(), base.rocksdbjniVersion(),
                base.rocksdbjniClassifier()));
        Map<String, String> files = plan.render(templates);

        assertThat(files).doesNotContainKey("src/assembly/rkb.xml").containsKey("changesets/.gitkeep");
        Document pom = xml(files.get("pom.xml"));
        assertThat(pom.getDocumentElement().hasAttribute("root")).isFalse();
        assertThat(texts(pom, "name")).containsExactly("Tom & Jerry <KB>");
        assertThat(texts(pom, "description")).containsExactly("Cats & mice");
        assertThat(property(pom, "ike.kbAssemble.collection")).isEqualTo("SOLOR");
        assertThat(property(pom, "kb.heap")).isEqualTo("-Xmx24g");
        assertThat(property(pom, "kb.modules")).isEqualTo("84cb2ae1-2790-4f0d-967d-0193b5d3f2d3");
        assertThat(texts(pom, "changeSetModules")).containsOnly("${kb.modules}").hasSize(3);
        assertThat(texts(pom, "artifactId")).doesNotContain("rocks-kb-engine", "rocksdbjni", "maven-assembly-plugin");
        assertThat(texts(pom, "id")).doesNotContain("rocks-store", "rocks-store-zip");
    }

    @Test
    @DisplayName("A source layer renders the import, its importer and a verify on its base")
    void rendersSourceLayer() throws Exception {
        KbLayer release = layer("dev.ikm.data.nuva-sct-ext:nuva-sct-ext-origin:zip:nuva-1.0.0-SNAPSHOT",
                "nuva-1.0.0-20261019.164040-1", 700_000);
        KbCreatePlan plan = new KbCreatePlan(sourceLayer("snomed-rf2", release, null, null));
        Map<String, String> files = plan.render(templates);

        assertThat(files).containsOnlyKeys("pom.xml", "README.adoc", ".mvn/jvm.config");
        Document pom = xml(files.get("pom.xml"));
        assertThat(texts(pom, "artifactId")).contains("importer-snomed-rf2").doesNotContain("rocks-kb-engine");
        assertThat(property(pom, "dev.ikm.tinkar__GA__tinkar-core__VERSION")).isEqualTo("1.127.7-SNAPSHOT");
        assertThat(property(pom, "source.release"))
                .isEqualTo("dev.ikm.data.nuva-sct-ext:nuva-sct-ext-origin:zip:nuva-1.0.0-20261019.164040-1");
        assertThat(texts(pom, "input")).containsExactly(
                "PB network.ike.foundation:ike-starter-set:zip:unreasoned-pb:${network.ike.foundation__GA__ike-starter-set__VERSION}",
                "PB network.ike.data:snomedct-kb:zip:unreasoned-pb:${network.ike.data__GA__snomedct-kb__VERSION}",
                "SOURCE snomed-rf2 ${source.release} release=full author=\"Snomed NUVA Extension Author\"");
        assertThat(texts(pom, "baseInput")).hasSize(2);
        assertThat(texts(pom, "classify")).containsExactly("false");
        assertThat(texts(pom, "id")).contains("export-source", "verify-on-base")
                .doesNotContain("fold-changesets", "assemble-knowledge-base", "komet");
        assertThat(texts(pom, "profile")).isEmpty();
        assertThat(plan.publishes()).containsExactly("network.ike.data:nuva-kb:zip:unreasoned-pb:20250509.1-SNAPSHOT");
        assertThat(files.get("README.adoc")).doesNotContain("== Authoring").contains("snomed-rf2 release");
    }

    @Test
    @DisplayName("A release given by path is read in place")
    void releaseByPath() throws Exception {
        KbCreatePlan plan = new KbCreatePlan(sourceLayer("snomed-rf2", null, "/data/SnomedCT_NUVA.zip", null));
        Document pom = xml(plan.render(templates).get("pom.xml"));
        assertThat(property(pom, "source.release")).isEqualTo("/data/SnomedCT_NUVA.zip");
    }

    @Test
    @DisplayName("Display names are derived from the artifactId")
    void displayNames() {
        assertThat(KbCreatePlan.displayNameOf("gudid-nuva-kb")).isEqualTo("Gudid Nuva Knowledge Base");
        assertThat(KbCreatePlan.displayNameOf("kb")).isEqualTo("Knowledge Base");
        assertThat(KbCreatePlan.displayNameOf("pizza")).isEqualTo("Pizza");
    }

    @Test
    @DisplayName("Expansion refuses an unknown token and never re-expands a value")
    void expansion() {
        assertThatIllegalStateException().isThrownBy(() -> KbCreatePlan.expand("@nope@", Map.of()))
                .withMessageContaining("@nope@");
        assertThat(KbCreatePlan.expand("<@a@>", Map.of("a", "@b@"))).isEqualTo("<@b@>");
        assertThatIllegalStateException().isThrownBy(() -> assemblyPlanWithout("fragments/rocks-execution.xml"))
                .withMessageContaining("fragments/rocks-execution.xml");
    }

    private static void assemblyPlanWithout(String template) {
        Map<String, String> partial = new java.util.TreeMap<>(templates);
        partial.remove(template);
        new KbCreatePlan(assembly(List.of(starterSet()), true)).render(partial);
    }

    @Test
    @DisplayName("The report names every pinned layer, the Rocks decision and the heap")
    void report() {
        KbCreatePlan plan = new KbCreatePlan(assembly(List.of(starterSet(), snomed(), gudid()), null));
        List<String> report = plan.report("/work/my-kb");
        assertThat(report).anyMatch(line -> line.contains("gudid-kb:zip:unreasoned-pb:20261001.2-20261111.021610-1, 2.74 GiB"));
        assertThat(report).anyMatch(line -> line.startsWith("  Rocks store:  yes"));
        assertThat(report).anyMatch(line -> line.contains("(its own Maven root)"));
        assertThat(KbCreatePlan.gib(461 * MIB)).isEqualTo("461.0 MiB");
    }

    @Test
    @DisplayName("Rocks parameter values and workspace membership")
    void parameters(@TempDir Path temp) throws Exception {
        assertThat(KbCreateParameters.parseRocks(null)).isNull();
        assertThat(KbCreateParameters.parseRocks("TRUE")).isTrue();
        assertThat(KbCreateParameters.parseRocks("false")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> KbCreateParameters.parseRocks("maybe"));

        assertThat(KbCreateParameters.insideWorkspace(temp.resolve("my-kb"))).isFalse();
        Files.writeString(temp.resolve("workspace.yaml"), "schema-version: \"1.0\"\n");
        assertThat(KbCreateParameters.insideWorkspace(temp.resolve("my-kb"))).isTrue();
    }
}
