package network.ike.plugin.scaffold;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of {@link ModelAdapter} instances keyed by model name.
 *
 * <p>Contains one adapter per known model
 * ({@code maven-settings-4}, {@code pom-openrewrite},
 * {@code git-config}). The scaffold planner consults this registry
 * when it encounters a {@link ScaffoldTier#MODEL_MANAGED} entry.
 */
public final class ModelAdapters {

    private final Map<String, ModelAdapter> byName;

    /** Create a registry populated with the built-in adapters. */
    public ModelAdapters() {
        this(new MavenSettingsAdapter(),
                new GitConfigAdapter(),
                new PomModelAdapter());
    }

    /**
     * Create a registry from an explicit set of adapters. Intended
     * for tests that swap in a stub.
     *
     * @param adapters one adapter per model name; duplicate names
     *                 cause an {@link IllegalArgumentException}
     */
    public ModelAdapters(ModelAdapter... adapters) {
        Map<String, ModelAdapter> map = new LinkedHashMap<>();
        for (ModelAdapter a : adapters) {
            if (map.putIfAbsent(a.modelName(), a) != null) {
                throw new IllegalArgumentException(
                        "duplicate adapter for model '"
                                + a.modelName() + "'");
            }
        }
        this.byName = map;
    }

    /**
     * Look up the adapter for a model name.
     *
     * @param modelName the model name from
     *                  {@link ManifestEntry#model()}
     * @return the adapter, or {@code null} if none is registered
     */
    public ModelAdapter get(String modelName) {
        return byName.get(modelName);
    }

    /**
     * Look up the adapter for a model name, throwing if none is
     * registered.
     *
     * @param modelName the model name
     * @return the adapter; never {@code null}
     * @throws ScaffoldException if no adapter is registered for
     *                           {@code modelName}
     */
    public ModelAdapter require(String modelName) {
        ModelAdapter a = byName.get(modelName);
        if (a == null) {
            throw new ScaffoldException(
                    "No ModelAdapter registered for model '"
                            + modelName + "'. Known models: "
                            + byName.keySet());
        }
        return a;
    }
}
