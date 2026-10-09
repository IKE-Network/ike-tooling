package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * A change set tool's request: the change set, and for a rewrite the file to write. The
 * inspector and the verifier read {@code changeSet} alone; the expander and the compactor
 * write {@code target}, and refuse a request without one.
 *
 * @param changeSet the change set zip to read
 * @param target    the file a rewrite writes; empty for a report
 */
public record ChangeSetRequest(Path changeSet, Optional<Path> target) {

    public ChangeSetRequest {
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(target, "target");
    }

    /** A request to read {@code changeSet} and report. */
    public static ChangeSetRequest of(Path changeSet) {
        return new ChangeSetRequest(changeSet, Optional.empty());
    }

    /** A request to rewrite {@code changeSet} as {@code target}. */
    public static ChangeSetRequest of(Path changeSet, Path target) {
        return new ChangeSetRequest(changeSet, Optional.of(target));
    }

    /** The target, for a tool that writes one. */
    public Path requireTarget(String tool) {
        return target.orElseThrow(() -> new IllegalArgumentException(tool + " writes a file: the request names no target"));
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "changeSet", changeSet.toString());
        PropCodec.putOptional(properties, "target", target.map(Path::toString));
        return properties;
    }

    public static ChangeSetRequest fromProperties(Properties properties) {
        return new ChangeSetRequest(PropCodec.requirePath(properties, "changeSet"),
                PropCodec.optionalPath(properties, "target"));
    }
}
