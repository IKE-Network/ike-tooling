package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Post an announcement to a Zulip stream
 * (IKE-Network/ike-issues#1016).
 *
 * <p>The publishing half of {@link IkeAnnounceDraftMojo}; run the draft
 * first to see exactly what would be said and where.
 */
@Mojo(name = IkeGoal.NAME_ANNOUNCE_PUBLISH, projectRequired = false)
public class IkeAnnouncePublishMojo extends IkeAnnounceDraftMojo {

    /** Creates this goal instance. */
    public IkeAnnouncePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        // Set here, not as a field initialiser: Maven's parameter
        // injection overwrites field assignments with the declared
        // default, which once shipped a publish goal that only drafted
        // (IKE-Network/ike-issues#987).
        publish = true;
        super.execute();
    }
}
