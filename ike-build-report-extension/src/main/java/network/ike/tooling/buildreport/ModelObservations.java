package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Session-scoped bridge between the two dependency-injection systems
 * this extension spans.
 *
 * <p>{@link BomImportModelObserver} is a new-API component
 * ({@code maven-api-di}); {@link BuildReportSpy} is a classic sisu
 * component. Maven instantiates them in <em>separate</em> DI
 * containers, so neither can inject the other — but both load from the
 * single extension classloader, so static state is shared. The spy
 * resets this bridge at {@code ProjectDiscoveryStarted} (before model
 * building) and drains it at {@code SessionEnded}, which also keeps
 * long-lived JVMs (the IDE's maven-server) from accumulating
 * observations across sessions.</p>
 */
public final class ModelObservations {

    /**
     * One file-stage model observation.
     *
     * @param groupId      the model's group id, parent-defaulted
     * @param artifactId   the model's artifact id
     * @param version      the model's version, parent-defaulted
     * @param pomFile      the POM file the model was read from
     * @param bomImports   the model's import-scoped POM dependencies
     * @param repositories the model's declared repositories, both
     *                     {@code <repositories>} and
     *                     {@code <pluginRepositories>}
     */
    public record FileModel(
            String groupId,
            String artifactId,
            String version,
            Path pomFile,
            List<BomImport> bomImports,
            List<RepositoryDeclaration> repositories) {

        /**
         * Validates identity fields and defensively copies the lists.
         *
         * @param groupId      the model's group id, parent-defaulted
         * @param artifactId   the model's artifact id
         * @param version      the model's version, parent-defaulted
         * @param pomFile      the POM file the model was read from
         * @param bomImports   the model's import-scoped POM dependencies
         * @param repositories the model's declared repositories
         */
        public FileModel {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(pomFile, "pomFile");
            bomImports = List.copyOf(bomImports);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }

        /**
         * Creates an observation with no repository declarations.
         *
         * @param groupId    the model's group id, parent-defaulted
         * @param artifactId the model's artifact id
         * @param version    the model's version, parent-defaulted
         * @param pomFile    the POM file the model was read from
         * @param bomImports the model's import-scoped POM dependencies
         */
        public FileModel(
                String groupId, String artifactId, String version, Path pomFile, List<BomImport> bomImports) {
            this(groupId, artifactId, version, pomFile, bomImports, List.of());
        }

        /**
         * Renders the observation's coordinates.
         *
         * @return {@code groupId:artifactId:version} with unknowns as {@code ?}
         */
        public String gav() {
            return (groupId == null ? "?" : groupId) + ":" + artifactId + ":"
                    + (version == null ? "?" : version);
        }
    }

    /**
     * One import-scoped POM dependency observed in a file model.
     *
     * @param groupId    the imported BOM's group id
     * @param artifactId the imported BOM's artifact id
     * @param version    the imported BOM's version, property-resolved
     *                   against the declaring model where possible;
     *                   {@code null} when unresolvable
     * @param line       the declaration's line number, or {@code -1}
     */
    public record BomImport(String groupId, String artifactId, String version, int line) {

        /**
         * Renders the import's coordinates.
         *
         * @return {@code groupId:artifactId:version} with unknowns as {@code ?}
         */
        public String gav() {
            return (groupId == null ? "?" : groupId) + ":" + (artifactId == null ? "?" : artifactId)
                    + ":" + (version == null ? "?" : version);
        }
    }

    /**
     * One repository declared by a file model.
     *
     * <p>Recorded for every POM Maven reads, not only workspace
     * members, because the repositories that cause resolution failures
     * are typically declared by a third-party dependency's POM rather
     * than by anything in the workspace — the single most useful fact
     * a reader of a repository finding needs, and one the resolver
     * event itself does not carry.</p>
     *
     * @param id     the declared repository id, as written in the POM
     * @param url    the declared repository URL
     * @param plugin whether the declaration is a
     *               {@code <pluginRepository>} rather than a
     *               {@code <repository>}
     */
    public record RepositoryDeclaration(String id, String url, boolean plugin) {

        /**
         * Normalizes the declared fields.
         *
         * @param id     the declared repository id
         * @param url    the declared repository URL
         * @param plugin whether this is a plugin repository declaration
         */
        public RepositoryDeclaration {
            id = id == null ? "" : id.strip();
            url = url == null ? "" : url.strip();
        }
    }

    private static final ConcurrentLinkedQueue<FileModel> OBSERVATIONS = new ConcurrentLinkedQueue<>();

    private ModelObservations() {
    }

    /**
     * Records one file-model observation; called by the model observer
     * for every POM Maven reads from disk.
     *
     * @param observation the observation to record
     */
    public static void record(FileModel observation) {
        OBSERVATIONS.add(Objects.requireNonNull(observation, "observation"));
    }

    /**
     * Returns a snapshot of all observations recorded since the last
     * {@link #reset()}.
     *
     * @return an immutable snapshot in record order
     */
    public static List<FileModel> snapshot() {
        return List.copyOf(new ArrayList<>(OBSERVATIONS));
    }

    /**
     * Clears all observations; called at the start of each session so
     * long-lived JVMs never carry observations across sessions.
     */
    public static void reset() {
        OBSERVATIONS.clear();
    }
}
