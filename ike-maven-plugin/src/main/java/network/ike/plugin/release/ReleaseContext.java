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
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4 P2
 * prep commit (IKE-Network/ike-issues#489). Mutable per-invocation
 * state (deploy attempt counts, async-spawn paths, etc.) lives on
 * the {@code NexusOutcome} / {@code CentralOutcome} records on the
 * mojo for now; a dedicated {@code ReleaseRunState} holder is
 * introduced when the orchestrator lands.
 *
 * @param gitRoot the resolved git root for the project under release
 * @param mvnw    the resolved {@code mvnw} wrapper script
 * @param log     the Maven plugin logger
 * @param request the user-supplied release inputs
 */
public record ReleaseContext(File gitRoot, File mvnw, Log log, ReleaseRequest request) {
}
