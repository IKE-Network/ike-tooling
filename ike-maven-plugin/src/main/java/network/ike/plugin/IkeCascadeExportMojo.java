package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.workspace.cascade.CascadeAssembler;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;
import network.ike.workspace.cascade.ReleaseCascade;
import network.ike.workspace.cascade.UrlCascadeResolver;
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
 * format (IKE-Network/ike-issues#403, #420).
 *
 * <p>Read-only. The cascade is decentralized — each foundation repo
 * version-controls its own {@code src/main/cascade/release-cascade.yaml}
 * declaring only its own edges (#420). This goal reads the local
 * repo's manifest, walks the edges into its sibling checkouts to
 * assemble the full ordered graph, and writes it as JSON or
 * {@code .properties} so a CI meta-runner can derive the build-chain
 * edges from the cascade instead of hand-wiring them.
 *
 * <p>The traversal resolves each member sibling-first: a member
 * checked out as a directory alongside this repo is read from disk;
 * one that is not is shallow-cloned from its edge's {@code url}
 * (IKE-Network/ike-issues#429). The goal therefore works both on a
 * developer workstation with sibling checkouts and on a CI agent
 * that has only this repo checked out.
 *
 * <p>Usage:
 * <pre>
 *   mvn ike:cascade-export                          # JSON to stdout
 *   mvn ike:cascade-export -Dformat=properties
 *   mvn ike:cascade-export -DoutputFile=target/cascade.json
 *   mvn ike:cascade-export -Dike.release.cascade.clone-dir=/path
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
     * Directory for the shallow clones made when a cascade member is
     * not checked out as a local sibling (IKE-Network/ike-issues#429).
     * Defaults to a fresh temporary directory.
     */
    @Parameter(property = "ike.release.cascade.clone-dir")
    String cascadeCloneDir;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public IkeCascadeExportMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);

        CascadeExportFormat exportFormat;
        try {
            exportFormat = CascadeExportFormat.fromString(format);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        ReleaseCascade cascade = assembleCascade(gitRoot);
        String rendered = exportFormat.render(cascade);
        String formatLabel = exportFormat.name().toLowerCase();

        String location;
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
            location = "written to `" + out + "`";
        } else {
            getLog().info("Cascade export (" + formatLabel + "):");
            rendered.lines().forEach(getLog()::info);
            location = "printed to the build log";
        }

        String report = new GoalReportBuilder()
                .section("Cascade export")
                .paragraph("Assembled the release cascade from the"
                        + " per-project `release-cascade.yaml` manifests"
                        + " and exported it as **" + formatLabel + "**, "
                        + location + ".")
                .codeBlock(formatLabel, rendered)
                .build();
        return new GoalReportSpec(IkeGoal.CASCADE_EXPORT,
                startDir.toPath(), report);
    }

    /**
     * Reads the local repo's {@code release-cascade.yaml} and assembles
     * the full cascade graph. Each edge is resolved sibling-first: a
     * member checked out alongside this repo is read from disk; one
     * that is not is shallow-cloned from its {@code url}.
     */
    private ReleaseCascade assembleCascade(File gitRoot) {
        Path localManifest = gitRoot.toPath().resolve(
                ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
        ProjectCascade local = ProjectCascadeIo.load(localManifest)
                .orElseThrow(() -> new MojoException(
                        "No " + ProjectCascadeIo.MANIFEST_RELATIVE_PATH
                        + " in " + gitRoot + " — run ike:cascade-export"
                        + " from a foundation cascade repo."));

        File rootPom = new File(gitRoot, "pom.xml");
        CascadeEdge start = new CascadeEdge(
                ReleaseSupport.readPomGroupId(rootPom),
                ReleaseSupport.readPomArtifactId(rootPom),
                gitRoot.getName(), null, null);

        File siblings = gitRoot.getParentFile();
        UrlCascadeResolver urlResolver = new UrlCascadeResolver(
                resolveCloneDir(), getLog()::info);
        try {
            return CascadeAssembler.assemble(start, local, edge -> {
                Path sibling = siblings.toPath().resolve(edge.repo())
                        .resolve(ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
                if (Files.isRegularFile(sibling)) {
                    return ProjectCascadeIo.read(sibling);
                }
                return urlResolver.resolve(edge);
            });
        } catch (RuntimeException e) {
            throw new MojoException(
                    "Cannot assemble the release cascade: "
                    + e.getMessage() + " — a member not checked out as a"
                    + " local sibling is cloned from its url, so every"
                    + " edge must declare one.", e);
        }
    }

    /**
     * The directory shallow clones land in — the
     * {@code ike.release.cascade.clone-dir} property, or a fresh
     * temporary directory when it is unset.
     */
    private Path resolveCloneDir() {
        if (cascadeCloneDir != null && !cascadeCloneDir.isBlank()) {
            return Path.of(cascadeCloneDir);
        }
        try {
            return Files.createTempDirectory("ike-cascade-");
        } catch (IOException e) {
            throw new MojoException(
                    "cannot create a temporary clone directory: "
                    + e.getMessage(), e);
        }
    }
}
