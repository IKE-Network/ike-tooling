package network.ike.tooling.buildreport;

import java.util.Objects;

/**
 * One file or tree whose size the ledger asks the session to measure,
 * for things that are not Maven artifacts: a jlinked image, an
 * installer, a store a test built.
 *
 * @param key  the measure key the size is recorded under
 * @param path a glob relative to the execution root; every match is
 *             summed, a matching directory as its whole tree
 */
public record SizeEntry(String key, String path) {

    /**
     * Validates the entry.
     *
     * @param key  the measure key the size is recorded under
     * @param path a glob relative to the execution root
     */
    public SizeEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a size entry needs a non-blank key");
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("size entry '" + key + "' needs a non-blank path");
        }
    }
}
