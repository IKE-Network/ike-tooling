package network.ike.plugin;

/**
 * The SCM block for the generated BOM POM — Maven Central requires it
 * (IKE-Network/ike-issues#967).
 *
 * <p>Decouples BOM XML generation from Maven's {@code Scm} model so
 * the generation logic is testable with plain records.
 *
 * @param connection          read SCM connection URI; null omits the element
 * @param developerConnection write SCM connection URI; null omits the element
 * @param url                 browsable repository URL; null omits the element
 * @param tag                 tag within the repository; null omits the element
 */
public record BomScm(String connection, String developerConnection,
                     String url, String tag) {}
