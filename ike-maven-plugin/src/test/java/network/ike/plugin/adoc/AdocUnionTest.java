package network.ike.plugin.adoc;

import network.ike.plugin.TestLog;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The union written from a real reactor layout and real library zips: what is copied, what is
 * linked, what is refused, what is left alone, and what a build is told about an include
 * nothing declared ships.
 */
class AdocUnionTest {

    private static final AdocCoordinates LAB = new AdocCoordinates("network.ike.lab", "topics", "12");
    private static final AdocCoordinates UNLV = new AdocCoordinates("network.ike.lab", "unlv-topics", "3");
    private static final AdocCoordinates MEMBER = new AdocCoordinates("network.ike.examples", "topics", "1-SNAPSHOT");

    private static final String GITFLOW = "network.ike.lab/dev/gitflow-overview.adoc";
    private static final String PROPOSAL = "network.ike.lab/unlv/proposal-index.adoc";
    private static final String CORPUS = "network.ike.examples/corpus/overview.adoc";

    @TempDir
    Path temp;

    private Path reactor;
    private Path union;
    private Path legacyDirectory;
    private Path localRepository;
    private Path memberRoot;
    private Path assemblyRoot;

    @BeforeEach
    void layout() throws Exception {
        reactor = temp.resolve("reactor");
        union = reactor.resolve("target/generated-sources/adoc");
        legacyDirectory = reactor.resolve("assembly/target/generated-sources/asciidoc/topics-asciidoc");
        localRepository = temp.resolve("repo");
        memberRoot = reactor.resolve("topics/src/docs/asciidoc");
        assemblyRoot = reactor.resolve("assembly/src/docs/asciidoc");
        AdocFixtures.write(memberRoot.resolve(CORPUS), AdocFixtures.topic("corpus-overview", "Corpus"));
        AdocFixtures.write(memberRoot.resolve("network.ike.examples/corpus/images/corpus.svg"), "<svg/>");
        Files.createDirectories(assemblyRoot);
    }

    private AdocUnion union(boolean link, boolean legacy) {
        return new AdocUnion(union, legacyDirectory, link, legacy, new TestLog());
    }

    private Path labZip(String version, Map<String, String> files) throws Exception {
        AdocCoordinates at = new AdocCoordinates(LAB.groupId(), LAB.artifactId(), version);
        return AdocFixtures.libraryZip(temp.resolve("zips/topics-" + version + "-adoc.zip"), at,
                Map.of("network.ike.lab/dev", "topics"), files);
    }

    private Path labZip() throws Exception {
        return labZip(LAB.version(), Map.of(GITFLOW, AdocFixtures.topic("dev-gitflow-overview", "Gitflow"),
                "network.ike.lab/dev/images/flow.svg", "<svg/>"));
    }

    // ── copy and link ────────────────────────────────────────────────────

    @Test
    void anExternalLibraryIsCopiedWithItsManifestUnderMetaInf() throws Exception {
        AdocUnion.Result result = union(true, false).update(List.of(AdocLibrary.external(LAB, labZip())));

        assertThat(result.copied()).containsExactly(LAB);
        assertThat(union.resolve(GITFLOW)).isRegularFile();
        assertThat(union.resolve("network.ike.lab/dev/images/flow.svg")).isRegularFile();
        assertThat(union.resolve("META-INF/ike/network.ike.lab/topics/manifest.yaml")).isRegularFile();
        assertThat(union.resolve("META-INF/ike/network.ike.lab/topics/topic-registry.yaml")).isRegularFile();
        assertThat(Files.readString(union.resolve("META-INF/ike/network.ike.lab/topics/union.properties")))
                .contains("version=12").contains("mode=copy").contains("sha256=");
    }

    @Test
    void aReactorMemberIsLinkedSoAnEditThroughTheUnionChangesTheSource() throws Exception {
        AdocUnion.Result result = union(true, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));

