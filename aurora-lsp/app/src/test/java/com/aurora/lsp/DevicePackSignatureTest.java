package com.aurora.lsp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the devices.json signature check.
 *
 * <p>A hostile devices.json can move the band table, raise the boost ceilings and reshape the
 * limiter, so an unsigned or wrongly-signed response is a config takeover, not a missing feature.
 * These assertions pin the fail-closed rule: anything that is not an exact ECDSA P-256 signature
 * over the exact bytes is rejected.
 */
public class DevicePackSignatureTest {

    private static final String BODY =
            "{\"packs\":[{\"name\":\"test\"}]}";

    // Signature over the bytes of BODY above, produced by the release key (see sign/).
    private static final String GOOD_SIGNATURE =
            "MEUCIQCcLo7lgTFHMw5w7Mz884FNcQ9gUmsEHDktc5I/JZOalQIgASkDSzb3JhQUwoOrMHgFSJXa18nvx8CUkYK9B8PZpvo=";

    @Test
    public void aValidSignatureOverTheExactBodyPasses() {
        assertTrue("the release key's signature must verify",
                DevicePack.INSTANCE.verify(BODY.getBytes(), GOOD_SIGNATURE));
    }

    @Test
    public void aMissingSignatureIsRejected() {
        assertFalse("no header means no trust",
                DevicePack.INSTANCE.verify(BODY.getBytes(), null));
        assertFalse("a blank header is just as untrusted",
                DevicePack.INSTANCE.verify(BODY.getBytes(), ""));
    }

    @Test
    public void aTamperedBodyIsRejected() {
        String tampered = BODY.replace("test", "evil");
        assertFalse("flipping one field must break the signature",
                DevicePack.INSTANCE.verify(tampered.getBytes(), GOOD_SIGNATURE));
    }

    @Test
    public void aGarbageSignatureIsRejected() {
        assertFalse("a non-base64 header must not throw, just fail",
                DevicePack.INSTANCE.verify(BODY.getBytes(), "not-a-signature"));
    }

    @Test
    public void aSignatureForDifferentBytesIsRejected() {
        String other = "{\"packs\":[]}";
        assertFalse("a signature is bound to its exact body",
                DevicePack.INSTANCE.verify(other.getBytes(), GOOD_SIGNATURE));
    }
}
