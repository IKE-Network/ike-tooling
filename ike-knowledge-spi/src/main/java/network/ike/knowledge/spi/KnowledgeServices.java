package network.ike.knowledge.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Implementation selection: exactly-one-or-named resolution over the context class
 * loader. The implementation arrives as an ordinary dependency at the use site (or the
 * parent POM's default); this helper only chooses among what the classpath provides —
 * selection is a dependency, never a view dimension.
 */
public final class KnowledgeServices {

    private KnowledgeServices() {
    }

    /**
     * Selects the implementation of a knowledge service from the context class loader.
     *
     * @param <S>                the service type
     * @param serviceInterface   the service interface to resolve
     * @param implementationName the implementation's simple class name, when the
     *                           classpath carries several
     * @return the selected implementation
     * @throws IllegalStateException if no implementation is present, several are present
     *                               without a selection, or the named implementation is
     *                               not among them
     */
    public static <S> S select(Class<S> serviceInterface, Optional<String> implementationName) {
        List<S> found = new ArrayList<>();
        ServiceLoader.load(serviceInterface, Thread.currentThread().getContextClassLoader())
                .forEach(found::add);
        if (implementationName.isPresent()) {
            String name = implementationName.get();
            List<S> matching = found.stream()
                    .filter(candidate -> candidate.getClass().getSimpleName().equals(name))
                    .toList();
            if (matching.size() == 1) {
                return matching.getFirst();
            }
            throw new IllegalStateException("Requested " + serviceInterface.getSimpleName()
                    + " implementation \"" + name + "\" — " + describe(found)
                    + ". Selection matches the implementation class's simple name exactly.");
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        throw new IllegalStateException("Expected exactly one " + serviceInterface.getSimpleName()
                + " implementation on the classpath — " + describe(found)
                + ". Declare an implementation dependency at the use site (or rely on the parent"
                + " POM's default), and name one explicitly when several are present.");
    }

    private static String describe(List<?> found) {
        if (found.isEmpty()) {
            return "found none";
        }
        return "found " + found.size() + ": "
                + found.stream().map(implementation -> implementation.getClass().getName()).toList();
    }
}
