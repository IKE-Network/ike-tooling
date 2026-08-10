package network.ike.tooling.buildreport;

/**
 * The structured event family a {@link Finding} was derived from.
 *
 * <p>Categories mirror the collection surfaces described in the
 * dev-build-report-extension design note: execution events, repository
 * events, and model problems. Raw log text is deliberately not a
 * category.</p>
 */
public enum FindingCategory {

    /** Project and mojo execution results ({@code ExecutionEvent}). */
    EXECUTION,

    /** Artifact and metadata resolution results ({@code RepositoryEvent}). */
    REPOSITORY,

    /** Model problems reported while building effective models. */
    MODEL
}
