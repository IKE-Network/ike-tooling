package network.ike.plugin;

/**
 * A developer entry for the generated BOM POM — Maven Central requires
 * at least one {@code <developer>} (IKE-Network/ike-issues#967).
 *
 * <p>Decouples BOM XML generation from Maven's {@code Developer} model
 * so the generation logic is testable with plain records.
 *
 * @param id    developer id; null omits the element
 * @param name  developer display name; null omits the element
 * @param email developer contact email; null omits the element
 */
public record BomDeveloper(String id, String name, String email) {}
