package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.InputLocation;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Repository;
import org.apache.maven.api.spi.ModelTransformer;
import org.apache.maven.api.spi.ModelTransformerException;

/**
 * Observes every file-stage model Maven reads and records reactor
 * coordinates, import-scoped POM dependencies, and declared
 * repositories into {@link ModelObservations}, for session-end
 * cross-referencing by {@link BuildReportSpy}.
 *
 * <p>Maven reads dependency POMs from the local repository through the
 * same path as workspace POMs, so this observer sees third-party
 * {@code <repositories>} declarations too — the raw material
 * {@link RepositoryProvenance} turns into "who asked for this
 * repository" on a repository finding.</p>
 *
 * <p>A pure observer: the model is always returned unchanged. This is
 * a new-API component discovered through the {@code maven-api-di}
 * annotation index — a separate DI container from the classic sisu
 * container that hosts the spy, which is why the hand-off happens
 * through the static bridge rather than injection.</p>
 */
@Named("ike-build-report-model-observer")
@Singleton
public class BomImportModelObserver implements ModelTransformer {

    /** Creates the observer. DI-only; not for direct construction. */
    public BomImportModelObserver() {
    }

    /**
     * Records the model's coordinates and BOM imports; never modifies
     * the model.
     *
     * @param model the file-stage parsed Maven model
     * @return the same model, always
     * @throws ModelTransformerException never thrown; declared for SPI
     *                                   conformance
     */
    @Override
    public Model transformFileModel(Model model) throws ModelTransformerException {
        try {
            Path pomFile = model.getPomFile();
            if (pomFile != null) {
                ModelObservations.record(new ModelObservations.FileModel(
                        groupId(model),
                        model.getArtifactId(),
                        version(model),
                        pomFile,
                        bomImports(model),
                        repositories(model)));
            }
        } catch (Exception e) {
            // Observation must never interfere with model building.
        }
        return model;
    }

    private static String groupId(Model model) {
        if (model.getGroupId() != null) {
            return model.getGroupId();
        }
        Parent parent = model.getParent();
        return parent == null ? null : parent.getGroupId();
    }

    private static String version(Model model) {
        if (model.getVersion() != null) {
            return model.getVersion();
        }
        Parent parent = model.getParent();
        return parent == null ? null : parent.getVersion();
    }

    private static List<ModelObservations.RepositoryDeclaration> repositories(Model model) {
        List<ModelObservations.RepositoryDeclaration> declarations = new ArrayList<>();
        collect(declarations, model.getRepositories(), false);
        collect(declarations, model.getPluginRepositories(), true);
        return declarations;
    }

    private static void collect(
            List<ModelObservations.RepositoryDeclaration> into, List<Repository> declared, boolean plugin) {
        if (declared == null) {
            return;
        }
        for (Repository repository : declared) {
            if (repository.getUrl() == null || repository.getUrl().isBlank()) {
                continue;
            }
            into.add(new ModelObservations.RepositoryDeclaration(
                    repository.getId(), repository.getUrl(), plugin));
        }
    }

    private static List<ModelObservations.BomImport> bomImports(Model model) {
        DependencyManagement management = model.getDependencyManagement();
        if (management == null || management.getDependencies() == null) {
            return List.of();
        }
        Map<String, String> properties = model.getProperties();
        List<ModelObservations.BomImport> imports = new ArrayList<>();
        for (Dependency dependency : management.getDependencies()) {
            if (!"pom".equals(dependency.getType()) || !"import".equals(dependency.getScope())) {
                continue;
            }
            imports.add(new ModelObservations.BomImport(
                    ModelCrossReference.resolveVersion(dependency.getGroupId(), properties),
                    dependency.getArtifactId(),
                    ModelCrossReference.resolveVersion(dependency.getVersion(), properties),
                    lineOf(dependency)));
        }
        return imports;
    }

    private static int lineOf(Dependency dependency) {
        InputLocation location = dependency.getLocation("artifactId");
        if (location == null) {
            location = dependency.getLocation("");
        }
        return location == null ? -1 : location.getLineNumber();
    }
}
