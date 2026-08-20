package network.ike.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@link WorkingSet} a working-tree operation acts on, from a
 * starting directory (IKE-Network/ike-issues#609, under #601).
 *
 * <p>Searches upward from the start directory for a manifest —
 * {@value #MANIFEST_FILE}, or {@value #LEGACY_MANIFEST_FILE} in a working
 * set the scaffold has not upgraded yet (ike-issues#1054). When one is
 * found, the working set is what it declares: its subprojects plus the
 * working-set root. When none is found, the working set is the single
 * repository at the start directory: a working set of one.
 *
 * <p>This is the single home for the "am I in a declared working set, or a
 * lone repo?" decision that working-tree goals otherwise each make for
 * themselves — the scattered {@code isWorkspaceMode()} + bare-mode branches
 * the migration in ike-issues#611 retires.
 */
public final class WorkingSetResolver {

    /**
     * The manifest file name — the thing it describes is a <em>working
     * set</em>, so that is what the file is called
     * (IKE-Network/ike-issues#1054).
     */
    public static final String MANIFEST_FILE = "working-set.yaml";

    /**
     * The manifest's former name. Read forever: manifests written before
     * the rename live in tagged trees, and a working set is upgraded by
     * the scaffold, not by every reader assuming it already happened.
     */
    public static final String LEGACY_MANIFEST_FILE = "workspace.yaml";

    /**
     * The manifest already present in a directory — the current name
     * preferred, the former name accepted.
     *
     * @param dir the directory to look in
     * @return the existing manifest path, or {@code null} when the
     *         directory holds neither
     */
    public static Path manifestIn(Path dir) {
        Path current = dir.resolve(MANIFEST_FILE);
        if (current.toFile().exists()) {
            return current;
        }
        Path legacy = dir.resolve(LEGACY_MANIFEST_FILE);
        return legacy.toFile().exists() ? legacy : null;
    }

    /**
     * Where a writer should write a directory's manifest: the file that is
     * already there, or the current name when there is none. A writer must
     * never introduce a second manifest beside an existing one.
     *
     * @param dir the working-set root directory
     * @return the path to write
     */
    public static Path manifestToWrite(Path dir) {
        Path existing = manifestIn(dir);
        return existing != null ? existing : dir.resolve(MANIFEST_FILE);
    }

    private WorkingSetResolver() {}

    /**
     * Resolve the working set from a starting directory.
     *
     * @param startDir the directory to resolve from (typically the CWD)
     * @return the declared working set when a manifest is found at or
     *         above {@code startDir}; otherwise the single-repository
     *         working set rooted at {@code startDir}
     */
    public static WorkingSet resolve(Path startDir) {
        Path manifest = findManifest(startDir);
        return manifest == null ? singleRepo(startDir) : declared(manifest);
    }

    /**
     * Build a single-repository working set rooted at {@code dir}, without
     * searching for a manifest — a working set of one.
     *
     * @param dir the repository directory
     * @return the single-repository working set
     */
    public static WorkingSet singleRepo(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        String name = fileName(root);
        return new WorkingSet(root, null, name,
                List.of(WorkingSet.Member.aggregator(name, root)));
    }

    private static WorkingSet declared(Path manifest) {
        Path root = manifest.getParent();
        Manifest model = ManifestReader.read(manifest);
        List<WorkingSet.Member> members = new ArrayList<>();
        for (String name : model.subprojects().keySet()) {
            members.add(WorkingSet.Member.subproject(name, root.resolve(name)));
        }
        members.add(WorkingSet.Member.aggregator(fileName(root), root));
        return new WorkingSet(root, manifest, baseName(model, root),
                List.copyOf(members));
    }

    /**
     * Resolve the working set's base name — the identity used for derived
     * names such as sibling directories. Prefers the manifest
     * {@code workspace-root:} {@code artifactId} (schema 1.1+ — the key
     * keeps its spelling; renaming a manifest key is a separate migration);
     * falls back to the root directory name when absent (legacy 1.0
     * manifests).
     *
     * @param model the parsed manifest
     * @param root  the working-set root directory
     * @return the declared root artifactId when present, else the directory
     *         name
     */
    private static String baseName(Manifest model, Path root) {
        WorkspaceRoot wr = model.workspaceRoot();
        if (wr != null && wr.artifactId() != null && !wr.artifactId().isBlank()) {
            return wr.artifactId();
        }
        return fileName(root);
    }

    /**
     * Search upward from {@code startDir} for a manifest, under either
     * name.
     *
     * @param startDir the directory to start the upward search from
     * @return the absolute manifest path, or {@code null} if none is found
     */
    static Path findManifest(Path startDir) {
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = manifestIn(dir);
            if (candidate != null) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String fileName(Path dir) {
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }
}
