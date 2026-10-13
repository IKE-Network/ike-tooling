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
 * @param modules   for a merge, the modules (by UUID) whose versions are kept; empty keeps all
 */
public record ChangeSetRequest(Path changeSet, Optional<Path> target, java.util.Set<String> modules) {

    /** Checks that neither part is null. */
    public ChangeSetRequest {
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(target, "target");
        modules = java.util.Set.copyOf(Objects.requireNonNull(modules, "modules"));
    }

    /**
     * A request with no module selection.
     *
     * @param changeSet the change set zip to read
     * @param target    the file a rewrite writes; empty for a report
     */
    public ChangeSetRequest(Path changeSet, Optional<Path> target) {
        this(changeSet, target, java.util.Set.of());
    }

    /**
     * A request to read a change set and report.
     *
     * @param changeSet the change set zip to read
     * @return the request, with no target
     */
    public static ChangeSetRequest of(Path changeSet) {
        return new ChangeSetRequest(changeSet, Optional.empty());
    }

    /**
     * A request to rewrite a change set as another file.
     *
     * @param changeSet the change set zip to read
     * @param target    the file to write
     * @return the request
     */
    public static ChangeSetRequest of(Path changeSet, Path target) {
        return new ChangeSetRequest(changeSet, Optional.of(target));
    }

    /**
     * The target, for a tool that writes one.
     *
     * @param tool the goal's name, for the message when there is no target
     * @return the target
     * @throws IllegalArgumentException if the request names no target
     */
    public Path requireTarget(String tool) {
        return target.orElseThrow(() -> new IllegalArgumentException(tool + " writes a file: the request names no target"));
    }

    /**
     * The request as properties, for the forked-JVM seam.
     *
     * @return the properties: {@code changeSet}, and {@code target} when there is one
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "changeSet", changeSet.toString());
        PropCodec.putOptional(properties, "target", target.map(Path::toString));
        if (!modules.isEmpty()) {
            PropCodec.put(properties, "modules", String.join(",", new java.util.TreeSet<>(modules)));
        }
        return properties;
    }

    /**
     * The request read back from its properties.
     *
     * @param properties what {@link #toProperties()} wrote
     * @return the request
     */
    public static ChangeSetRequest fromProperties(Properties properties) {
        return new ChangeSetRequest(PropCodec.requirePath(properties, "changeSet"),
                PropCodec.optionalPath(properties, "target"),
                PropCodec.optional(properties, "modules")
                        .map(list -> java.util.Set.of(list.split(","))).orElse(java.util.Set.of()));
    }
}
