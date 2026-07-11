/**
 * The IKE knowledge-pipeline contract: goal-shaped service interfaces resolved via
 * {@link java.util.ServiceLoader}, and the value records that cross the forked-JVM
 * execution seam as properties files.
 *
 * <h2>The shape</h2>
 * Each Maven goal face has one service: {@link network.ike.knowledge.spi.KnowledgeBaseAssembler}
 * ({@code ike:kb-assemble}), {@link network.ike.knowledge.spi.KnowledgeExporter}
 * ({@code ike:knowledge-export}), {@link network.ike.knowledge.spi.BindingsGenerator}
 * ({@code ike:knowledge-bindings}), and {@link network.ike.knowledge.spi.KnowledgeVerifier}
 * ({@code ike:kb-verify}). All extend {@link network.ike.knowledge.spi.KnowledgeService},
 * whose properties-codec bridge keeps {@link network.ike.knowledge.spi.IkeServiceBootstrap}
 * — the generic forked-JVM entry point — free of any per-service knowledge.
 *
 * <h2>Implementation selection</h2>
 * The implementation is an ordinary dependency declared at the use site (or defaulted by
 * the parent POM) and resolved from the runtime classpath with exactly-one-or-named
 * semantics ({@link network.ike.knowledge.spi.KnowledgeServices}). This module never
 * names an engine: it is the stable contract over a substrate whose own names may
 * migrate.
 *
 * <h2>Evolution discipline</h2>
 * The SPI is goal-shaped — interfaces exist only for goals that exist — and evolves
 * additively (default methods). Implementation selection is a dependency, never a
 * {@link network.ike.knowledge.spi.ViewSpec} dimension.
 *
 * @see <a href="https://github.com/IKE-Network/ike-issues/issues/850">IKE-Network/ike-issues#850</a>
 * @see <a href="https://github.com/IKE-Network/ike-issues/issues/856">IKE-Network/ike-issues#856</a>
 */
package network.ike.knowledge.spi;
