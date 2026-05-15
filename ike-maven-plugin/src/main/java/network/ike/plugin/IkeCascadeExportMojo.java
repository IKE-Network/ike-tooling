package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.workspace.cascade.ReleaseCascade;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports the foundation release cascade topology in a machine-readable
 * format (IKE-Network/ike-issues#403).
 *
 * <p>Read-only. Resolves {@code release-cascade.yaml} — the declarative
 * cascade ordering from {@code ike-build-standards} (#402) — and writes
 * it as JSON or {@code .properties} so a CI meta-runner can generate
 * the build-chain edges from the manifest instead of hand-wiring them.
 * That keeps the CI build graph derived from the single source of
 * truth rather than drifting from it.
 *
 * <p>Usage:
 * <pre>
 *   mvn ike:cascade-export                          # JSON to stdout
 *   mvn ike:cascade-export -Dformat=properties
 *   mvn ike:cascade-export -DoutputFile=target/cascade.json
 * </pre>
 */
@Mojo(name = "cascade-export", projectRequired = false, aggregator = true)
public class IkeCascadeExportMojo extends AbstractGoalMojo {

    /** Output format: {@code json} (default) or {@code properties}. */
    @Parameter(property = "format", defaultValue = "json")
    String format;

    /**
     * File to write the export to. When unset, the export is logged
     * to stdout.
     */
    @Parameter(property = "outputFile")
    String outputFile;

    /**
     * Path to the {@code release-cascade.yaml} manifest. Bound to the
     * standard {@code ike.release.cascade.manifest} property; when
     * unset the manifest is resolved from {@code target/} or the
     * {@code ike-build-standards} cascade artifact (#402, #404).
     */
    @Parameter(property = "ike.release.cascade.manifest")
    String cascadeManifest;

    /** Maven session — used to resolve the cascade artifact. */
    @Inject
    Session session;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public IkeCascadeExportMojo() {}

    @Override
    public void execute() throws MojoException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);

        CascadeExportFormat exportFormat;
        try {
            exportFormat = CascadeExportFormat.fromString(format);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        ReleaseCascade cascade = CascadeManifestResolver
                .resolve(session, gitRoot, cascadeManifest, getLog())
                .orElseThrow(() -> new MojoException(
                        "No release-cascade.yaml found. Pass "
                        + "-Dike.release.cascade.manifest=<path>, or run "
                        + "from a foundation repo that can resolve the "
                        + "ike-build-standards cascade artifact."));

        String rendered = exportFormat.render(cascade);
        String formatLabel = exportFormat.name().toLowerCase();

        if (outputFile != null && !outputFile.isBlank()) {
            Path out = Path.of(outputFile);
            try {
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                Files.writeString(out, rendered, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Could not write " + out + ": " + e.getMessage(), e);
            }
            getLog().info("Cascade exported (" + formatLabel + ") to " + out);
        } else {
            getLog().info("Cascade export (" + formatLabel + "):");
            rendered.lines().forEach(getLog()::info);
        }
    }
}
