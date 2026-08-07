package network.ike.plugin;

/**
 * A license entry for the generated BOM POM — Maven Central requires
 * at least one {@code <license>} (IKE-Network/ike-issues#967).
 *
 * <p>Decouples BOM XML generation from Maven's {@code License} model
 * so the generation logic is testable with plain records.
 *
 * @param name license display name (e.g., "Apache License, Version 2.0");
 *             null omits the element
 * @param url  license text URL; null omits the element
 */
public record BomLicense(String name, String url) {}
