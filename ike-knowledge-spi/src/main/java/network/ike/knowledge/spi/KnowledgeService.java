package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Base contract of every knowledge-pipeline service: a typed execution method plus the
 * properties-codec bridge that lets {@link IkeServiceBootstrap} drive any service
 * generically across the forked-JVM seam. The wire format is a flat properties file —
 * string-typing is confined to serialization; the contract is the typed interfaces.
 *
 * <p>Concrete service interfaces ({@link KnowledgeBaseAssembler}, {@link KnowledgeExporter},
 * {@link BindingsGenerator}, {@link KnowledgeVerifier}) supply default implementations of
 * the codec methods delegating to their request/result records, and a default
 * {@link #execute(Object)} delegating to their named method — an implementation overrides
 * only that named method.
 *
 * @param <Q> the request record type
 * @param <R> the result record type
 */
public interface KnowledgeService<Q, R> {

    /**
     * Executes this service.
     *
     * @param request the typed request
     * @return the typed result
     */
    R execute(Q request);

    /**
     * Decodes a request from its properties form — the forked seam's wire format.
     *
     * @param properties the request properties
     * @return the typed request
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    Q requestFromProperties(Properties properties);

    /**
     * Encodes a result to its properties form — the forked seam's wire format.
     *
     * @param result the typed result
     * @return the result properties
     */
    Properties resultToProperties(R result);
}
