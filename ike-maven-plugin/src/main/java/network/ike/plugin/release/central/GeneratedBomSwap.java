package network.ike.plugin.release.central;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Swap {@code ike:generate-bom} output into the Maven Central staging
 * bundle (IKE-Network/ike-issues#853).
 *
 * <p>{@code generate-bom} writes a fully resolved BOM to
 * {@code target/generated-bom.xml}, but Maven 4's immutable
 * {@code Project} offers no way to make install/deploy publish it in
 * place of the stub POM — so every {@code ike-bom} version on Central
 * (72–132) shipped the stub: no {@code <dependencyManagement>} at all,
 * plus leaked {@code <build>} and {@code <distributionManagement>}
 * sections. This class closes the gap at the staging step of
 * {@code ike:release-publish}: after the signed deploy into
 * {@code target/staging-deploy}, each module's generated BOM replaces
 * its staged stub POM.
 *
 * <p>The stub's signature and checksums are deleted along with its
 * content — they were produced over the stub bytes and must be
 * regenerated over the swapped file (the caller re-signs via
 * {@code gpg:sign-and-deploy-file} under the same {@code signArtifacts}
 * profile the staging deploy used).
 */
final class GeneratedBomSwap {

    private GeneratedBomSwap() {}

    /** Sidecars invalidated by a content swap. */
    private static final List<String> SIDECARS = List.of(
            ".asc", ".md5", ".sha1", ".sha256", ".sha512");

    /**
     * One planned swap: a module's generated BOM and the staged stub
     * POM it replaces.
     */
    record Swap(Path generatedBom, Path stagedPom,
                String groupId, String artifactId, String version) {}

    /**
     * Find every {@code target/generated-bom.xml} under
     * {@code gitRoot} and pair it with its staged POM.
     *
     * @param gitRoot    the release worktree root
     * @param stagingDir the {@code staging-deploy} directory
     * @return planned swaps for generated BOMs whose staged POM exists;
     *         a generated BOM without a staged counterpart is skipped
     * @throws IOException when the tree walk or an XML read fails
     */
    static List<Swap> plan(Path gitRoot, Path stagingDir)
            throws IOException {
        List<Swap> swaps = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(gitRoot, 6)) {
            List<Path> generated = paths
                    .filter(p -> p.getFileName().toString()
                            .equals("generated-bom.xml"))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString()
                                    .equals("target"))
                    .toList();
            for (Path bom : generated) {
                String[] gav = readGav(bom);
                if (gav == null) continue;
                Path stagedPom = stagingDir
                        .resolve(gav[0].replace('.', '/'))
                        .resolve(gav[1])
                        .resolve(gav[2])
                        .resolve(gav[1] + "-" + gav[2] + ".pom");
                if (!Files.isRegularFile(stagedPom)) continue;
                swaps.add(new Swap(bom, stagedPom, gav[0], gav[1], gav[2]));
            }
        }
        return swaps;
    }

    /**
     * Replace the staged POM's content with the generated BOM and
     * delete the now-stale signature and checksum sidecars.
     *
     * @param swap the planned swap
     * @return the deleted sidecar paths
     * @throws IOException when a write or delete fails
     */
    static List<Path> apply(Swap swap) throws IOException {
        Files.writeString(swap.stagedPom(),
                Files.readString(swap.generatedBom(), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        List<Path> deleted = new ArrayList<>();
        for (String suffix : SIDECARS) {
            Path sidecar = swap.stagedPom().resolveSibling(
                    swap.stagedPom().getFileName() + suffix);
            if (Files.deleteIfExists(sidecar)) {
                deleted.add(sidecar);
            }
        }
        return deleted;
    }

    /**
     * Assert the staged POM now carries the resolved BOM payload and
     * none of the stub's build machinery — the #853 failure was
     * silent, so the swap's success must not be assumed.
     *
     * @param swap the applied swap
     * @return null when valid, else a description of what is wrong
     * @throws IOException when the staged POM cannot be read
     */
    static String verify(Swap swap) throws IOException {
        String pom = Files.readString(swap.stagedPom(),
                StandardCharsets.UTF_8);
        if (!pom.contains("<dependencyManagement>")) {
            return swap.stagedPom() + " has no <dependencyManagement> "
                    + "after the generated-BOM swap";
        }
        if (pom.contains("<build>") || pom.contains("<distributionManagement>")) {
            return swap.stagedPom() + " still carries stub build machinery "
                    + "(<build>/<distributionManagement>) after the swap";
        }
        for (String required : List.of("<licenses>", "<developers>", "<scm>")) {
            if (!pom.contains(required)) {
                return swap.stagedPom() + " lacks " + required
                        + " after the generated-BOM swap — Maven Central "
                        + "requires it (IKE-Network/ike-issues#967)";
            }
        }
        return null;
    }

    /**
     * Read project-level groupId/artifactId/version from a generated
     * BOM, or null when the file is not parseable as a POM.
     */
    private static String[] readGav(Path bomXml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = dbf.newDocumentBuilder().parse(bomXml.toFile());
            Element project = doc.getDocumentElement();
            String groupId = directChildText(project, "groupId");
            String artifactId = directChildText(project, "artifactId");
            String version = directChildText(project, "version");
            if (groupId == null || artifactId == null || version == null) {
                return null;
            }
            return new String[] {groupId, artifactId, version};
        } catch (Exception e) {
            return null;
        }
    }

    /** Text of a direct child element, ignoring nested occurrences. */
    private static String directChildText(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null;
                n = n.getNextSibling()) {
            if (n instanceof Element el && name.equals(el.getTagName())) {
                return el.getTextContent().trim();
            }
        }
        return null;
    }
}
