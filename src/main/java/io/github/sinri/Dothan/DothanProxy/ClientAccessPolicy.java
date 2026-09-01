package io.github.sinri.Dothan.DothanProxy;

import org.apache.commons.validator.routines.InetAddressValidator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An immutable, side-effect-free policy for deciding whether a client address may connect.
 */
public final class ClientAccessPolicy {
    private final Set<String> whitelist;
    private final Set<String> blacklist;

    public ClientAccessPolicy(Set<String> whitelist, Set<String> blacklist) {
        this.whitelist = canonicalize(whitelist);
        this.blacklist = canonicalize(blacklist);
    }

    public Decision evaluate(String clientAddress) {
        final String canonicalAddress;
        try {
            canonicalAddress = canonicalAddress(clientAddress);
        } catch (IllegalArgumentException error) {
            return Decision.INVALID_ADDRESS;
        }
        if (blacklist.contains(canonicalAddress)) {
            return Decision.BLACKLISTED;
        }
        if (!whitelist.isEmpty() && !whitelist.contains(canonicalAddress)) {
            return Decision.NOT_WHITELISTED;
        }
        return Decision.ALLOWED;
    }

    public static String canonicalAddress(String address) {
        if (address == null || !InetAddressValidator.getInstance().isValid(address)) {
            throw new IllegalArgumentException("invalid IP address: " + address);
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("invalid IP address: " + address, error);
        }
    }

    private static Set<String> canonicalize(Set<String> addresses) {
        return addresses.stream()
                .map(ClientAccessPolicy::canonicalAddress)
                .collect(Collectors.toUnmodifiableSet());
    }

    public enum Decision {
        ALLOWED(true, "allowed"),
        BLACKLISTED(false, "address is blacklisted"),
        NOT_WHITELISTED(false, "address is not whitelisted"),
        INVALID_ADDRESS(false, "remote address is invalid");

        private final boolean allowed;
        private final String reason;

        Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }
}
