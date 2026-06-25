package network.ike.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@link WorkingSet} a working-tree workspace operation acts
 * on, from a starting directory (IKE-Network/ike-issues#609, under #601).
 *
 * <p>Searches upward from the start directory for a {@code workspace.yaml}.
 * When one is found, the working set is that workspace — its declared
 * subprojects plus the workspace root. When none is found, the working set
 * is the single repository at the start directory: a working set of one.
 *
 * <p>This is the single home for the "am I in a workspace, or a lone repo?"
 * decision that working-tree goals otherwise each make for themselves — the
 * scattered {@code isWorkspaceMode()} + bare-mode branches the migration in
 * ike-issues#611 retires.
 */
public final class WorkingSetResolver {

    /** The manifest file name searched for when walking up from a directory. */
    public static final String MANIFEST_FILE = "workspace.yaml";

    private WorkingSetResolver() {}

    /**
     * Resolve the working set from a starting directory.
     *
     * @param startDir the directory to resolve from (typically the CWD)
     * @return a workspace working set when a {@code workspace.yaml} is found
     *         at or above {@code startDir}; otherwise the single-repository
     *         working set rooted at {@code startDir}
     */
    public static WorkingSet resolve(Path startDir) {
        Path manifest = findManifest(startDir);
        return manifest == null ? singleRepo(startDir) : workspace(manifest);
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

    private static WorkingSet workspace(Path manifest) {
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
     * {@code workspace-root:} {@code artifactId} (schema 1.1+); falls back to
     * the root directory name when absent (legacy 1.0 manifests).
     *
     * @param model the parsed manifest
     * @param root  the workspace root directory
     * @return the workspace-root artifactId when present, else the directory
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
     * Search upward from {@code startDir} for a {@code workspace.yaml}.
     *
     * @param startDir the directory to start the upward search from
     * @return the absolute manifest path, or {@code null} if none is found
     */
    static Path findManifest(Path startDir) {
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(MANIFEST_FILE);
            if (candidate.toFile().exists()) {
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
