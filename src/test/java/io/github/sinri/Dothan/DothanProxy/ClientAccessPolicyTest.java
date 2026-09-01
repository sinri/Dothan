package io.github.sinri.Dothan.DothanProxy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.github.sinri.Dothan.DothanProxy.ClientAccessPolicy.Decision.ALLOWED;
import static io.github.sinri.Dothan.DothanProxy.ClientAccessPolicy.Decision.BLACKLISTED;
import static io.github.sinri.Dothan.DothanProxy.ClientAccessPolicy.Decision.INVALID_ADDRESS;
import static io.github.sinri.Dothan.DothanProxy.ClientAccessPolicy.Decision.NOT_WHITELISTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientAccessPolicyTest {
    @Test
    void emptyListsAllowAnyValidAddress() {
        ClientAccessPolicy policy = new ClientAccessPolicy(Set.of(), Set.of());

        assertEquals(ALLOWED, policy.evaluate("127.0.0.1"));
        assertEquals(ALLOWED, policy.evaluate("::1"));
    }

    @Test
    void nonEmptyWhitelistRequiresMembership() {
        ClientAccessPolicy policy = new ClientAccessPolicy(Set.of("127.0.0.1"), Set.of());

        assertEquals(ALLOWED, policy.evaluate("127.0.0.1"));
        assertEquals(NOT_WHITELISTED, policy.evaluate("192.0.2.1"));
    }

    @Test
    void blacklistTakesPrecedenceDefensively() {
        ClientAccessPolicy policy = new ClientAccessPolicy(
                Set.of("127.0.0.1"), Set.of("127.0.0.1"));

        assertEquals(BLACKLISTED, policy.evaluate("127.0.0.1"));
    }

    @Test
    void equivalentIpv6FormsHaveTheSameIdentity() {
        ClientAccessPolicy policy = new ClientAccessPolicy(
                Set.of("0:0:0:0:0:0:0:1"), Set.of());

        assertEquals(ALLOWED, policy.evaluate("::1"));
    }

    @Test
    void invalidRemoteAddressFailsClosedWithoutNameResolution() {
        ClientAccessPolicy policy = new ClientAccessPolicy(Set.of(), Set.of());

        assertEquals(INVALID_ADDRESS, policy.evaluate("client.example"));
    }
}
