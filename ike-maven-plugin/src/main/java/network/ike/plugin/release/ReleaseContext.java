package network.ike.plugin.release;

import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Per-invocation context carried through the release pipeline.
 *
 * <p>Bundles the resolved git root, Maven wrapper path, logger, and
 * the {@link ReleaseRequest} (user-supplied inputs). Threaded through
 * every release helper as a single parameter instead of positional
 * {@code gitRoot}/{@code mvnw} arguments, so that downstream phase
 * objects (FinalizePhase, NexusPhase, CentralPhase, ...) can be
 * extracted with stable method signatures.
 *
 * <p>Two-stage construction: the context is built early in
 * {@code runGoal()} with {@code mvnw} as {@code null} so that
 * {@code ReleasePrep} (which doesn't need {@code mvnw}) can run
 * against it. After {@code mvnw} is resolved, the mojo refines the
 * context via {@link #withMvnw(File)} before any phase that does
 * need the Maven wrapper runs (logAudit, LocalPhase, NexusPhase,
 * CentralPhase, ...). Phases that don't need {@code mvnw} must not
 * dereference it; those that do are guaranteed to run only after
 * refinement.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4 P2
 * prep commit (IKE-Network/ike-issues#489). Mutable per-invocation
 * state (deploy attempt counts, async-spawn paths, etc.) lives on
 * the {@code NexusOutcome} / {@code CentralOutcome} records on the
 * mojo for now; a dedicated {@code ReleaseRunState} holder is
 * introduced when the orchestrator lands.
 *
 * @param gitRoot the resolved git root for the project under release
 * @param mvnw    the resolved {@code mvnw} wrapper script; {@code null}
 *                until refined post-ReleasePrep via {@link #withMvnw(File)}
 * @param log     the Maven plugin logger
 * @param request the user-supplied release inputs
 */
public record ReleaseContext(File gitRoot, File mvnw, Log log, ReleaseRequest request) {

    /**
     * Returns a copy of this context with {@code mvnw} replaced.
     *
     * <p>Used to refine the early-built ({@code mvnw == null}) context
     * once the wrapper is resolved.
     *
     * @param mvnw the resolved Maven wrapper script
     * @return a new {@code ReleaseContext} with the same {@code gitRoot},
     *         {@code log}, and {@code request}, and the given {@code mvnw}
     */
    public ReleaseContext withMvnw(File mvnw) {
        return new ReleaseContext(gitRoot, mvnw, log, request);
    }
}
