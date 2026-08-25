package io.github.sinri.Dothan.Security;

import io.github.sinri.Dothan.DothanProxy.DothanTransferModeEnum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureRecordCodecTest {
    private static final String KEY = "a-test-key-with-enough-entropy-0123456789";

    @Test
    void roundTripsBothDirectionsWithRandomTcpFragmentation() throws Exception {
        byte[] request = randomBytes(1_100_003, 1);
        byte[] response = randomBytes(900_007, 2);

        assertArrayEquals(request, roundTrip(request, DothanTransferModeEnum.ENCRYPT, 3));
        assertArrayEquals(response, roundTrip(response, DothanTransferModeEnum.DECRYPT, 4));
    }

    @Test
    void acceptsManyRecordsMergedIntoOneTcpRead() throws Exception {
        byte[] plaintext = randomBytes(SecureRecordCodec.MAX_PLAINTEXT_LENGTH * 3 + 17, 5);
        BoundCodecs codecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);

        byte[] wire = wireBytes(codecs.encoder(), plaintext);
        assertArrayEquals(plaintext, concatenate(codecs.decoder().accept(wire)));
        codecs.decoder().endOfInput();
    }

    @Test
    void rejectsCiphertextTampering() throws Exception {
        BoundCodecs codecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        byte[] wire = wireBytes(codecs.encoder(), "sensitive payload".getBytes());
        wire[wire.length - 1] ^= 1;

        assertThrows(GeneralSecurityException.class, () -> codecs.decoder().accept(wire));
    }

    @Test
    void rejectsWrongKeyAndWrongRoleDuringPeerAuthentication() throws Exception {
        SecureRecordCodec.Encoder encoder = new SecureRecordCodec.Encoder(KEY, DothanTransferModeEnum.ENCRYPT);
        SecureRecordCodec.Encoder localEncoder = new SecureRecordCodec.Encoder(KEY, DothanTransferModeEnum.DECRYPT);
        byte[] header = encoder.header();

        SecureRecordCodec.Decoder wrongKey = new SecureRecordCodec.Decoder(
                "wrong-key", DothanTransferModeEnum.ENCRYPT, localEncoder.header());
        assertThrows(SecureRecordCodec.ProtocolException.class, () -> wrongKey.accept(header));

        SecureRecordCodec.Decoder wrongRole = new SecureRecordCodec.Decoder(
                KEY, DothanTransferModeEnum.DECRYPT, localEncoder.header());
        assertThrows(SecureRecordCodec.ProtocolException.class, () -> wrongRole.accept(header));
    }

    @Test
    void rejectsReorderedRecords() throws Exception {
        BoundCodecs codecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        List<byte[]> records = codecs.encoder().encode(randomBytes(SecureRecordCodec.MAX_PLAINTEXT_LENGTH + 1, 6));
        codecs.decoder().accept(codecs.encoder().header());

        assertThrows(SecureRecordCodec.ProtocolException.class, () -> codecs.decoder().accept(records.get(1)));
    }

    @Test
    void reportsTruncatedHeaderAndRecordAtEndOfInput() throws Exception {
        BoundCodecs headerCodecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        headerCodecs.decoder().accept(Arrays.copyOf(headerCodecs.encoder().header(), 10));
        assertFalse(headerCodecs.decoder().isHeaderAccepted());
        assertThrows(SecureRecordCodec.ProtocolException.class, headerCodecs.decoder()::endOfInput);

        BoundCodecs recordCodecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        byte[] wire = wireBytes(recordCodecs.encoder(), randomBytes(100, 7));
        recordCodecs.decoder().accept(Arrays.copyOf(wire, wire.length - 1));
        assertTrue(recordCodecs.decoder().isHeaderAccepted());
        assertThrows(SecureRecordCodec.ProtocolException.class, recordCodecs.decoder()::endOfInput);
    }

    @Test
    void rejectsTruncationAtACompleteRecordBoundaryWithoutSecureClose() throws Exception {
        BoundCodecs codecs = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        ByteArrayOutputStream incompleteStream = new ByteArrayOutputStream();
        incompleteStream.write(codecs.encoder().header());
        incompleteStream.write(codecs.encoder().encode("complete data record".getBytes()).get(0));

        codecs.decoder().accept(incompleteStream.toByteArray());
        assertThrows(SecureRecordCodec.ProtocolException.class, codecs.decoder()::endOfInput);
    }

    @Test
    void rejectsACompleteSessionReplayedAgainstANewPeerChallenge() throws Exception {
        BoundCodecs originalSession = boundCodecs(KEY, DothanTransferModeEnum.ENCRYPT);
        byte[] capturedWire = wireBytes(originalSession.encoder(), "do not replay".getBytes());
        SecureRecordCodec.Encoder freshLocalEncoder = new SecureRecordCodec.Encoder(
                KEY, DothanTransferModeEnum.DECRYPT);
        SecureRecordCodec.Decoder freshDecoder = new SecureRecordCodec.Decoder(
                KEY, DothanTransferModeEnum.ENCRYPT, freshLocalEncoder.header());

        assertThrows(GeneralSecurityException.class, () -> freshDecoder.accept(capturedWire));
    }

    private static byte[] roundTrip(byte[] plaintext, DothanTransferModeEnum role, int seed) throws Exception {
        BoundCodecs codecs = boundCodecs(KEY, role);
        byte[] wire = wireBytes(codecs.encoder(), plaintext);
        Random random = new Random(seed);
        List<byte[]> decoded = new ArrayList<>();
        int offset = 0;
        while (offset < wire.length) {
            int fragmentLength = Math.min(wire.length - offset, 1 + random.nextInt(32_000));
            decoded.addAll(codecs.decoder().accept(Arrays.copyOfRange(wire, offset, offset + fragmentLength)));
            offset += fragmentLength;
        }
        codecs.decoder().endOfInput();
        return concatenate(decoded);
    }

    private static BoundCodecs boundCodecs(String key, DothanTransferModeEnum senderRole) throws Exception {
        DothanTransferModeEnum receiverRole = senderRole == DothanTransferModeEnum.ENCRYPT
                ? DothanTransferModeEnum.DECRYPT
                : DothanTransferModeEnum.ENCRYPT;
        SecureRecordCodec.Encoder sender = new SecureRecordCodec.Encoder(key, senderRole);
        SecureRecordCodec.Encoder receiver = new SecureRecordCodec.Encoder(key, receiverRole);
        sender.bindPeerHeader(receiver.header());
        SecureRecordCodec.Decoder decoder = new SecureRecordCodec.Decoder(key, senderRole, receiver.header());
        return new BoundCodecs(sender, decoder);
    }

    private static byte[] wireBytes(SecureRecordCodec.Encoder encoder, byte[] plaintext) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(encoder.header());
        for (byte[] record : encoder.encode(plaintext)) {
            output.write(record);
        }
        output.write(encoder.closeRecord());
        return output.toByteArray();
    }

    private static byte[] concatenate(List<byte[]> chunks) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            output.write(chunk);
        }
        return output.toByteArray();
    }

    private static byte[] randomBytes(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private record BoundCodecs(SecureRecordCodec.Encoder encoder, SecureRecordCodec.Decoder decoder) {
    }
}
