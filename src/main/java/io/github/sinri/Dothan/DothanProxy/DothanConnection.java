package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfig;
import io.github.sinri.Dothan.Security.SecureRecordCodec;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;

import java.security.GeneralSecurityException;

public class DothanConnection {
    private final NetSocket clientSocket;
    private final NetSocket serverSocket;
    private final Vertx vertx;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private boolean closed;
    private long handshakeTimerId = -1;

    DothanConnection(Vertx vertx, NetSocket clientSocket, NetSocket serverSocket) {
        this.vertx = vertx;
        this.clientSocket = clientSocket;
        this.serverSocket = serverSocket;
    }

    void proxy() {
        serverSocket.closeHandler(ignored -> close());
        clientSocket.closeHandler(ignored -> close());
        serverSocket.exceptionHandler(error -> fail("service-provider socket failure", error));
        clientSocket.exceptionHandler(error -> fail("client socket failure", error));

        DothanConfig config = DothanConfig.getInstance();
        DothanTransferModeEnum role = config.getTransferMode();
        if (role == DothanTransferModeEnum.PLAIN || config.getSecureTransportMode() == SecureTransportModeEnum.TLS) {
            proxyTransparent();
            return;
        }

        try {
            proxySecureRecords(role, config.getTransferKey());
        } catch (GeneralSecurityException error) {
            fail("could not initialize secure record protocol", error);
        }
    }

    private void proxyTransparent() {
        clientSocket.handler(buffer -> forward(clientSocket, serverSocket, buffer, "request"));
        serverSocket.handler(buffer -> forward(serverSocket, clientSocket, buffer, "response"));
    }

    private void proxySecureRecords(DothanTransferModeEnum role, String transferKey)
            throws GeneralSecurityException {
        DothanTransferModeEnum peerRole = role == DothanTransferModeEnum.ENCRYPT
                ? DothanTransferModeEnum.DECRYPT
                : DothanTransferModeEnum.ENCRYPT;
        SecureRecordCodec.Encoder encoder = new SecureRecordCodec.Encoder(transferKey, role);
        SecureRecordCodec.Decoder decoder = new SecureRecordCodec.Decoder(transferKey, peerRole, encoder.header());
        handshakeTimerId = vertx.setTimer(10_000, ignored -> {
            if (!decoder.isHeaderAccepted()) {
                fail("secure record peer handshake timed out", new SecureRecordCodec.ProtocolException("timeout"));
            }
        });

        if (role == DothanTransferModeEnum.ENCRYPT) {
            clientSocket.pause();
            clientSocket.closeHandler(ignored -> plaintextInputClosed(encoder, serverSocket));
            serverSocket.closeHandler(ignored -> encryptedInputClosed(decoder));
            write(serverSocket, Buffer.buffer(encoder.header()), "secure request header");
            clientSocket.handler(buffer -> encodeAndForward(clientSocket, encoder, serverSocket, buffer, "request"));
            serverSocket.handler(buffer -> decodeAndForward(serverSocket, decoder, encoder,
                    clientSocket, clientSocket, buffer, "response"));
        } else {
            serverSocket.pause();
            serverSocket.closeHandler(ignored -> plaintextInputClosed(encoder, clientSocket));
            clientSocket.closeHandler(ignored -> encryptedInputClosed(decoder));
            write(clientSocket, Buffer.buffer(encoder.header()), "secure response header");
            clientSocket.handler(buffer -> decodeAndForward(clientSocket, decoder, encoder,
                    serverSocket, serverSocket, buffer, "request"));
            serverSocket.handler(buffer -> encodeAndForward(serverSocket, encoder, clientSocket,
                    buffer, "response"));
        }
    }

    private void plaintextInputClosed(SecureRecordCodec.Encoder encoder, NetSocket encryptedDestination) {
        if (closed) {
            return;
        }
        try {
            encryptedDestination.write(Buffer.buffer(encoder.closeRecord()))
                    .onComplete(ignored -> close());
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("could not send secure close record", error);
        }
    }

    private void encryptedInputClosed(SecureRecordCodec.Decoder decoder) {
        if (closed) {
            return;
        }
        try {
            decoder.endOfInput();
            close();
        } catch (SecureRecordCodec.ProtocolException error) {
            fail("encrypted stream ended unexpectedly", error);
        }
    }

    private void encodeAndForward(NetSocket source, SecureRecordCodec.Encoder encoder,
                                  NetSocket destination, Buffer plaintext, String direction) {
        try {
            for (byte[] record : encoder.encode(plaintext.getBytes())) {
                write(source, destination, Buffer.buffer(record), "secure " + direction);
            }
            logTransfer(direction, plaintext.length());
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("secure " + direction + " encryption failed", error);
        }
    }

    private void decodeAndForward(NetSocket encryptedSource, SecureRecordCodec.Decoder decoder,
                                  SecureRecordCodec.Encoder encoder, NetSocket plaintextSource,
                                  NetSocket destination, Buffer ciphertext, String direction) {
        try {
            int plaintextLength = 0;
            for (byte[] plaintext : decoder.accept(ciphertext.getBytes())) {
                plaintextLength += plaintext.length;
                write(encryptedSource, destination, Buffer.buffer(plaintext), "secure " + direction);
            }
            if (decoder.isHeaderAccepted() && !encoder.isPeerBound()) {
                encoder.bindPeerHeader(decoder.acceptedHeader());
                cancelHandshakeTimer();
                plaintextSource.resume();
            }
            if (plaintextLength > 0) {
                logTransfer(direction, plaintextLength);
            }
            if (decoder.isCloseReceived()) {
                close();
            }
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("secure " + direction + " authentication failed; closing connection", error);
        }
    }

    private void forward(NetSocket source, NetSocket destination, Buffer buffer, String direction) {
        logTransfer(direction, buffer.length());
        write(source, destination, buffer, direction + " from " + source.remoteAddress());
    }

    private void write(NetSocket socket, Buffer buffer, String operation) {
        socket.write(buffer).onFailure(error -> fail(operation + " write failed", error));
    }

    private void write(NetSocket source, NetSocket destination, Buffer buffer, String operation) {
        destination.write(buffer).onFailure(error -> fail(operation + " write failed", error));
        if (destination.writeQueueFull()) {
            source.pause();
            destination.drainHandler(ignored -> {
                if (!closed) {
                    source.resume();
                }
            });
        }
    }

    private void logTransfer(String direction, int plaintextLength) {
        if (DothanConfig.getInstance().isVerbose()) {
            logger.info("Forwarded %s plaintext bytes in %s direction".formatted(plaintextLength, direction));
        }
    }

    private void fail(String message, Throwable error) {
        logger.error(message + ": " + error.getMessage(), error);
        close();
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelHandshakeTimer();
        clientSocket.close();
        serverSocket.close();
    }

    private void cancelHandshakeTimer() {
        if (handshakeTimerId >= 0) {
            vertx.cancelTimer(handshakeTimerId);
            handshakeTimerId = -1;
        }
    }
}
