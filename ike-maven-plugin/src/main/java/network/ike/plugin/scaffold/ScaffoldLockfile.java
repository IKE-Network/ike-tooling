package network.ike.plugin.scaffold;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory representation of a scaffold lockfile.
 *
 * <p>Two lockfile instances exist per scaffold run:
 *
 * <ul>
 *   <li>{@code {project.root}/.ike/scaffold.lock} — committed to git,
 *       tracks what the scaffold has installed in a project.</li>
 *   <li>{@code {user.home}/.ike/scaffold.lock} — local to a machine,
 *       tracks what the scaffold has installed in the user-home tier
 *       ({@code ~/.git-hooks/}, {@code ~/.m2/settings.xml}, etc.).</li>
 * </ul>
 *
 * <p>The on-disk format is YAML; see
 * {@code ScaffoldLockfileIo} for parse/emit.
 *
 * <p>This class records only current scaffold-owned state; it never
 * stores user secrets or free-form user content.
 *
 * @param schema           lockfile schema version (currently
 *                         {@link #CURRENT_SCHEMA}); future schema
 *                         bumps will be accompanied by an in-place
 *                         migrator
 * @param standardsVersion {@code ike-build-standards} version that
 *                         produced the last applied state; may be
 *                         {@code null} for a newly created lockfile
 *                         that has not yet had a successful publish
 * @param applied          UTC timestamp of the last successful
 *                         publish; may be {@code null} (never
 *                         published)
 * @param files            per-file entries, keyed by scaffold path.
 *                         Paths are normalised to forward slashes and
 *                         may use the {@code "~/"} prefix for
 *                         user-home scope. Insertion order is
 *                         preserved. The stored map is unmodifiable.
 */
public record ScaffoldLockfile(
        int schema,
        String standardsVersion,
        Instant applied,
        Map<String, LockfileEntry> files) {

    /**
     * The current on-disk schema version. Bumps here must be paired
     * with a migration in {@code ScaffoldLockfileIo}.
     */
    public static final int CURRENT_SCHEMA = 1;

    /**
     * Canonical constructor with validation and defensive copying of
     * the file map.
     */
    public ScaffoldLockfile {
        if (schema <= 0) {
            throw new IllegalArgumentException(
                    "schema must be positive");
        }
        files = files == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(files));
        for (Map.Entry<String, LockfileEntry> e : files.entrySet()) {
            Objects.requireNonNull(e.getKey(),
                    "lockfile entry key must not be null");
            Objects.requireNonNull(e.getValue(),
                    "lockfile entry value must not be null");
            if (e.getKey().isBlank()) {
                throw new IllegalArgumentException(
                        "lockfile entry key must not be blank");
            }
        }
    }

    /**
     * Create an empty lockfile at the current schema with no entries
     * and no applied state. Useful for initialising a new scaffold
     * target.
     *
     * @return a blank {@code ScaffoldLockfile} at
     *         {@link #CURRENT_SCHEMA}
     */
    public static ScaffoldLockfile empty() {
        return new ScaffoldLockfile(
                CURRENT_SCHEMA,
                null,
                null,
                Collections.emptyMap());
    }

    /**
     * Return a copy of this lockfile with one entry added or replaced.
     *
     * @param path  scaffold path key
     * @param entry entry to store
     * @return a new lockfile with the entry in place (insertion order
     *         preserved: new keys append; existing keys keep their
     *         position)
     */
    public ScaffoldLockfile withEntry(String path, LockfileEntry entry) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(entry, "entry");
        LinkedHashMap<String, LockfileEntry> next =
                new LinkedHashMap<>(files);
        next.put(path, entry);
        return new ScaffoldLockfile(
                schema, standardsVersion, applied, next);
    }

    /**
     * Return a copy of this lockfile with one entry removed.
     *
     * @param path scaffold path key to drop; missing keys are ignored
     * @return a new lockfile without that entry; the same instance
     *         if the entry was already absent
     */
    public ScaffoldLockfile withoutEntry(String path) {
        Objects.requireNonNull(path, "path");
        if (!files.containsKey(path)) {
            return this;
        }
        LinkedHashMap<String, LockfileEntry> next =
                new LinkedHashMap<>(files);
        next.remove(path);
        return new ScaffoldLockfile(
                schema, standardsVersion, applied, next);
    }

    /**
     * Return a copy with the top-level {@code standardsVersion} and
     * {@code applied} stamps updated (used when publish completes).
     *
     * @param standardsVersion the ike-build-standards version just
     *                         applied; must not be {@code null}
     * @param applied          timestamp of the publish; must not be
     *                         {@code null}
     * @return a new lockfile with updated stamps
     */
    public ScaffoldLockfile withAppliedStamp(
            String standardsVersion, Instant applied) {
        Objects.requireNonNull(standardsVersion, "standardsVersion");
        Objects.requireNonNull(applied, "applied");
        return new ScaffoldLockfile(
                schema, standardsVersion, applied, files);
    }
}
