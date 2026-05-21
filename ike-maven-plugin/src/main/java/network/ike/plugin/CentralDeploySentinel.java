package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Status record for an asynchronous Maven Central deploy
 * (IKE-Network/ike-issues#484). Persisted as a Java {@code
 * .properties} file under a discoverable cache directory so the
 * outcome survives the originating Maven JVM exiting.
 *
 * <p>State machine (single forward transition): {@link State#PENDING}
 * is written by the release Mojo when it spawns the detached
 * subprocess; the subprocess itself rewrites the file to
 * {@link State#SUCCESS} or {@link State#FAILURE} when it finishes.
 *
 * <p>Default location: {@code ~/.cache/ike-release/}. Chosen over
 * {@code target/} so the sentinel survives a {@code mvn clean} and
 * is discoverable across repos by {@link CentralStatusMojo}, which
 * walks the directory to report in-flight deploys workspace-wide.
 */
public final class CentralDeploySentinel {

    /** Sentinel lifecycle states. */
    public enum State {
        /** Subprocess spawned, still running. */
        PENDING,
        /** Central deploy succeeded — bundle published. */
        SUCCESS,
        /** All retry attempts exhausted; see {@link #lastError()}. */
        FAILURE
    }

    /** Default sentinel directory: {@code ~/.cache/ike-release/}. */
    public static final Path DEFAULT_DIR = Paths.get(
            System.getProperty("user.home"), ".cache", "ike-release");

    // ── Property keys ───────────────────────────────────────────
    static final String KEY_STATE = "state";
    static final String KEY_ARTIFACT_ID = "artifactId";
    static final String KEY_VERSION = "version";
    static final String KEY_STARTED = "started";
    static final String KEY_FINISHED = "finished";
    static final String KEY_ATTEMPTS = "attempts";
    static final String KEY_MAX_ATTEMPTS = "maxAttempts";
    static final String KEY_LAST_ERROR = "lastError";
    static final String KEY_LOG_FILE = "logFile";
    static final String KEY_PID = "pid";

    private final State state;
    private final String artifactId;
    private final String version;
    private final Instant started;
    private final Instant finished;
    private final int attempts;
    private final int maxAttempts;
    private final String lastError;
    private final Path logFile;
    private final long pid;
    private final Path path;

    private CentralDeploySentinel(Builder b) {
        this.state = b.state;
        this.artifactId = b.artifactId;
        this.version = b.version;
        this.started = b.started;
        this.finished = b.finished;
        this.attempts = b.attempts;
        this.maxAttempts = b.maxAttempts;
        this.lastError = b.lastError;
        this.logFile = b.logFile;
        this.pid = b.pid;
        this.path = b.path;
    }

    /**
     * Lifecycle state of this sentinel.
     *
     * @return the state
     */
    public State state() { return state; }

    /**
     * Project artifactId the deploy belongs to.
     *
     * @return the artifactId
     */
    public String artifactId() { return artifactId; }

    /**
     * Release version being deployed.
     *
     * @return the version
     */
    public String version() { return version; }

    /**
     * When the deploy was spawned (UTC).
     *
     * @return the start instant
     */
    public Instant started() { return started; }

    /**
     * When the deploy reached a terminal state, or {@code null}
     * while still {@link State#PENDING}.
     *
     * @return the finish instant, or {@code null} when pending
     */
    public Instant finished() { return finished; }

    /**
     * Number of attempts taken — refreshed by the subprocess
     * before each upload so {@code ike:central-status} reflects
     * live progress.
     *
     * @return attempts taken so far
     */
    public int attempts() { return attempts; }

    /**
     * Configured maximum attempts for the retry loop, recorded
     * so the displayed {@code attempts/max} ratio is meaningful
     * even after the configuration changes between releases.
     *
     * @return the configured maximum
     */
    public int maxAttempts() { return maxAttempts; }

    /**
     * Short failure summary captured by the subprocess when the
     * retry budget is exhausted.
     *
     * @return failure summary, or {@code null} when not failed
     */
    public String lastError() { return lastError; }

    /**
     * Path the subprocess streams its deploy log to.
     *
     * @return the log-file path, or {@code null} if not recorded
     */
    public Path logFile() { return logFile; }

    /**
     * PID of the spawned subprocess. Written while {@link State#PENDING}
     * so {@code ike:central-status} can detect orphaned sentinels
     * (subprocess died, sentinel never advanced).
     *
     * @return the PID, or 0 if not recorded
     */
    public long pid() { return pid; }

    /**
     * Absolute path to this sentinel file on disk.
     *
     * @return the sentinel file path
     */
    public Path path() { return path; }

    /**
     * Resolve the canonical sentinel-file path for a project.
     *
     * @param dir        sentinel directory (typically {@link #DEFAULT_DIR})
     * @param artifactId project artifactId
     * @param version    release version
     * @return {@code <dir>/<artifactId>-<version>.properties}
     */
    public static Path resolvePath(Path dir, String artifactId,
                                    String version) {
        return dir.resolve(artifactId + "-" + version + ".properties");
    }

    /**
     * Read a sentinel file into a value object.
     *
     * @param path the sentinel file
     * @return the parsed sentinel
     * @throws MojoException if the file is missing or malformed
     */
    public static CentralDeploySentinel read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new MojoException("Sentinel not found: " + path);
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            throw new MojoException("Could not read sentinel "
                    + path + ": " + e.getMessage(), e);
        }
        return new Builder()
                .path(path)
                .state(parseState(p, path))
                .artifactId(required(p, KEY_ARTIFACT_ID, path))
                .version(required(p, KEY_VERSION, path))
                .started(parseInstant(p, KEY_STARTED, true, path))
                .finished(parseInstant(p, KEY_FINISHED, false, path))
                .attempts(parseInt(p, KEY_ATTEMPTS, 0))
                .maxAttempts(parseInt(p, KEY_MAX_ATTEMPTS, 0))
                .lastError(p.getProperty(KEY_LAST_ERROR))
                .logFile(parseOptionalPath(p, KEY_LOG_FILE))
                .pid(parseLong(p, KEY_PID, 0L))
                .build();
    }

    /**
     * List all sentinel files under a directory, newest first. Files
     * that fail to parse are skipped — listing must be robust against
     * partially-written files an in-flight subprocess is updating.
     *
     * @param dir the sentinel directory (may not exist; treated as empty)
     * @return parsed sentinels, ordered by {@code started} descending
     */
    public static List<CentralDeploySentinel> listAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CentralDeploySentinel> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString()
                            .endsWith(".properties"))
                    .forEach(p -> {
                        try {
                            out.add(read(p));
                        } catch (RuntimeException ignored) {
                            // Skip partially-written / malformed entries.
                        }
                    });
        } catch (IOException e) {
            throw new MojoException("Could not list sentinel dir "
                    + dir + ": " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(CentralDeploySentinel::started)
                .reversed());
        return out;
    }

    /**
     * Write this sentinel to {@link #path()}. Atomic replace via
     * a temp-file + rename so concurrent readers never see a
     * half-written file.
     *
     * @throws MojoException on I/O failure
     */
    public void write() {
        Properties p = new Properties();
        p.setProperty(KEY_STATE, state.name());
        p.setProperty(KEY_ARTIFACT_ID, artifactId);
        p.setProperty(KEY_VERSION, version);
        p.setProperty(KEY_STARTED, started.toString());
        if (finished != null) {
            p.setProperty(KEY_FINISHED, finished.toString());
        }
        p.setProperty(KEY_ATTEMPTS, Integer.toString(attempts));
        p.setProperty(KEY_MAX_ATTEMPTS, Integer.toString(maxAttempts));
        if (lastError != null) {
            p.setProperty(KEY_LAST_ERROR, lastError);
        }
        if (logFile != null) {
            p.setProperty(KEY_LOG_FILE, logFile.toString());
        }
        if (pid != 0L) {
            p.setProperty(KEY_PID, Long.toString(pid));
        }
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(
                    path.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                p.store(out,
                        "ike:release-publish Maven Central deploy "
                                + "sentinel (IKE-Network/ike-issues#484)");
            }
            Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new MojoException("Could not write sentinel "
                    + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Start a new, empty builder.
     *
     * @return a fresh builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Start a builder pre-seeded with this sentinel's fields —
     * the typical entry point for the subprocess transitioning
     * {@link State#PENDING} to {@link State#SUCCESS} or
     * {@link State#FAILURE} without restating immutable fields.
     *
     * @return a builder seeded from this sentinel
     */
    public Builder toBuilder() {
        return new Builder()
                .state(state)
                .artifactId(artifactId)
                .version(version)
                .started(started)
                .finished(finished)
                .attempts(attempts)
                .maxAttempts(maxAttempts)
                .lastError(lastError)
                .logFile(logFile)
                .pid(pid)
                .path(path);
    }

    /**
     * Fluent builder for {@link CentralDeploySentinel}. Required
     * fields ({@code state, artifactId, version, started, path})
     * are validated by {@link #build()}; the rest are optional
     * and default to their type's zero / {@code null}.
     */
    public static final class Builder {
        private State state;
        private String artifactId;
        private String version;
        private Instant started;
        private Instant finished;
        private int attempts;
        private int maxAttempts;
        private String lastError;
        private Path logFile;
        private long pid;
        private Path path;

        /** Creates an empty builder; use {@link #builder()}. */
        Builder() {}

        /**
         * Set the lifecycle state.
         *
         * @param v lifecycle state (must not be {@code null})
         * @return this builder for chaining
         */
        public Builder state(State v) { this.state = v; return this; }

        /**
         * Set the project artifactId.
         *
         * @param v project artifactId
         * @return this builder for chaining
         */
        public Builder artifactId(String v) { this.artifactId = v; return this; }

        /**
         * Set the release version.
         *
         * @param v release version
         * @return this builder for chaining
         */
        public Builder version(String v) { this.version = v; return this; }

        /**
         * Set the start instant.
         *
         * @param v start instant (UTC)
         * @return this builder for chaining
         */
        public Builder started(Instant v) { this.started = v; return this; }

        /**
         * Set the finish instant; pass {@code null} for a
         * sentinel still in {@link State#PENDING}.
         *
         * @param v finish instant, or {@code null} if pending
         * @return this builder for chaining
         */
        public Builder finished(Instant v) { this.finished = v; return this; }

        /**
         * Set the number of attempts taken.
         *
         * @param v attempts taken so far
         * @return this builder for chaining
         */
        public Builder attempts(int v) { this.attempts = v; return this; }

        /**
         * Set the configured maximum attempts.
         *
         * @param v configured max attempts
         * @return this builder for chaining
         */
        public Builder maxAttempts(int v) { this.maxAttempts = v; return this; }

        /**
         * Set the failure summary; pass {@code null} when not failed.
         *
         * @param v failure summary, or {@code null}
         * @return this builder for chaining
         */
        public Builder lastError(String v) { this.lastError = v; return this; }

        /**
         * Set the deploy-log file path.
         *
         * @param v deploy log file path
         * @return this builder for chaining
         */
        public Builder logFile(Path v) { this.logFile = v; return this; }

        /**
         * Set the subprocess PID.
         *
         * @param v subprocess PID, or 0 if not recorded
         * @return this builder for chaining
         */
        public Builder pid(long v) { this.pid = v; return this; }

        /**
         * Set the sentinel-file path on disk. Required.
         *
         * @param v sentinel file path
         * @return this builder for chaining
         */
        public Builder path(Path v) { this.path = v; return this; }

        /**
         * Validate required fields and build the immutable
         * sentinel value.
         *
         * @return the built immutable sentinel
         * @throws IllegalStateException if any required field
         *         ({@code state, artifactId, version, started,
         *         path}) is unset
         */
        public CentralDeploySentinel build() {
            if (state == null || artifactId == null || version == null
                    || started == null || path == null) {
                throw new IllegalStateException(
                        "Required fields missing — state, artifactId, "
                                + "version, started, and path must be set");
            }
            return new CentralDeploySentinel(this);
        }
    }

    // ── Parse helpers ───────────────────────────────────────────

    private static State parseState(Properties p, Path path) {
        String raw = required(p, KEY_STATE, path);
        try {
            return State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new MojoException("Invalid sentinel state '"
                    + raw + "' in " + path
                    + " (expected PENDING/SUCCESS/FAILURE)", e);
        }
    }

    private static String required(Properties p, String key, Path path) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new MojoException("Sentinel " + path
                    + " missing required property '" + key + "'");
        }
        return value;
    }

    private static Instant parseInstant(Properties p, String key,
                                         boolean required, Path path) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new MojoException("Sentinel " + path
                        + " missing required property '" + key + "'");
            }
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new MojoException("Invalid instant '" + raw
                    + "' for property '" + key + "' in " + path, e);
        }
    }

    private static int parseInt(Properties p, String key, int dflt) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long parseLong(Properties p, String key, long dflt) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Path parseOptionalPath(Properties p, String key) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Paths.get(raw);
    }
}
