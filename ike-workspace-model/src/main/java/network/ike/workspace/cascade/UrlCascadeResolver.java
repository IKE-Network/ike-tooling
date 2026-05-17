package network.ike.workspace.cascade;

import network.ike.plugin.ReleaseSupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A {@link CascadeAssembler.CascadeResolver} that resolves cascade
 * members from their git {@code url} rather than local sibling
 * checkouts (IKE-Network/ike-issues#429).
 *
 * <p>{@code CascadeAssembler} stitches the release cascade from each
 * member's per-project {@code release-cascade.yaml}. The sibling-checkout
 * resolver assumes every member is a directory alongside the repo the
 * goal runs in — true on a developer workstation, false on a CI agent
 * that has only one repo checked out. This resolver closes that gap:
 * it shallow-clones each member from its edge's {@code url} and reads
 * {@code src/main/cascade/release-cascade.yaml} from the clone.
 *
 * <p>Clones land in a caller-supplied directory — one subdirectory per
 * member, named by the edge's {@code repo}. A member already cloned
 * there is refreshed with {@code git pull --ff-only} rather than
 * re-cloned. The resolver is host-agnostic: it passes the {@code url}
 * to {@code git clone} verbatim.
 */
public final class UrlCascadeResolver
        implements CascadeAssembler.CascadeResolver {

    private final Path cloneDir;
    private final Consumer<String> log;

    /**
     * Creates a resolver that clones into {@code cloneDir} and logs
     * nothing.
     *
     * @param cloneDir the directory shallow clones are placed under
     */
    public UrlCascadeResolver(Path cloneDir) {
        this(cloneDir, msg -> { });
    }

    /**
     * Creates a resolver that clones into {@code cloneDir} and reports
     * progress through {@code log}.
     *
     * @param cloneDir the directory shallow clones are placed under
     * @param log      sink for one progress line per member resolved
     */
    public UrlCascadeResolver(Path cloneDir, Consumer<String> log) {
        if (cloneDir == null) {
            throw new IllegalArgumentException("cloneDir is required");
        }
        if (log == null) {
            throw new IllegalArgumentException("log is required");
        }
        this.cloneDir = cloneDir;
        this.log = log;
    }

    /**
     * Shallow-clones the member named by {@code edge} and parses its
     * {@code release-cascade.yaml}.
     *
     * @param edge the edge naming the member; must carry a {@code url}
     * @return the member's parsed manifest
     * @throws IllegalStateException if the edge has no {@code url} or
     *                               the clone has no manifest
     * @throws UncheckedIOException  if the clone directory cannot be
     *                               created
     */
    @Override
    public ProjectCascade resolve(CascadeEdge edge) {
        if (edge.url() == null || edge.url().isBlank()) {
            throw new IllegalStateException("cascade edge " + edge.ga()
                    + " has no url — cannot resolve it without a local"
                    + " checkout");
        }
        try {
            Files.createDirectories(cloneDir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot create cascade clone directory "
                    + cloneDir, e);
        }

        Path checkout = cloneDir.resolve(edge.repo());
        if (Files.isDirectory(checkout.resolve(".git"))) {
            log.accept("Refreshing " + edge.repo() + " in " + checkout);
            ReleaseSupport.execCapture(checkout.toFile(),
                    "git", "pull", "--ff-only");
        } else {
            log.accept("Cloning " + edge.repo() + " from " + edge.url());
            ReleaseSupport.execCapture(cloneDir.toFile(),
                    "git", "clone", "--depth", "1",
                    edge.url(), edge.repo());
        }

        Path manifest = checkout.resolve(
                ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("cascade member "
                    + edge.ga() + " (cloned from " + edge.url()
                    + ") has no " + ProjectCascadeIo.MANIFEST_RELATIVE_PATH);
        }
        return ProjectCascadeIo.read(manifest);
    }
}
