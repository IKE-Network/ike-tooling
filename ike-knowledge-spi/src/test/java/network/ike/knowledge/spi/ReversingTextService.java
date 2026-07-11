package network.ike.knowledge.spi;

/**
 * A real {@link TextService}: reverses the text.
 */
public final class ReversingTextService implements TextService {

    /** Creates the service (ServiceLoader requirement). */
    public ReversingTextService() {
    }

    @Override
    public TextResult transform(TextRequest request) {
        return new TextResult(new StringBuilder(request.text()).reverse().toString());
    }
}
