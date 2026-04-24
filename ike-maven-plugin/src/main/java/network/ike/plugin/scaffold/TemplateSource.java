package network.ike.plugin.scaffold;

/**
 * Source of scaffold template bytes, addressed by the string in
 * {@link ManifestEntry#source()}.
 *
 * <p>In production this is backed by the {@code ike-build-standards}
 * scaffold zip unpacked at plugin {@code validate} time. Tests use an
 * in-memory map implementation.
 */
public interface TemplateSource {

    /**
     * Read template bytes for a given source path.
     *
     * @param source the path declared in the manifest's
     *               {@code source} field
     * @return the template bytes
     * @throws ScaffoldException if {@code source} does not exist in
     *                           this template source
     */
    byte[] read(String source);
}
