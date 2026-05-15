package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply convergence on the project's deployed-site state — deploy the
 * current version + register on the IKE Network landing page.
 *
 * <p>The {@code -publish} counterpart of
 * {@link IkeSiteDraftMojo ike:site-draft}. Default behavior deploys
 * the current version to the project's {@code gh-pages} branch and
 * updates the landing-page registration.
 *
 * <p>Flags (mirror the #393 ws-side scheme):
 * <ul>
 *   <li>{@code -DupdateSite=false} — skip the gh-pages site deploy</li>
 *   <li>{@code -DupdateRegistration=false} — skip the landing-page update</li>
 *   <li>{@code -Dsite=removed} — invert the apply pass: deregister
 *       and remove the deployed site (subsumes the retired
 *       {@code ike:clean-site} + {@code ike:deregister-site-publish})</li>
 * </ul>
 *
 * <p>Subsumes the retired {@code ike:deploy-site-publish},
 * {@code ike:register-site-publish}, {@code ike:deregister-site-publish},
 * and {@code ike:clean-site} (in its publish role) goals. See
 * IKE-Network/ike-issues#398.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ike:site-publish                          # deploy + register
 * mvn ike:site-publish -DupdateSite=false       # registration only
 * mvn ike:site-publish -DupdateRegistration=false  # deploy only
 * mvn ike:site-publish -Dsite=removed           # deregister + cleanup
 * }</pre>
 *
 * @see IkeSiteDraftMojo
 */
@Mojo(name = "site-publish", projectRequired = true, aggregator = true)
public class IkeSitePublishMojo extends IkeSiteDraftMojo {

    /** Creates this goal instance. */
    public IkeSitePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
