package network.ike.plugin;

import network.ike.knowledge.spi.IkeServiceBootstrap;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Runs a knowledge-pipeline service across the execution seam: writes the request
 * properties file, launches {@code IkeServiceBootstrap} over the <em>project's</em>
 * runtime classpath, and reads back the result properties. The plugin stays free of any
 * store engine — the implementation arrives as a use-site (or parent-default) dependency
 * and is resolved by ServiceLoader inside the seam.
 *
 * <p>Forked by default: the engine's store lifecycle is JVM-global (a second start in
 * one JVM fails), reasoner runs are heap-heavy, and a fork survives anything the
 * implementation does short of misbehaving hardware. Child output streams live; any
 * non-zero exit fails the build. The in-process mode exists for debugging — same
 * classpath, same bootstrap, no process boundary.
 *
 * <p>The child inherits {@code --enable-preview} when the Maven JVM runs with it, so
 * preview-compiled engine classes load in the fork exactly as they do in the build.
 */
final class KnowledgeServiceRunner {

    private final Log log;

    /**
     * Creates a runner logging through the given mojo log.
     *
     * @param log the mojo's log
     */
    KnowledgeServiceRunner(Log log) {
        this.log = log;
    }

    /**
     * Executes one service invocation across the seam.
     *
     * @param serviceInterfaceName the SPI service interface's fully qualified name
     * @param request              the request properties (the record's wire form)
     * @param classpath            the seam classpath: the project's runtime dependency
     *                             paths plus its classes directory
     * @param workDirectory        where the request and result files are written
     * @param fork                 fork a child JVM (the default posture) or run
     *                             in-process for debugging
     * @param jvmArguments         extra child-JVM arguments (fork only)
     * @return the result properties
     * @throws MojoException if the seam cannot be launched, the child exits non-zero,
     *                       or the result cannot be read
     */
    Properties run(String serviceInterfaceName, Properties request, List<Path> classpath,
                   Path workDirectory, boolean fork, List<String> jvmArguments) {
        try {
            Files.createDirectories(workDirectory);
            String stem = serviceInterfaceName.substring(serviceInterfaceName.lastIndexOf('.') + 1);
            Path requestFile = workDirectory.resolve(stem + "-request.properties");
            Path resultFile = workDirectory.resolve(stem + "-result.properties");
            try (var out = Files.newOutputStream(requestFile)) {
                request.store(out, serviceInterfaceName + " request");
            }
            Files.deleteIfExists(resultFile);

            if (fork) {
                runForked(serviceInterfaceName, classpath, requestFile, resultFile, jvmArguments);
            } else {
                runInProcess(serviceInterfaceName, classpath, requestFile, resultFile);
            }

            Properties result = new Properties();
            try (InputStream in = Files.newInputStream(resultFile)) {
                result.load(in);
            }
            return result;
        } catch (IOException e) {
            throw new MojoException("Knowledge service seam I/O failed", e);
        }
    }

    private void runForked(String serviceInterfaceName, List<Path> classpath,
                           Path requestFile, Path resultFile, List<String> jvmArguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview")) {
            command.add("--enable-preview");
        }
        command.addAll(jvmArguments);
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator,
                classpath.stream().map(Path::toString).toList()));
        command.add(IkeServiceBootstrap.class.getName());
        command.add(serviceInterfaceName);
        command.add(requestFile.toString());
        command.add(resultFile.toString());

        try {
            Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    log.info("[seam] " + line);
                    line = reader.readLine();
                }
            }
            int exit = child.waitFor();
            if (exit != 0) {
                throw new MojoException("Knowledge service " + serviceInterfaceName
                        + " failed in the forked seam (exit " + exit + ") — see the [seam] log"
                        + " above; if the service was not found, declare the implementation"
                        + " dependency (ike-knowledge-provider) at the use site or rely on the"
                        + " parent default");
            }
        } catch (IOException e) {
            throw new MojoException("Cannot launch the knowledge service fork", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted waiting for the knowledge service fork", e);
        }
    }

    private void runInProcess(String serviceInterfaceName, List<Path> classpath,
                              Path requestFile, Path resultFile) {
        List<URL> urls = new ArrayList<>();
        try {
            for (Path path : classpath) {
                urls.add(path.toUri().toURL());
            }
        } catch (Exception e) {
            throw new MojoException("Cannot assemble the in-process seam classpath", e);
        }
        ClassLoader originalContext = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader("ike-knowledge-seam",
                urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> bootstrap = Class.forName(IkeServiceBootstrap.class.getName(), true, loader);
            Method main = bootstrap.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{serviceInterfaceName,
                    requestFile.toString(), resultFile.toString()});
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new MojoException("Knowledge service " + serviceInterfaceName
                    + " failed in-process: " + e.getCause().getMessage(), e.getCause());
        } catch (MojoException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoException("Cannot run the knowledge service in-process", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContext);
        }
    }
}
