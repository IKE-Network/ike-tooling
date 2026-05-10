package network.ike.workspace;

import java.util.List;

/**
 * A workspace subproject — one git repository in the workspace manifest.
 *
 * <p>The {@code state}, {@code tag}, and {@code kind} fields express the
 * subproject's <strong>alignment</strong> with the workspace's release
 * cascade (ike-issues#233). The four-state alignment model:
 *
 * <ul>
 *   <li><b>snapshot-aligned</b> — {@code state="snapshot"} (the default
 *       on legacy manifests). The subproject is in the workspace, tracked
 *       as a SNAPSHOT, and released by {@code ws:release}.</li>
 *   <li><b>tag-aligned</b> — {@code state="tag-aligned"} with
 *       {@code tag="<git-tag>"} and {@code kind="release"} or
 *       {@code kind="checkpoint"}. Source on disk for IDE debugging,
 *       but pinned at a tag and not in the release cascade.</li>
 *   <li><b>external-consumer</b> — absent from the manifest entirely;
 *       referenced by released GAV from Nexus.</li>
 *   <li><b>unrelated</b> — neither in the manifest nor referenced.</li>
 * </ul>
 *
 * <p>Goals introduced by #233 ({@code ws:attach-snapshot},
 * {@code ws:attach-release}, {@code ws:attach-checkpoint},
 * {@code ws:promote}, {@code ws:demote}, {@code ws:detach}) drive
 * transitions across the lattice; {@code ws:add} / {@code ws:remove}
 * remain shortcuts for external-consumer ⇄ snapshot-aligned.
 *
 * @param name         the subproject identifier (directory name and YAML key)
 * @param description  human-readable purpose
 * @param repo         git clone URL
 * @param branch       the branch to track
 * @param version      Maven version string, or null if not versioned
 * @param groupId      Maven groupId
 * @param dependsOn    inter-repository dependencies
 * @param notes        free-text migration or status notes
 * @param mavenVersion Maven version for the wrapper (e.g., "4.0.0-rc-5"),
 *                     overrides {@link Defaults#mavenVersion()}. Null to inherit.
 * @param parent       subproject name of the Maven parent POM, or null if the
 *                     parent is not a workspace subproject. Used by ws:verify
 *                     and ws:align-publish to enforce parent version alignment.
 * @param sha          git commit SHA to check out. When present, {@code ws:init}
 *                     checks out this exact commit instead of branch HEAD.
 *                     Written by {@code ws:checkpoint-publish}. Null means use
 *                     branch HEAD.
 * @param state        alignment state — {@code "snapshot"} (default,
 *                     in release cascade) or {@code "tag-aligned"} (frozen
 *                     at a tag, not in release cascade). Schema 1.1+ field
 *                     (ike-issues#233); legacy manifests default to
 *                     {@code "snapshot"} on read.
 * @param tag          git tag this subproject is pinned to when
 *                     {@code state="tag-aligned"}; null when
 *                     {@code state="snapshot"}.
 * @param kind         when {@code state="tag-aligned"}, distinguishes
 *                     {@code "release"} (artifact is in Nexus) from
 *                     {@code "checkpoint"} (artifact may need local
 *                     {@code mvn install}). Null when
 *                     {@code state="snapshot"}.
 */
public record Subproject(
        String name,
        String description,
        String repo,
        String branch,
        String version,
        String groupId,
        List<Dependency> dependsOn,
        String notes,
        String mavenVersion,
        String parent,
        String sha,
        String state,
        String tag,
        String kind
) {

    /** Sentinel for the snapshot-aligned state — the default alignment. */
    public static final String STATE_SNAPSHOT = "snapshot";

    /** Sentinel for the tag-aligned state. */
    public static final String STATE_TAG_ALIGNED = "tag-aligned";

    /** Sentinel for the {@code release} sub-kind of tag-aligned. */
    public static final String KIND_RELEASE = "release";

    /** Sentinel for the {@code checkpoint} sub-kind of tag-aligned. */
    public static final String KIND_CHECKPOINT = "checkpoint";

    /**
     * Whether this subproject participates in the workspace's release
     * cascade — {@code true} for snapshot-aligned (the default),
     * {@code false} for tag-aligned. Convenience over comparing
     * {@link #state()} to a sentinel.
     *
     * @return true if {@link #state()} is {@code "snapshot"} or null
     *         (legacy manifest default)
     */
    public boolean isSnapshotAligned() {
        return state == null || STATE_SNAPSHOT.equals(state);
    }

    /**
     * Whether this subproject is pinned to a tag and excluded from the
     * release cascade.
     *
     * @return true if {@link #state()} equals {@code "tag-aligned"}
     */
    public boolean isTagAligned() {
        return STATE_TAG_ALIGNED.equals(state);
    }
}
