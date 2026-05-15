package network.ike.plugin;

import network.ike.workspace.cascade.ReleaseCascade;
import network.ike.workspace.cascade.ReleaseCascadeIo;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the {@code release-cascade.yaml} manifest for a foundation
 * repository (IKE-Network/ike-issues#402, #404).
 *
 * <p>Two strategies, tried in order:
 * <ol>
 *   <li><b>On-disk</b> — the path named by the
 *       {@code ike.release.cascade.manifest} property, falling back to
 *       {@code <gitRoot>/target/release-cascade.yaml}. Works for
 *       {@code ike-tooling} and for {@code ike-parent}-inheriting
 *       consumers.</li>
 *   <li><b>Artifact resolution</b> — fetch the {@code ike-build-standards}
 *       {@code cascade} classified ZIP through the Maven session, keyed
 *       off the repo's {@code ike-tooling.version}. The
 *       location-independent fallback for {@code ike-docs} and
 *       {@code ike-platform}, which carry no on-disk manifest.</li>
 * </ol>
 *
 * <p>Shared by {@link ReleaseDraftMojo}'s cascade footer and
 * {@code ike:cascade-export}.
 */
public final class CascadeManifestResolver {

    private CascadeManifestResolver() {}

    /**
     * Resolves and parses the cascade manifest.
     *
     * @param session              the Maven session, used for the
     *                             artifact-resolution fallback
     * @param gitRoot              the repository's git root
     * @param manifestPathOverride explicit manifest path (from the
     *                             {@code ike.release.cascade.manifest}
     *                             property); may be {@code null}/blank
     * @param log                  logger for the non-fatal warning
     *                             emitted when artifact resolution fails
     * @return the parsed cascade, or empty if no manifest can be found
     */
    public static Optional<ReleaseCascade> resolve(
            Session session, File gitRoot, String manifestPathOverride,
            Log log) {
        Path manifestPath =
                (manifestPathOverride != null
                        && !manifestPathOverride.isBlank())
                        ? Path.of(manifestPathOverride)
                        : new File(gitRoot, "target/release-cascade.yaml")
                                .toPath();
        Optional<ReleaseCascade> onDisk = ReleaseCascadeIo.load(manifestPath);
        if (onDisk.isPresent()) {
            return onDisk;
        }
        return resolveFromArtifact(session, gitRoot, log);
    }

    private static Optional<ReleaseCascade> resolveFromArtifact(
            Session session, File gitRoot, Log log) {
        File rootPom = new File(gitRoot, "pom.xml");
        String ikeToolingVersion = ReleaseSupport.readPomProperty(
                rootPom, "ike-tooling.version");
        if (ikeToolingVersion == null || ikeToolingVersion.isBlank()) {
            return Optional.empty();
        }
        try {
            ArtifactCoordinates coords = session.createArtifactCoordinates(
                    "network.ike.tooling", "ike-build-standards",
                    ikeToolingVersion, "cascade", "zip", "zip");
            DownloadedArtifact artifact = session.resolveArtifact(coords);
            return Optional.of(
                    ReleaseCascadeIo.readFromZip(artifact.getPath()));
        } catch (RuntimeException e) {
            log.warn("Could not resolve release-cascade.yaml from "
                    + "ike-build-standards:" + ikeToolingVersion
                    + ":cascade — " + e.getMessage());
            return Optional.empty();
        }
    }
}
