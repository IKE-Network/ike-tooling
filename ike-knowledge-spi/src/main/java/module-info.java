/**
 * The contract layer of the IKE knowledge pipeline: ServiceLoader service
 * interfaces for the {@code ike:} knowledge goals, the ViewSpec dimension
 * keys, and the properties codec that crosses the forked-JVM seam.
 * Zero dependencies — the IKE foundation never depends on the chronology
 * store; implementations arrive as ordinary dependencies at the use site.
 */
module network.ike.knowledge.spi {
    exports network.ike.knowledge.spi;

    uses network.ike.knowledge.spi.KnowledgeBaseAssembler;
    uses network.ike.knowledge.spi.KnowledgeExporter;
    uses network.ike.knowledge.spi.BindingsGenerator;
    uses network.ike.knowledge.spi.KnowledgeVerifier;
}