        Path link = union.resolve("network.ike.examples/corpus");
        assertThat(result.linked()).containsExactly(MEMBER);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(memberRoot.resolve("network.ike.examples/corpus"));
        Files.writeString(union.resolve(CORPUS), "= Edited through the union\n");
        assertThat(Files.readString(memberRoot.resolve(CORPUS))).isEqualTo("= Edited through the union\n");
        assertThat(AdocManifest.read(union.resolve("META-INF/ike/network.ike.examples/topics/manifest.yaml")).packages())
                .containsExactly(new AdocPackage("network.ike.examples/corpus", "topics"));
    }

    @Test
    void aReactorMemberIsCopiedWhenLinkingIsOff() throws Exception {
        AdocUnion.Result first = union(false, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));
        AdocUnion.Result second = union(false, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));

        Path copy = union.resolve("network.ike.examples/corpus");
        assertThat(first.linked()).containsExactly(MEMBER);
        assertThat(second.unchanged()).containsExactly(MEMBER);
        assertThat(Files.isSymbolicLink(copy)).isFalse();
        assertThat(copy.resolve("overview.adoc")).isRegularFile();
        Files.writeString(union.resolve(CORPUS), "= Edited in the copy\n");
        assertThat(Files.readString(memberRoot.resolve(CORPUS))).startsWith("// corpus-overview");
    }

    @Test
    void aMemberLinkedBeforeIsCopiedOnceLinkingIsOffAndBackAgain() throws Exception {
        union(true, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));
        union(false, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));
        assertThat(Files.isSymbolicLink(union.resolve("network.ike.examples/corpus"))).isFalse();
        assertThat(union.resolve(CORPUS)).isRegularFile();

        union(true, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));
        assertThat(Files.isSymbolicLink(union.resolve("network.ike.examples/corpus"))).isTrue();
    }

    // ── idempotence ──────────────────────────────────────────────────────

    @Test
    void anUnchangedLibraryIsNotRewritten() throws Exception {
        Path zip = labZip();
        union(true, false).update(List.of(AdocLibrary.external(LAB, zip), AdocLibrary.reactor(MEMBER, memberRoot)));
        FileTime stamped = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(union.resolve(GITFLOW), stamped);

        AdocUnion.Result again = union(true, false)
                .update(List.of(AdocLibrary.external(LAB, zip), AdocLibrary.reactor(MEMBER, memberRoot)));

        assertThat(again.unchanged()).containsExactlyInAnyOrder(LAB, MEMBER);
        assertThat(again.copied()).isEmpty();
        assertThat(again.linked()).isEmpty();
        assertThat(Files.getLastModifiedTime(union.resolve(GITFLOW))).isEqualTo(stamped);
    }

    @Test
    void aChangedLibraryReplacesWhatItsOldVersionLeft() throws Exception {
        union(true, false).update(List.of(AdocLibrary.external(LAB, labZip())));
        AdocCoordinates newer = new AdocCoordinates(LAB.groupId(), LAB.artifactId(), "13");
        Path newerZip = labZip("13", Map.of("network.ike.lab/dev/gitflow.adoc", AdocFixtures.topic("dev-gitflow", "Gitflow")));

        AdocUnion.Result result = union(true, false).update(List.of(AdocLibrary.external(newer, newerZip)));

        assertThat(result.copied()).containsExactly(newer);
        assertThat(union.resolve("network.ike.lab/dev/gitflow.adoc")).isRegularFile();
        assertThat(union.resolve(GITFLOW)).doesNotExist();
        assertThat(union.resolve("network.ike.lab/dev/images")).doesNotExist();
        assertThat(Files.readString(union.resolve("META-INF/ike/network.ike.lab/topics/union.properties")))
                .contains("version=13");
    }

    @Test
    void anEntryNoModuleDeclaresAnymoreIsLeftAlone() throws Exception {
        union(true, false).update(List.of(AdocLibrary.external(LAB, labZip())));

        AdocUnion.Result result = union(true, false).update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));

        assertThat(result.linked()).containsExactly(MEMBER);
        assertThat(union.resolve(GITFLOW)).isRegularFile();
        assertThat(union.resolve("META-INF/ike/network.ike.lab/topics/manifest.yaml")).isRegularFile();
    }

    // ── refusals ─────────────────────────────────────────────────────────

    @Test
    void twoLibrariesShippingOnePackageAreRefusedNamingBoth() throws Exception {
        Path unlvZip = AdocFixtures.libraryZip(temp.resolve("zips/unlv-topics-3-adoc.zip"), UNLV,
                Map.of("network.ike.lab/dev", "topics"),
                Map.of("network.ike.lab/dev/other.adoc", AdocFixtures.topic("dev-other", "Other")));

        assertThatThrownBy(() -> union(true, false).update(
                List.of(AdocLibrary.external(LAB, labZip()), AdocLibrary.external(UNLV, unlvZip))))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Two libraries ship the package network.ike.lab/dev")
                .hasMessageContaining(LAB.gav())
                .hasMessageContaining(UNLV.gav());
        assertThat(union.resolve(GITFLOW)).doesNotExist();
    }

    @Test
    void aLibraryAnotherModuleLeftInTheUnionAlsoBlocksASplitPackage() throws Exception {
        union(true, false).update(List.of(AdocLibrary.external(LAB, labZip())));
        Path unlvZip = AdocFixtures.libraryZip(temp.resolve("zips/unlv-topics-3-adoc.zip"), UNLV,
                Map.of("network.ike.lab/dev", "topics"),
                Map.of("network.ike.lab/dev/other.adoc", AdocFixtures.topic("dev-other", "Other")));

        assertThatThrownBy(() -> union(true, false).update(List.of(AdocLibrary.external(UNLV, unlvZip))))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("network.ike.lab:topics, already in the union")
                .hasMessageContaining(UNLV.gav());
    }

    @Test
    void aZipWithoutAManifestIsRefusedUnlessLegacyModeIsOn() throws Exception {
        Path legacyZip = AdocFixtures.zip(temp.resolve("zips/legacy-adoc.zip"),
                Map.of("topics/dev/old.adoc", AdocFixtures.topic("dev-old", "Old")));

        assertThatThrownBy(() -> union(true, false).update(List.of(AdocLibrary.external(LAB, legacyZip))))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining(LAB.gav())
                .hasMessageContaining("legacy library")
                .hasMessageContaining("-Dike.adoc.legacy=true");
        assertThat(union.resolve("topics")).doesNotExist();
    }

    @Test
    void legacyModeUnpacksALegacyLibraryWhereTheStockUnpackDid() throws Exception {
        Path legacyZip = AdocFixtures.zip(temp.resolve("zips/legacy-adoc.zip"),
                Map.of("topics/dev/old.adoc", AdocFixtures.topic("dev-old", "Old")));
        AdocFixtures.write(assemblyRoot.resolve("book.adoc"),
                "= Book\n\ninclude::{topics}/topics/dev/old.adoc[leveloffset=+1]\n");
        AdocUnion adocUnion = union(true, true);

        AdocUnion.Result result = adocUnion.update(List.of(AdocLibrary.external(LAB, legacyZip)));

        assertThat(result.legacy()).containsExactly(LAB);
        assertThat(adocUnion.legacyUnpacked()).isTrue();
        assertThat(legacyDirectory.resolve("topics/dev/old.adoc")).isRegularFile();
        assertThat(union.resolve("topics")).doesNotExist();
        assertThat(adocUnion.verifyIncludes(assemblyRoot, localRepository)).isEqualTo(1);
    }

    // ── include verification ─────────────────────────────────────────────

    @Test
    void includesThatResolveInTheUnionAreCounted() throws Exception {
        AdocUnion adocUnion = union(true, false);
        adocUnion.update(List.of(AdocLibrary.external(LAB, labZip()), AdocLibrary.reactor(MEMBER, memberRoot)));
        AdocFixtures.write(assemblyRoot.resolve("book.adoc"), """
                = Book

                include::{adoc}/%s[leveloffset=+1]
                include::{topics}/%s[leveloffset=+1]
                include::{adoc}/network.ike.lab/dev/{chapter}.adoc[]
                include::chapters/local.adoc[]
                """.formatted(GITFLOW, CORPUS));

        assertThat(adocUnion.verifyIncludes(assemblyRoot, localRepository)).isEqualTo(2);
    }

    @Test
    void anUnresolvedIncludeNamesTheDependencyBlockFromTheLocalRepository() throws Exception {
        AdocFixtures.installed(localRepository, UNLV, Map.of("network.ike.lab/unlv", "library"),
                Map.of(PROPOSAL, "= Proposals\n"));
        AdocFixtures.installed(localRepository, new AdocCoordinates(UNLV.groupId(), UNLV.artifactId(), "2"),
                Map.of("network.ike.lab/unlv", "library"), Map.of(PROPOSAL, "= Proposals\n"));
        AdocFixtures.installed(localRepository, LAB, Map.of("network.ike.lab/dev", "topics"), Map.of(GITFLOW, "= G\n"));
        AdocUnion adocUnion = union(true, false);
        adocUnion.update(List.of(AdocLibrary.reactor(MEMBER, memberRoot)));
        AdocFixtures.write(assemblyRoot.resolve("book.adoc"),
                "= Book\n\ninclude::{adoc}/" + PROPOSAL + "[leveloffset=+1]\n");

        assertThatThrownBy(() -> adocUnion.verifyIncludes(assemblyRoot, localRepository))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("1 include in " + assemblyRoot + " does not resolve")
                .hasMessageContaining("book.adoc:3  include::{adoc}/" + PROPOSAL)
                .hasMessageContaining("network.ike.lab:unlv-topics ships the package network.ike.lab/unlv (version 3")
                .hasMessageContaining(UNLV.dependencyBlock());
    }

    @Test
    void anUnresolvedIncludeIntoADeclaredLibraryReportsThePackageButNotTheFile() throws Exception {
        AdocUnion adocUnion = union(true, false);
        adocUnion.update(List.of(AdocLibrary.external(LAB, labZip())));
        AdocFixtures.write(assemblyRoot.resolve("book.adoc"),
                "= Book\n\ninclude::{adoc}/network.ike.lab/dev/gitflow-overveiw.adoc[]\n");

        assertThatThrownBy(() -> adocUnion.verifyIncludes(assemblyRoot, localRepository))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("network.ike.lab:topics (version 12) is in the union and ships the package"
                        + " network.ike.lab/dev, but not this file");
    }

    @Test
    void anUnresolvedIncludeNoKnownLibraryShipsSaysSo() throws Exception {
        AdocUnion adocUnion = union(true, false);
        adocUnion.update(List.of());
        AdocFixtures.write(assemblyRoot.resolve("book.adoc"),
                "= Book\n\ninclude::{adoc}/network.ike.nowhere/x/y.adoc[]\n");

        assertThatThrownBy(() -> adocUnion.verifyIncludes(assemblyRoot, localRepository))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("No library in the union or in the local repository ships a package holding"
                        + " network.ike.nowhere/x/y.adoc")
                .hasMessageContaining("never fetches an undeclared library");
    }
}
