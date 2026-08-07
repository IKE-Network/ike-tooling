package network.ike.plugin.release.central;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finalizes a Maven Central staging directory between the signed
 * staging deploy and the JReleaser upload: prunes the Maven 4
 * {@code -build.pom} artifacts, then swaps each module's generated
 * BOM over its staged stub POM, re-signs it, and verifies the result
 * (IKE-Network/ike-issues#853, IKE-Network/ike-issues#966).
 *
 * <p>Both Central deploy paths run this exact code. The sync path
 * ({@link CentralPhase}) calls it in-process; the detached async
 * sentinel script (IKE-Network/ike-issues#484) invokes it through
 * {@code ike:central-stage}, pinned to the plugin version that
 * rendered the script. This class exists because the swap originally
 * lived only on the sync path while releases default to async — so
 * ike-bom 145 and 146 shipped to Central as stubs with green builds
 * (#966).
 */
public final class CentralStaging {

    private CentralStaging() {}

    /**
     * Deletes the Maven 4 {@code -build.pom} artifacts — and their
     * signatures and checksums — from the staging directory. The
     * build POM carries the 4.1.0 model and is build-time only;
     * Maven Central publishes the consumer POM (the main
     * {@code .pom}). One build POM is produced per reactor module,
     * so the directory is walked recursively.
     *
     * @param log        Maven logger for progress output
     * @param stagingDir the {@code staging-deploy} directory to prune
     * @throws MojoException when the walk or a delete fails
     */
    public static void pruneBuildPoms(Log log, Path stagingDir) {
        try (Stream<Path> paths = Files.walk(stagingDir)) {
            List<Path> buildPoms = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .contains("-build.pom"))
                    .toList();
            for (Path p : buildPoms) {
                Files.delete(p);
                log.info("  pruned " + stagingDir.relativize(p));
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to prune -build.pom artifacts from "
                            + stagingDir, e);
        }
    }

    /**
     * Replaces each staged stub POM with its module's
     * {@code target/generated-bom.xml}, re-signs it, and verifies the
     * result (IKE-Network/ike-issues#853). The stub's signature and
     * checksums are invalidated by the content swap, so the swapped
     * file is re-deployed into the staging directory through
     * {@code gpg:sign-and-deploy-file} under the same
     * {@code signArtifacts} profile as the original staging deploy —
     * producing a fresh signature and fresh checksums over the real
     * BOM bytes before JReleaser uploads the bundle.
     *
     * <p>Finding zero generated BOMs is normal for repos without a
     * BOM module and is logged rather than failed; a swap whose
     * verification fails throws, so a stub can never replace a real
     * BOM silently.
     *
     * @param log        Maven logger for progress output
     * @param buildRoot  the build root that produced the staged
     *                   artifacts (walked for
     *                   {@code target/generated-bom.xml} outputs)
     * @param mvnw       the Maven wrapper used for the re-sign deploy
     * @param stagingDir the {@code staging-deploy} directory holding
     *                   the staged stub POMs
     * @return the number of BOMs swapped
     * @throws MojoException when the swap I/O fails, the re-sign
     *         subprocess exits non-zero, or a swapped POM does not
     *         verify
     */
    public static int swapGeneratedBoms(Log log, File buildRoot,
                                        File mvnw, Path stagingDir) {
        int swapped = 0;
        try {
            for (GeneratedBomSwap.Swap swap
                    : GeneratedBomSwap.plan(buildRoot.toPath(), stagingDir)) {
                GeneratedBomSwap.apply(swap);
                log.info("  swapped generated BOM into "
                        + stagingDir.relativize(swap.stagedPom()));
                ReleaseSupport.exec(buildRoot, log,
                        mvnw.getAbsolutePath(),
                        "gpg:sign-and-deploy-file", "-N", "-B",
                        "-P", "signArtifacts",
                        "-Durl=file://" + stagingDir.toAbsolutePath(),
                        "-Dfile=" + swap.generatedBom().toAbsolutePath(),
                        "-DpomFile=" + swap.generatedBom().toAbsolutePath(),
                        "-Dpackaging=pom");
                String problem = GeneratedBomSwap.verify(swap);
                if (problem != null) {
                    throw new MojoException(
                            "Generated-BOM swap verification failed: "
                                    + problem);
                }
                swapped++;
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to swap generated BOMs into " + stagingDir, e);
        }
        if (swapped == 0) {
            log.info("  no generated BOMs under " + buildRoot
                    + " — nothing to swap");
        } else {
            log.info("  swapped " + swapped + " generated BOM(s)");
        }
        return swapped;
    }
}
