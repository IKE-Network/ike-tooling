package network.ike.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotarizeMojoTest {

    /** Real failure captured 2026-04-25 from the laptop notarize attempt. */
    private static final String LAPTOP_2026_04_25_TIMEOUT = """
            Conducting pre-submission checks for Komet Desktop 2026-04-25 2212s-osx-aarch64.pkg and initiating connection to the Apple notary service...
            Error: HTTPError(statusCode: nil, error: Error Domain=NSURLErrorDomain Code=-1001 "The request timed out." UserInfo={_kCFStreamErrorCodeKey=-2102, NSUnderlyingError=0x9650a91a0 {Error Domain=kCFErrorDomainCFNetwork Code=-1001 "(null)" UserInfo={_kCFStreamErrorCodeKey=-2102, _kCFStreamErrorDomainKey=4}}, _NSURLErrorFailingURLSessionTaskErrorKey=LocalDataTask, NSLocalizedDescription=The request timed out.})
            """;

    /** Successful submission output (real, captured 2026-04-25 22:30 UTC). */
    private static final String SUCCESS_OUTPUT = """
            Conducting pre-submission checks for Komet Desktop 2026-04-25 2212s-osx-aarch64.pkg and initiating connection to the Apple notary service...
            Submission ID received
              id: 9477874c-b0de-443e-a6b9-a199c31e2c31
            Successfully uploaded file
              id: 9477874c-b0de-443e-a6b9-a199c31e2c31
            Waiting for processing to complete. Wait timeout is set to 1800.0 second(s).
            Current status: In Progress...Current status: In Progress.....
            Current status: Accepted.......................Processing complete
              id: 9477874c-b0de-443e-a6b9-a199c31e2c31
              status: Accepted
            """;

    /** Genuine notarization rejection — exit 0 from xcrun, status=Invalid. */
    private static final String REJECTED_OUTPUT = """
            Conducting pre-submission checks for foo.pkg and initiating connection to the Apple notary service...
            Submission ID received
              id: deadbeef-1111-2222-3333-444455556666
            Successfully uploaded file
              id: deadbeef-1111-2222-3333-444455556666
            Waiting for processing to complete.
            Current status: In Progress.....Current status: Invalid....Processing complete
              id: deadbeef-1111-2222-3333-444455556666
              status: Invalid
            """;

    /** Drop after Submission ID was received (post-id transient). */
    private static final String POST_ID_NETWORK_DROP = """
            Conducting pre-submission checks for foo.pkg and initiating connection to the Apple notary service...
            Submission ID received
              id: 12345678-aaaa-bbbb-cccc-deadbeefcafe
            Successfully uploaded file
              id: 12345678-aaaa-bbbb-cccc-deadbeefcafe
            Waiting for processing to complete.
            Current status: In Progress....
            Error: HTTPError(statusCode: nil, error: Error Domain=NSURLErrorDomain Code=-1001 "The request timed out.")
            """;

    // ---- isTransientNetworkError ----

    @Test
    void detects_NSURLErrorDomain_minus1001() {
        assertTrue(NotarizeMojo.isTransientNetworkError(LAPTOP_2026_04_25_TIMEOUT));
    }

    @Test
    void detects_post_id_network_drop() {
        assertTrue(NotarizeMojo.isTransientNetworkError(POST_ID_NETWORK_DROP));
    }

    @Test
    void detects_kCFErrorDomainCFNetwork() {
        assertTrue(NotarizeMojo.isTransientNetworkError(
                "some random Domain=kCFErrorDomainCFNetwork Code=-2102 noise"));
    }

    @Test
    void detects_could_not_connect_message() {
        assertTrue(NotarizeMojo.isTransientNetworkError(
                "Error: Could not connect to the notary service"));
    }

    @Test
    void detects_offline_message() {
        assertTrue(NotarizeMojo.isTransientNetworkError(
                "The Internet connection appears to be offline."));
    }

    @Test
    void rejected_status_is_not_transient() {
        // Genuine rejection — must NOT trigger retry.
        assertFalse(NotarizeMojo.isTransientNetworkError(REJECTED_OUTPUT));
    }

    @Test
    void successful_output_is_not_transient() {
        assertFalse(NotarizeMojo.isTransientNetworkError(SUCCESS_OUTPUT));
    }

    @Test
    void keychain_error_is_not_transient() {
        // Wrong keychain profile → not retried; user has to fix config.
        assertFalse(NotarizeMojo.isTransientNetworkError(
                "Error: keychain item not found for profile 'notarytool'"));
    }

    @Test
    void blank_output_is_not_transient() {
        assertFalse(NotarizeMojo.isTransientNetworkError(""));
        assertFalse(NotarizeMojo.isTransientNetworkError(null));
    }

    // ---- extractSubmissionId ----

    @Test
    void extracts_id_from_success() {
        assertEquals("9477874c-b0de-443e-a6b9-a199c31e2c31",
                NotarizeMojo.extractSubmissionId(SUCCESS_OUTPUT));
    }

    @Test
    void extracts_id_even_when_followed_by_network_drop() {
        // Post-id failure: the id was already printed; we must capture it
        // so the retry loop can switch from `submit` to `info <id>`.
        assertEquals("12345678-aaaa-bbbb-cccc-deadbeefcafe",
                NotarizeMojo.extractSubmissionId(POST_ID_NETWORK_DROP));
    }

    @Test
    void no_id_when_pre_id_timeout() {
        assertNull(NotarizeMojo.extractSubmissionId(LAPTOP_2026_04_25_TIMEOUT));
    }

    @Test
    void no_id_for_blank_output() {
        assertNull(NotarizeMojo.extractSubmissionId(""));
        assertNull(NotarizeMojo.extractSubmissionId(null));
    }

    // ---- extractStatus ----

    @Test
    void extracts_accepted_status() {
        // Last `status:` wins, ignoring `Current status:` progress lines.
        assertEquals("Accepted", NotarizeMojo.extractStatus(SUCCESS_OUTPUT));
    }

    @Test
    void extracts_invalid_status_for_genuine_rejection() {
        assertEquals("Invalid", NotarizeMojo.extractStatus(REJECTED_OUTPUT));
    }

    @Test
    void no_status_when_pre_id_timeout() {
        assertNull(NotarizeMojo.extractStatus(LAPTOP_2026_04_25_TIMEOUT));
    }

    // ---- parseBackoffSchedule ----

    @Test
    void parses_default_schedule() {
        assertEquals(List.of(30, 120),
                NotarizeMojo.parseBackoffSchedule("30,120"));
    }

    @Test
    void parses_with_whitespace() {
        assertEquals(List.of(30, 120, 300),
                NotarizeMojo.parseBackoffSchedule(" 30 , 120 , 300 "));
    }

    @Test
    void skips_blank_and_non_numeric_tokens() {
        assertEquals(List.of(60),
                NotarizeMojo.parseBackoffSchedule("60,,abc, ,xyz"));
    }

    @Test
    void skips_non_positive_values() {
        assertEquals(List.of(30),
                NotarizeMojo.parseBackoffSchedule("0,-5,30"));
    }

    @Test
    void empty_for_blank_input() {
        assertTrue(NotarizeMojo.parseBackoffSchedule("").isEmpty());
        assertTrue(NotarizeMojo.parseBackoffSchedule(null).isEmpty());
        assertTrue(NotarizeMojo.parseBackoffSchedule("   ").isEmpty());
    }

    // ---- backoffWait ----

    @Test
    void backoff_attempt_2_uses_first_entry() {
        assertEquals(30, NotarizeMojo.backoffWait(List.of(30, 120), 2));
    }

    @Test
    void backoff_attempt_3_uses_second_entry() {
        assertEquals(120, NotarizeMojo.backoffWait(List.of(30, 120), 3));
    }

    @Test
    void backoff_reuses_last_entry_when_schedule_short() {
        // attempt 4, but schedule only has 2 entries → reuse last (120)
        assertEquals(120, NotarizeMojo.backoffWait(List.of(30, 120), 4));
        assertEquals(120, NotarizeMojo.backoffWait(List.of(30, 120), 99));
    }

    @Test
    void backoff_zero_for_first_attempt() {
        // attempt 1 never waits — it's the initial try.
        assertEquals(0, NotarizeMojo.backoffWait(List.of(30, 120), 1));
    }

    @Test
    void backoff_zero_for_empty_schedule() {
        assertEquals(0, NotarizeMojo.backoffWait(List.of(), 2));
        assertEquals(0, NotarizeMojo.backoffWait(List.of(), 5));
    }
}
