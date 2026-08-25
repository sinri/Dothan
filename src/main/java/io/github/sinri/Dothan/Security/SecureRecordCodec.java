package io.github.sinri.Dothan.Security;

import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import io.vertx.core.buffer.Buffer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dothan secure record protocol version 2.
 *
 * <p>Each direction has an authenticated header, an independently derived key and a
 * random nonce prefix. Each traffic key is bound to the fresh authenticated header
 * sent by its peer, preventing a captured session from being replayed on a new connection.
 * Records carry a strictly increasing sequence number and are
 * protected by AES-256-GCM. The explicit length makes decoding independent of TCP
 * packet boundaries.
 */
public final class SecureRecordCodec {
    static final byte[] MAGIC = {'D', 'T', 'H', 'N'};
    static final byte VERSION = 2;
    static final int HEADER_LENGTH = 58;
    static final int MAX_PLAINTEXT_LENGTH = 16 * 1024;
    static final int TAG_LENGTH = 16;
    static final int MAX_FRAME_LENGTH = Long.BYTES + MAX_PLAINTEXT_LENGTH + TAG_LENGTH;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_PREFIX_LENGTH = 4;
    private static final int HEADER_TAG_LENGTH = 32;
    private static final int KDF_ITERATIONS = 210_000;
    private static final byte[] KDF_CONTEXT = "Dothan secure record v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PEER_BINDING_CONTEXT = "Dothan peer binding v2".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecureRecordCodec() {
    }

    public static final class Encoder {
        private byte[] baseEncryptionKey;
        private SecretKeySpec encryptionKey;
        private final byte[] noncePrefix;
        private final byte[] aadPrefix;
        private final byte[] header;
        private long sequence;
        private boolean closed;

        public Encoder(String transferKey, DothanTransferModeEnum senderRole) throws GeneralSecurityException {
            requireSecureRole(senderRole);
            requireTransferKey(transferKey);

            byte[] salt = randomBytes(SALT_LENGTH);
            noncePrefix = randomBytes(NONCE_PREFIX_LENGTH);
            byte[] material = deriveMaterial(transferKey, salt);
            baseEncryptionKey = Arrays.copyOfRange(material, 0, 32);
            byte[] authenticationKey = Arrays.copyOfRange(material, 32, 64);
            Arrays.fill(material, (byte) 0);

            aadPrefix = createHeaderPrefix(senderRole, salt, noncePrefix);
            byte[] headerTag = hmac(authenticationKey, aadPrefix);
            Arrays.fill(authenticationKey, (byte) 0);
            header = ByteBuffer.allocate(HEADER_LENGTH).put(aadPrefix).put(headerTag).array();
        }

        public byte[] header() {
            return header.clone();
        }

        public void bindPeerHeader(byte[] peerHeader) throws GeneralSecurityException, ProtocolException {
            if (encryptionKey != null) {
                throw new ProtocolException("secure record encoder is already bound to a peer");
            }
            validateHeaderLength(peerHeader);
            encryptionKey = new SecretKeySpec(bindKey(baseEncryptionKey, peerHeader), "AES");
            Arrays.fill(baseEncryptionKey, (byte) 0);
            baseEncryptionKey = null;
        }

        public boolean isPeerBound() {
            return encryptionKey != null;
        }

        public List<byte[]> encode(byte[] plaintext) throws GeneralSecurityException, ProtocolException {
            if (encryptionKey == null) {
                throw new ProtocolException("secure record peer handshake is incomplete");
            }
            if (closed) {
                throw new ProtocolException("secure record encoder is closed");
            }
            List<byte[]> records = new ArrayList<>();
            for (int offset = 0; offset < plaintext.length; offset += MAX_PLAINTEXT_LENGTH) {
                int length = Math.min(MAX_PLAINTEXT_LENGTH, plaintext.length - offset);
                records.add(encodeRecord(plaintext, offset, length));
            }
            return records;
        }

        public byte[] closeRecord() throws GeneralSecurityException, ProtocolException {
            if (encryptionKey == null) {
                throw new ProtocolException("secure record peer handshake is incomplete");
            }
            if (closed) {
                throw new ProtocolException("secure record encoder is already closed");
            }
            closed = true;
            return encodeRecord(new byte[0], 0, 0);
        }

        private byte[] encodeRecord(byte[] plaintext, int offset, int length)
                throws GeneralSecurityException, ProtocolException {
            if (sequence == Long.MAX_VALUE) {
                throw new ProtocolException("secure record sequence exhausted");
            }
            long recordSequence = sequence++;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(TAG_LENGTH * Byte.SIZE, nonce(recordSequence)));
            cipher.updateAAD(recordAad(aadPrefix, recordSequence, length));
            byte[] ciphertext = cipher.doFinal(plaintext, offset, length);
            int frameLength = Long.BYTES + ciphertext.length;
            return ByteBuffer.allocate(Integer.BYTES + frameLength)
                    .putInt(frameLength)
                    .putLong(recordSequence)
                    .put(ciphertext)
                    .array();
        }

        private byte[] nonce(long recordSequence) {
            return ByteBuffer.allocate(12).put(noncePrefix).putLong(recordSequence).array();
        }
    }

    public static final class Decoder {
        private String transferKey;
        private final DothanTransferModeEnum expectedSenderRole;
        private final byte[] localHeader;
        private Buffer pending = Buffer.buffer();
        private SecretKeySpec encryptionKey;
        private byte[] noncePrefix;
        private byte[] aadPrefix;
        private long expectedSequence;
        private boolean headerAccepted;
        private byte[] acceptedHeader;
        private boolean closeReceived;

        public Decoder(String transferKey, DothanTransferModeEnum expectedSenderRole, byte[] localHeader) {
            requireSecureRole(expectedSenderRole);
            requireTransferKey(transferKey);
            validateHeaderLength(localHeader);
            this.transferKey = transferKey;
            this.expectedSenderRole = expectedSenderRole;
            this.localHeader = localHeader.clone();
        }

        public List<byte[]> accept(byte[] bytes) throws GeneralSecurityException, ProtocolException {
            if (closeReceived && bytes.length > 0) {
                throw new ProtocolException("data received after secure close record");
            }
            pending.appendBytes(bytes);
            List<byte[]> plaintextRecords = new ArrayList<>();
            if (!headerAccepted && !acceptHeader()) {
                return plaintextRecords;
            }

            while (pending.length() >= Integer.BYTES) {
                int frameLength = pending.getInt(0);
                if (frameLength < Long.BYTES + TAG_LENGTH || frameLength > MAX_FRAME_LENGTH) {
                    throw new ProtocolException("invalid secure record length: " + frameLength);
                }
                if (pending.length() < Integer.BYTES + frameLength) {
                    break;
                }
                long sequence = pending.getLong(Integer.BYTES);
                if (sequence != expectedSequence) {
                    throw new ProtocolException("unexpected secure record sequence");
                }
                int ciphertextOffset = Integer.BYTES + Long.BYTES;
                byte[] ciphertext = pending.getBytes(ciphertextOffset, Integer.BYTES + frameLength);
                int plaintextLength = ciphertext.length - TAG_LENGTH;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                        new GCMParameterSpec(TAG_LENGTH * Byte.SIZE, nonce(sequence)));
                cipher.updateAAD(recordAad(aadPrefix, sequence, plaintextLength));
                byte[] plaintext = cipher.doFinal(ciphertext);
                expectedSequence++;
                consume(Integer.BYTES + frameLength);
                if (plaintext.length == 0) {
                    closeReceived = true;
                    if (pending.length() != 0) {
                        throw new ProtocolException("data received after secure close record");
                    }
                    break;
                }
                plaintextRecords.add(plaintext);
            }
            return plaintextRecords;
        }

        public boolean isHeaderAccepted() {
            return headerAccepted;
        }

        public byte[] acceptedHeader() throws ProtocolException {
            if (!headerAccepted) {
                throw new ProtocolException("secure record peer handshake is incomplete");
            }
            return acceptedHeader.clone();
        }

        public void endOfInput() throws ProtocolException {
            if (!headerAccepted || pending.length() != 0 || !closeReceived) {
                throw new ProtocolException("encrypted stream ended without a valid secure close record");
            }
        }

        public boolean isCloseReceived() {
            return closeReceived;
        }

        private boolean acceptHeader() throws GeneralSecurityException, ProtocolException {
            if (pending.length() < HEADER_LENGTH) {
                return false;
            }
            byte[] candidate = pending.getBytes(0, HEADER_LENGTH);
            aadPrefix = Arrays.copyOfRange(candidate, 0, HEADER_LENGTH - HEADER_TAG_LENGTH);
            ByteBuffer headerBuffer = ByteBuffer.wrap(aadPrefix);
            byte[] magic = new byte[MAGIC.length];
            headerBuffer.get(magic);
            byte version = headerBuffer.get();
            byte role = headerBuffer.get();
            byte[] salt = new byte[SALT_LENGTH];
            headerBuffer.get(salt);
            noncePrefix = new byte[NONCE_PREFIX_LENGTH];
            headerBuffer.get(noncePrefix);

            if (!Arrays.equals(MAGIC, magic) || version != VERSION || role != roleByte(expectedSenderRole)) {
                throw new ProtocolException("unsupported or unexpected secure record header");
            }
            byte[] material = deriveMaterial(transferKey, salt);
            transferKey = null;
            byte[] baseEncryptionKey = Arrays.copyOfRange(material, 0, 32);
            byte[] authenticationKey = Arrays.copyOfRange(material, 32, 64);
            Arrays.fill(material, (byte) 0);
            byte[] expectedTag = hmac(authenticationKey, aadPrefix);
            Arrays.fill(authenticationKey, (byte) 0);
            byte[] actualTag = Arrays.copyOfRange(candidate, aadPrefix.length, candidate.length);
            if (!MessageDigest.isEqual(expectedTag, actualTag)) {
                throw new ProtocolException("secure record peer authentication failed");
            }
            encryptionKey = new SecretKeySpec(bindKey(baseEncryptionKey, localHeader), "AES");
            Arrays.fill(baseEncryptionKey, (byte) 0);
            acceptedHeader = candidate;
            headerAccepted = true;
            consume(HEADER_LENGTH);
            return true;
        }

        private byte[] nonce(long sequence) {
            return ByteBuffer.allocate(12).put(noncePrefix).putLong(sequence).array();
        }

        private void consume(int length) {
            pending = length == pending.length()
                    ? Buffer.buffer()
                    : Buffer.buffer(pending.getBytes(length, pending.length()));
        }
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }

    private static byte[] createHeaderPrefix(DothanTransferModeEnum senderRole, byte[] salt, byte[] noncePrefix) {
        return ByteBuffer.allocate(HEADER_LENGTH - HEADER_TAG_LENGTH)
                .put(MAGIC)
                .put(VERSION)
                .put(roleByte(senderRole))
                .put(salt)
                .put(noncePrefix)
                .array();
    }

    private static byte roleByte(DothanTransferModeEnum role) {
        return switch (role) {
            case ENCRYPT -> 1;
            case DECRYPT -> 2;
            default -> throw new IllegalArgumentException("PLAIN is not a secure record role");
        };
    }

    private static void requireSecureRole(DothanTransferModeEnum role) {
        if (role == null || role == DothanTransferModeEnum.PLAIN) {
            throw new IllegalArgumentException("a secure record role is required");
        }
    }

    private static void requireTransferKey(String transferKey) {
        if (transferKey == null || transferKey.isBlank()) {
            throw new IllegalArgumentException("TRANSFER KEY must not be blank");
        }
    }

    private static byte[] deriveMaterial(String transferKey, byte[] randomSalt) throws GeneralSecurityException {
        byte[] kdfSalt = ByteBuffer.allocate(KDF_CONTEXT.length + randomSalt.length)
                .put(KDF_CONTEXT)
                .put(randomSalt)
                .array();
        PBEKeySpec keySpec = new PBEKeySpec(transferKey.toCharArray(), kdfSalt, KDF_ITERATIONS, 512);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).getEncoded();
        } finally {
            keySpec.clearPassword();
        }
    }

    private static byte[] hmac(byte[] key, byte[] content) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(content);
    }

    private static byte[] bindKey(byte[] baseKey, byte[] peerHeader) throws GeneralSecurityException {
        return hmac(baseKey, ByteBuffer.allocate(PEER_BINDING_CONTEXT.length + peerHeader.length)
                .put(PEER_BINDING_CONTEXT)
                .put(peerHeader)
                .array());
    }

    private static void validateHeaderLength(byte[] header) {
        if (header == null || header.length != HEADER_LENGTH) {
            throw new IllegalArgumentException("a complete local secure record header is required");
        }
    }

    private static byte[] recordAad(byte[] prefix, long sequence, int plaintextLength) {
        return ByteBuffer.allocate(prefix.length + Long.BYTES + Integer.BYTES)
                .put(prefix)
                .putLong(sequence)
                .putInt(plaintextLength)
                .array();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }
}
