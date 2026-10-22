package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where one reactor module keeps its files, captured when the session
 * starts so the measures can be read from the right directories at
 * session end.
 *
 * @param name            the module's artifact id
 * @param basedir         the directory of its POM
 * @param buildDirectory  its build directory, normally {@code target}
 * @param outputDirectory where its compiled classes go, normally
 *                        {@code target/classes}
 */
public record ModuleLocation(String name, Path basedir, Path buildDirectory, Path outputDirectory) {

    /**
     * Validates the location.
     *
     * @param name            the module's artifact id
     * @param basedir         the directory of its POM
     * @param buildDirectory  its build directory
     * @param outputDirectory where its compiled classes go
     */
    public ModuleLocation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(basedir, "basedir");
        Objects.requireNonNull(buildDirectory, "buildDirectory");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
    }

    /**
     * Creates a location with Maven's default layout under the basedir.
     *
     * @param name    the module's artifact id
     * @param basedir the directory of its POM
     * @return the location with {@code target} and {@code target/classes}
     */
    public static ModuleLocation standard(String name, Path basedir) {
        Path build = basedir.resolve("target");
        return new ModuleLocation(name, basedir, build, build.resolve("classes"));
    }
}
