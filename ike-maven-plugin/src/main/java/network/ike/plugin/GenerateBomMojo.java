package network.ike.plugin;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Scm;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generate a Bill of Materials POM from another module's dependency management.
 *
 * <h2>Why this goal exists</h2>
 *
 * <p>Maven 4's consumer POM resolves property references in
 * {@code <dependencies>} but <em>not</em> in {@code <dependencyManagement>}.
 * When an external project imports a BOM via {@code <scope>import</scope>},
 * it receives the consumer POM — so any {@code ${…}} expressions in managed
 * dependency versions arrive unresolved and the build fails.
 * {@code flatten-maven-plugin}, the Maven 3 solution for this class of
 * problem, has not been updated for the Maven 4 model changes.</p>
 *
 * <p>This goal works around the limitation by reading the
 * {@code <dependencyManagement>} entries from a source module (default:
 * {@code ike-parent}) in the reactor, resolving every property reference to
 * a literal value, and writing a standalone BOM POM to
 * {@code target/generated-bom.xml}. The Maven 4 {@code Project} is
 * immutable, so the generated POM cannot replace the stub in the same
 * build; {@code ike:release-publish} swaps it into the Maven Central
 * staging bundle — re-signed and verified — before upload
 * (IKE-Network/ike-issues#853), so external consumers get a fully
 * populated BOM without any manual maintenance. The generated POM
 * uses the {@code 4.0.0} model version for maximum consumer
 * compatibility.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>Bind this goal to a POM-packaged stub module in the reactor,
 * ordered <em>after</em> the source module and the plugin module:</p>
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;network.ike&lt;/groupId&gt;
 *   &lt;artifactId&gt;ike-maven-plugin&lt;/artifactId&gt;
 *   &lt;executions&gt;
 *     &lt;execution&gt;
 *       &lt;id&gt;generate-bom&lt;/id&gt;
 *       &lt;goals&gt;&lt;goal&gt;generate-bom&lt;/goal&gt;&lt;/goals&gt;
 *     &lt;/execution&gt;
 *   &lt;/executions&gt;
 * &lt;/plugin&gt;
 * </pre>
 */
@Mojo(name = IkeGoal.NAME_GENERATE_BOM,
      defaultPhase = "generate-resources",
      projectRequired = true)
public class GenerateBomMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** The current project (injected by Maven 4). */
    @org.apache.maven.api.di.Inject
    private Project project;

    /** Reactor projects via Maven 4 Session. */
    @org.apache.maven.api.di.Inject
    private Session session;

    /**
     * Artifact ID of the reactor module whose {@code <dependencyManagement>}
     * entries should be copied into the generated BOM.
     */
    @Parameter(property = "bom.source", defaultValue = "ike-parent")
    private String sourceArtifactId;

    /** Creates this goal instance. */
    public GenerateBomMojo() {}

    @Override
    public void execute() throws MojoException {
        // ── Find source module in reactor ────────────────────────────
        List<Project> reactorProjects = session.getProjects();
        Project source = reactorProjects.stream()
                .filter(p -> p.getArtifactId().equals(sourceArtifactId))
                .findFirst()
                .orElseThrow(() -> new MojoException(
                        sourceArtifactId + " not found in reactor. "
                        + "Ensure it is listed before this module in <subprojects>."));

        DependencyManagement depMgmt = source.getModel().getDependencyManagement();
        if (depMgmt == null || depMgmt.getDependencies().isEmpty()) {
            throw new MojoException(
                    sourceArtifactId + " has no <dependencyManagement> entries.");
        }

        List<Dependency> deps = depMgmt.getDependencies();

        // ── Convert Maven Dependency objects to BomEntry records ─────
        List<BomEntry> entries = deps.stream()
                .map(dep -> new BomEntry(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(),
                        dep.getClassifier(), dep.getType(), dep.getScope()))
                .toList();

        // ── Carry Central-required metadata into the standalone BOM ──
        // The stub inherits <licenses>/<developers>/<scm> through its
        // parent chain; the generated BOM has no parent, so the blocks
        // must be written explicitly or Central's PomChecker rejects
        // the upload (IKE-Network/ike-issues#967).
        List<BomLicense> licenses = project.getModel().getLicenses().stream()
                .map(l -> new BomLicense(l.getName(), l.getUrl()))
                .toList();
        List<BomDeveloper> developers = project.getModel().getDevelopers().stream()
                .map(d -> new BomDeveloper(d.getId(), d.getName(), d.getEmail()))
                .toList();
        Scm modelScm = project.getModel().getScm();
        BomScm scm = modelScm == null ? null : new BomScm(
                modelScm.getConnection(), modelScm.getDeveloperConnection(),
                modelScm.getUrl(), modelScm.getTag());

        // ── Generate BOM POM ─────────────────────────────────────────
        String bomXml = buildBomXml(
                project.getGroupId(), project.getArtifactId(), project.getVersion(),
                project.getModel().getName(), project.getModel().getDescription(),
                project.getModel().getUrl(),
                licenses, developers, scm,
                entries);

        Path targetDir = Path.of(project.getBuild().getDirectory());
        try {
            Files.createDirectories(targetDir);
            Path bomPom = targetDir.resolve("generated-bom.xml");
            Files.writeString(bomPom, bomXml);

            // Maven 4's Project is immutable, so the stub POM cannot be
            // replaced here; the release pipeline swaps this file into
            // the Central staging bundle (GeneratedBomSwap, #853).

            getLog().info("Generated BOM with " + deps.size()
                    + " managed entries from " + sourceArtifactId);
        } catch (IOException e) {
            throw new MojoException("Failed to write generated BOM", e);
        }
    }

    // ── XML generation (pure, static, testable) ─────────────────────

    /**
     * Build a complete BOM POM XML string from the given project
     * coordinates and dependency entries.
     *
     * <p>This is a pure function with no Maven or I/O dependencies,
     * suitable for direct unit testing.
     *
     * @param groupId     project group ID
     * @param artifactId  project artifact ID
     * @param version     project version
     * @param name        project display name (XML-escaped internally)
     * @param description project description (may be null)
     * @param url         project URL (may be null)
     * @param licenses    license entries; empty omits the block — Maven
     *                    Central requires at least one entry
     *                    (IKE-Network/ike-issues#967)
     * @param developers  developer entries; empty omits the block —
     *                    Maven Central requires at least one entry (#967)
     * @param scm         SCM block; null omits it — Maven Central
     *                    requires it (#967)
     * @param entries     managed dependency entries
     * @return well-formed POM XML
     */
    public static String buildBomXml(String groupId, String artifactId,
                                      String version, String name,
                                      String description, String url,
                                      List<BomLicense> licenses,
                                      List<BomDeveloper> developers,
                                      BomScm scm,
                                      List<BomEntry> entries) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!--\n");
        xml.append("  Auto-generated BOM — do not edit.\n");
        xml.append("  Generated by: ")
                .append(IkeGoal.GENERATE_BOM.qualified()).append("\n");
        xml.append("-->\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0\n");
        xml.append("         http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        xml.append("    <modelVersion>4.0.0</modelVersion>\n\n");

        xml.append("    <groupId>").append(groupId).append("</groupId>\n");
        xml.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        xml.append("    <version>").append(version).append("</version>\n");
        xml.append("    <packaging>pom</packaging>\n\n");

        xml.append("    <name>").append(escapeXml(name)).append("</name>\n");
        if (description != null) {
            xml.append("    <description>")
               .append(escapeXml(description.strip()))
               .append("</description>\n");
        }
        xml.append("    <url>").append(url != null ? url : "").append("</url>\n\n");

        // Central-required publishing metadata (#967): the stub POM
        // inherits these through its parent chain; a standalone BOM
        // must carry them itself.
        if (!licenses.isEmpty()) {
            xml.append("    <licenses>\n");
            for (BomLicense license : licenses) {
                xml.append("        <license>\n");
                if (license.name() != null) {
                    xml.append("            <name>")
                       .append(escapeXml(license.name())).append("</name>\n");
                }
                if (license.url() != null) {
                    xml.append("            <url>")
                       .append(license.url()).append("</url>\n");
                }
                xml.append("        </license>\n");
            }
            xml.append("    </licenses>\n\n");
        }
        if (!developers.isEmpty()) {
            xml.append("    <developers>\n");
            for (BomDeveloper developer : developers) {
                xml.append("        <developer>\n");
                if (developer.id() != null) {
                    xml.append("            <id>")
                       .append(escapeXml(developer.id())).append("</id>\n");
                }
                if (developer.name() != null) {
                    xml.append("            <name>")
                       .append(escapeXml(developer.name())).append("</name>\n");
                }
                if (developer.email() != null) {
                    xml.append("            <email>")
                       .append(escapeXml(developer.email())).append("</email>\n");
                }
                xml.append("        </developer>\n");
            }
            xml.append("    </developers>\n\n");
        }
        if (scm != null) {
            xml.append("    <scm>\n");
            if (scm.connection() != null) {
                xml.append("        <connection>")
                   .append(escapeXml(scm.connection())).append("</connection>\n");
            }
            if (scm.developerConnection() != null) {
                xml.append("        <developerConnection>")
                   .append(escapeXml(scm.developerConnection()))
                   .append("</developerConnection>\n");
            }
            if (scm.url() != null) {
                xml.append("        <url>").append(scm.url()).append("</url>\n");
            }
            if (scm.tag() != null) {
                xml.append("        <tag>")
                   .append(escapeXml(scm.tag())).append("</tag>\n");
            }
            xml.append("    </scm>\n\n");
        }

        // Dependency Management
        xml.append("    <dependencyManagement>\n");
        xml.append("        <dependencies>\n");

        for (BomEntry entry : entries) {
            xml.append("            <dependency>\n");
            xml.append("                <groupId>").append(entry.groupId()).append("</groupId>\n");
            xml.append("                <artifactId>").append(entry.artifactId()).append("</artifactId>\n");
            xml.append("                <version>").append(entry.version()).append("</version>\n");

            if (entry.classifier() != null && !entry.classifier().isEmpty()) {
                xml.append("                <classifier>").append(entry.classifier()).append("</classifier>\n");
            }
            if (entry.type() != null && !"jar".equals(entry.type())) {
                xml.append("                <type>").append(entry.type()).append("</type>\n");
            }
            if (entry.scope() != null && !"compile".equals(entry.scope())) {
                xml.append("                <scope>").append(entry.scope()).append("</scope>\n");
            }

            xml.append("            </dependency>\n");
        }

        xml.append("        </dependencies>\n");
        xml.append("    </dependencyManagement>\n");
        xml.append("</project>\n");

        return xml.toString();
    }

    /**
     * Escape XML special characters in text content.
     *
     * @param text input text (may be null)
     * @return escaped text, or empty string if null
     */
    public static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}
