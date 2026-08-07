package network.ike.plugin;

import network.ike.plugin.release.central.CentralStaging;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Finalize a Maven Central staging directory: prune the Maven 4
 * {@code -build.pom} artifacts, swap generated BOMs over their staged
 * stub POMs, re-sign, and verify (IKE-Network/ike-issues#853,
 * IKE-Network/ike-issues#966).
 *
 * <p>Invoked by the async Central deploy sentinel script
 * (IKE-Network/ike-issues#484) between its signed staging deploy and
 * the JReleaser upload — always as a fully pinned
 * {@code groupId:artifactId:version:central-stage} matching the plugin
 * that rendered the script, never through the build's own plugin pin,
 * which may lag one release behind (#966). The sync deploy path runs
 * the same {@link CentralStaging} logic in-process without this goal.
 *
 * <p>Usage (as rendered into the sentinel script):
 * <pre>
 * mvn -N -B network.ike.tooling:ike-maven-plugin:242:central-stage \
 *     -Dike.central.buildRoot=/path/to/worktree \
 *     -Dike.central.stagingDir=/path/to/worktree/target/staging-deploy
 * </pre>
 *
 * <p>Fails (non-zero) when a swapped POM does not verify, so the
 * sentinel script treats it as a retry-eligible cycle failure — a
 * stub can no longer reach Central silently.
 */
@org.apache.maven.api.plugin.annotations.Mojo(
        name = IkeGoal.NAME_CENTRAL_STAGE, projectRequired = false)
public class CentralStageMojo implements Mojo {

    /** Maven logger, injected by the plugin runtime. */
    @Inject
    Log log;

    /** @return the injected Maven logger */
    Log getLog() { return log; }

    /**
     * The build root that produced the staged artifacts — the release
     * worktree. Walked for {@code target/generated-bom.xml} outputs
     * and must contain the {@code mvnw} wrapper used for the re-sign
     * deploy.
     */
    @Parameter(property = "ike.central.buildRoot", required = true)
    String buildRoot;

    /**
     * The {@code staging-deploy} directory produced by the signed
     * staging deploy — the bundle JReleaser uploads.
     */
    @Parameter(property = "ike.central.stagingDir", required = true)
    String stagingDir;

    /** Creates this goal instance. */
    public CentralStageMojo() {}

    @Override
    public void execute() {
        Path root = requireDirectory(buildRoot, "ike.central.buildRoot");
        Path staging = requireDirectory(stagingDir, "ike.central.stagingDir");
        File mvnw = root.resolve("mvnw").toFile();
        if (!mvnw.isFile()) {
            throw new MojoException("No mvnw wrapper at " + mvnw
                    + " — central-stage re-signs swapped BOMs through "
                    + "the build root's own Maven wrapper");
        }
        CentralStaging.pruneBuildPoms(getLog(), staging);
        CentralStaging.swapGeneratedBoms(getLog(), root.toFile(), mvnw,
                staging);
    }

    /**
     * Resolves a required directory parameter, failing with the
     * property name when it is absent or not a directory.
     *
     * @param value    the configured parameter value
     * @param property the property name for the failure message
     * @return the resolved directory path
     * @throws MojoException when the value is not an existing directory
     */
    static Path requireDirectory(String value, String property) {
        Path p = Paths.get(value);
        if (!Files.isDirectory(p)) {
            throw new MojoException(property + "=" + value
                    + " is not an existing directory");
        }
        return p;
    }
}
