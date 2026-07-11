package network.ike.knowledge.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The generic forked-JVM entry point: resolves a knowledge service from the fork's
 * classpath, materializes the typed request from a properties file, executes, and writes
 * the typed result as a properties file. One bootstrap serves every service — the
 * {@link KnowledgeService} codec bridge keeps it free of per-service knowledge.
 *
 * <p>Arguments: {@code <serviceInterface> <requestFile> <resultFile>}. The request file
 * may carry the reserved top-level key {@code implementation} naming the implementation's
 * simple class name when the classpath provides several.
 *
 * <p>Failure is signalled by throwing — the JVM exits non-zero and the invoking goal
 * fails the build. Nothing here (and nothing in a well-behaved implementation) calls
 * {@code System.exit}.
 *
 * <p>Resolution uses the context class loader — the {@code java -cp} fork this
 * bootstrap is designed for. A module-path fork would additionally need the service
 * bindings visible to the boot layer.
 */
public final class IkeServiceBootstrap {

    /**
     * The reserved top-level request key naming the implementation's simple class name
     * when the classpath provides several. Goal-side writers use this constant; no
     * request codec may use the key.
     */
    public static final String IMPLEMENTATION_KEY = "implementation";

    private IkeServiceBootstrap() {
    }

    /**
     * Runs one service invocation.
     *
     * @param args {@code <serviceInterface> <requestFile> <resultFile>}
     * @throws Exception if the arguments are invalid, the service or implementation
     *                   cannot be resolved, the request is malformed, execution fails,
     *                   or a file cannot be read or written
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: IkeServiceBootstrap <serviceInterface> <requestFile> <resultFile>");
        }
        Class<?> serviceInterface = Class.forName(args[0], true,
                Thread.currentThread().getContextClassLoader());
        if (!KnowledgeService.class.isAssignableFrom(serviceInterface)) {
            throw new IllegalArgumentException(serviceInterface.getName()
                    + " is not a " + KnowledgeService.class.getName());
        }
        Properties request = load(Path.of(args[1]));
        Optional<String> implementationName = PropCodec.optional(request, IMPLEMENTATION_KEY);

        Properties result = invoke(serviceInterface, implementationName, request);

        Path resultFile = Path.of(args[2]);
        if (resultFile.toAbsolutePath().getParent() != null) {
            Files.createDirectories(resultFile.toAbsolutePath().getParent());
        }
        try (OutputStream out = Files.newOutputStream(resultFile)) {
            result.store(out, serviceInterface.getSimpleName() + " result");
        }
    }

    /**
     * Resolves and invokes the service — separated from {@link #main(String[])} so the
     * mechanics are exercisable in-process.
     *
     * @param serviceInterface   the service interface to resolve
     * @param implementationName the implementation selection, if any
     * @param request            the request properties
     * @return the result properties
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Properties invoke(Class<?> serviceInterface, Optional<String> implementationName,
                             Properties request) {
        KnowledgeService service = (KnowledgeService)
                KnowledgeServices.select(serviceInterface, implementationName);
        Object typedRequest = service.requestFromProperties(request);
        Object typedResult = service.execute(typedRequest);
        return service.resultToProperties(typedResult);
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}
