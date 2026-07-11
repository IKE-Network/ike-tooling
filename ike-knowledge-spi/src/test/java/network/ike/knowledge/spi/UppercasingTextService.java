package network.ike.knowledge.spi;

import java.util.Locale;

/**
 * A real {@link TextService}: uppercases the text.
 */
public final class UppercasingTextService implements TextService {

    /** Creates the service (ServiceLoader requirement). */
    public UppercasingTextService() {
    }

    @Override
    public TextResult transform(TextRequest request) {
        return new TextResult(request.text().toUpperCase(Locale.ROOT));
    }
}
