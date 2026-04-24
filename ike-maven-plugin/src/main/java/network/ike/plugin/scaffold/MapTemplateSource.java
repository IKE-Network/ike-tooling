package network.ike.plugin.scaffold;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * In-memory {@link TemplateSource} backed by a {@code Map<String,
 * byte[]>}. Intended for tests and callers that synthesize templates
 * programmatically.
 */
public final class MapTemplateSource implements TemplateSource {

    private final Map<String, byte[]> templates;

    /**
     * Construct a source backed by the given map. Lookups return a
     * defensive copy of the stored bytes, but the map itself is kept
     * by reference for efficiency.
     *
     * @param templates map from source path to template bytes; the
     *                  map is kept by reference, so callers must not
     *                  mutate it after construction
     */
    public MapTemplateSource(Map<String, byte[]> templates) {
        this.templates = templates;
    }

    /**
     * Convenience factory for a string-valued map.
     *
     * @param templates map from source path to template text (UTF-8)
     * @return a {@link MapTemplateSource}
     */
    public static MapTemplateSource ofStrings(
            Map<String, String> templates) {
        java.util.LinkedHashMap<String, byte[]> m =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : templates.entrySet()) {
            m.put(e.getKey(),
                    e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return new MapTemplateSource(m);
    }

    @Override
    public byte[] read(String source) {
        byte[] v = templates.get(source);
        if (v == null) {
            throw new ScaffoldException(
                    "template not found in map source: " + source);
        }
        return v.clone();
    }
}
