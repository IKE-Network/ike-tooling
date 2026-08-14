package network.ike.plugin;

import network.ike.plugin.AnnounceSupport.Announcement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the announcement goal's pure half
 * (IKE-Network/ike-issues#1016) — everything except the network call, so
 * the parts that used to be untestable shell are now testable.
 */
class AnnounceSupportTest {

    private static Announcement valid() {
        return new Announcement("https://ike.zulipchat.com", "Komet Installer",
                "release ike-komet-wsr-2", "**Release 2** — working set published");
    }

    @Test
    void aCompleteAnnouncementValidates() {
        assertNull(AnnounceSupport.validate(valid()));
    }

    @Test
    void eachMissingPieceIsNamedPrecisely() {
        assertEquals("stream is not set", AnnounceSupport.validate(
                new Announcement("https://z", "", "t", "c")));
        assertEquals("topic is not set", AnnounceSupport.validate(
                new Announcement("https://z", "s", " ", "c")));
        assertEquals("message content is empty", AnnounceSupport.validate(
                new Announcement("https://z", "s", "t", "")));
        assertEquals("zulip site URL is not set", AnnounceSupport.validate(
                new Announcement(null, "s", "t", "c")));
        assertEquals("no announcement", AnnounceSupport.validate(null));
    }

    @Test
    void anOverlongMessageIsRefusedBeforeItIsSent() {
        // Zulip's limit; better to say so than to have the API say it.
        String tooLong = "x".repeat(10001);
        String problem = AnnounceSupport.validate(
                new Announcement("https://z", "s", "t", tooLong));
        assertTrue(problem.contains("10001"), problem);
        assertTrue(problem.contains("10000"), problem);
    }

    @Test
    void theFormBodyEncodesEveryFieldZulipNeeds() {
        String body = AnnounceSupport.formBody(valid());
        assertTrue(body.startsWith("type=stream&"), body);
        // Spaces and markdown survive encoding.
        assertTrue(body.contains("to=Komet+Installer"), body);
        assertTrue(body.contains("topic=release+ike-komet-wsr-2"), body);
        // Asterisks need no escaping in a form body; the em dash does.
        assertTrue(body.contains("content=**Release+2**"), body);
        assertTrue(body.contains("%E2%80%94"), body);
    }

    @Test
    void multilineContentSurvivesEncoding() {
        String body = AnnounceSupport.formBody(new Announcement(
                "https://z", "s", "t", "line one\nline two"));
        assertTrue(body.contains("line+one%0Aline+two"), body);
    }

    @Test
    void theDestinationLineNamesWhereItGoesAndCarriesNoSecret() {
        assertEquals("ike.zulipchat.com · #Komet Installer > release ike-komet-wsr-2",
                AnnounceSupport.destination(valid()));
    }
}
