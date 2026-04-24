package network.ike.plugin.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Template source backed by an unpacked directory.
 *
 * <p>In production the {@code ike-build-standards} scaffold is
 * unpacked to a directory during plugin {@code validate}; this class
 * resolves {@code source} paths against that directory.
 */
public final class DirectoryTemplateSource implements TemplateSource {

    private final Path root;

    /**
     * @param root directory containing the scaffold templates
     */
    public DirectoryTemplateSource(Path root) {
        this.root = root;
    }

    @Override
    public byte[] read(String source) {
        Path p = root.resolve(source).normalize();
        if (!p.startsWith(root)) {
            throw new ScaffoldException(
                    "template source '" + source
                            + "' escapes scaffold root");
        }
        if (!Files.isRegularFile(p)) {
            throw new ScaffoldException(
                    "template not found: " + source
                            + " (under " + root + ")");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "cannot read template " + source, e);
        }
    }
}
