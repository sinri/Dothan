package io.github.sinri.Dothan.DothanProxy;

/**
 * Protects the link between an ENCRYPT node and a DECRYPT node.
 */
public enum SecureTransportModeEnum {
    /** Password-based, framed AES-GCM records. This is the configuration-compatible default. */
    RECORD,
    /** TLS 1.3 with certificates on both peers. */
    TLS
}
