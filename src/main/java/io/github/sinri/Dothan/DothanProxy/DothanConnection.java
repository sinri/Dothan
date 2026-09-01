package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.github.sinri.Dothan.Security.SecureRecordCodec;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DothanConnection {
    private final NetSocket clientSocket;
    private final NetSocket serverSocket;
    private final Vertx vertx;
    private final DothanConfigSnapshot config;
    private final Consumer<Throwable> closeCallback;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Promise<Void> closeCompletion = Promise.promise();
    private long handshakeTimerId = -1;
    private FlowControlledDirection requestDirection;
    private FlowControlledDirection responseDirection;

    DothanConnection(Vertx vertx, NetSocket clientSocket, NetSocket serverSocket,
                     DothanConfigSnapshot config, Consumer<Throwable> closeCallback) {
        this.vertx = vertx;
        this.clientSocket = clientSocket;
        this.serverSocket = serverSocket;
        this.config = config;
        this.closeCallback = closeCallback;
    }

    void proxy() {
        serverSocket.closeHandler(ignored -> close());
        clientSocket.closeHandler(ignored -> close());
        serverSocket.exceptionHandler(error -> fail("service-provider socket failure", error));
        clientSocket.exceptionHandler(error -> fail("client socket failure", error));

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
        initializeDirections();
        clientSocket.handler(buffer -> forward(clientSocket, buffer, "request"));
        serverSocket.handler(buffer -> forward(serverSocket, buffer, "response"));
        requestDirection.activate();
        responseDirection.activate();
    }

    private void proxySecureRecords(DothanTransferModeEnum role, String transferKey)
            throws GeneralSecurityException {
        DothanTransferModeEnum peerRole = role == DothanTransferModeEnum.ENCRYPT
                ? DothanTransferModeEnum.DECRYPT
                : DothanTransferModeEnum.ENCRYPT;
        SecureRecordCodec.Encoder encoder = new SecureRecordCodec.Encoder(transferKey, role);
        SecureRecordCodec.Decoder decoder = new SecureRecordCodec.Decoder(transferKey, peerRole, encoder.header());
        initializeDirections();
        handshakeTimerId = vertx.setTimer(10_000, ignored -> {
            if (!decoder.isHeaderAccepted()) {
                fail("secure record peer handshake timed out", new SecureRecordCodec.ProtocolException("timeout"));
            }
        });

        if (role == DothanTransferModeEnum.ENCRYPT) {
            clientSocket.closeHandler(ignored -> plaintextInputClosed(encoder, serverSocket));
            serverSocket.closeHandler(ignored -> encryptedInputClosed(decoder));
            requestDirection.write(Buffer.buffer(encoder.header()), "secure request header");
            clientSocket.handler(buffer -> encodeAndForward(clientSocket, encoder, buffer, "request"));
            serverSocket.handler(buffer -> decodeAndForward(serverSocket, decoder, encoder,
                    clientSocket, buffer, "response"));
            responseDirection.activate();
        } else {
            serverSocket.closeHandler(ignored -> plaintextInputClosed(encoder, clientSocket));
            clientSocket.closeHandler(ignored -> encryptedInputClosed(decoder));
            responseDirection.write(Buffer.buffer(encoder.header()), "secure response header");
            clientSocket.handler(buffer -> decodeAndForward(clientSocket, decoder, encoder,
                    serverSocket, buffer, "request"));
            serverSocket.handler(buffer -> encodeAndForward(serverSocket, encoder, buffer, "response"));
            requestDirection.activate();
        }
    }

    private void initializeDirections() {
        requestDirection = new FlowControlledDirection(clientSocket, serverSocket, this::writeFailed);
        responseDirection = new FlowControlledDirection(serverSocket, clientSocket, this::writeFailed);
    }

    private void plaintextInputClosed(SecureRecordCodec.Encoder encoder, NetSocket encryptedDestination) {
        if (closed.get()) {
            return;
        }
        try {
            directionTo(encryptedDestination).writeAndThen(Buffer.buffer(encoder.closeRecord()),
                    "secure close record", this::close);
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("could not send secure close record", error);
        }
    }

    private void encryptedInputClosed(SecureRecordCodec.Decoder decoder) {
        if (closed.get()) {
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
                                  Buffer plaintext, String direction) {
        try {
            List<Buffer> records = new ArrayList<>();
            for (byte[] record : encoder.encode(plaintext.getBytes())) {
                records.add(Buffer.buffer(record));
            }
            directionFrom(source).write(records, "secure " + direction);
            logTransfer(direction, plaintext.length());
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("secure " + direction + " encryption failed", error);
        }
    }

    private void decodeAndForward(NetSocket encryptedSource, SecureRecordCodec.Decoder decoder,
                                  SecureRecordCodec.Encoder encoder, NetSocket plaintextSource,
                                  Buffer ciphertext, String direction) {
        try {
            int plaintextLength = 0;
            List<Buffer> plaintextRecords = new ArrayList<>();
            for (byte[] plaintext : decoder.accept(ciphertext.getBytes())) {
                plaintextLength += plaintext.length;
                plaintextRecords.add(Buffer.buffer(plaintext));
            }
            boolean closeReceived = decoder.isCloseReceived();
            if (!plaintextRecords.isEmpty()) {
                if (closeReceived) {
                    directionFrom(encryptedSource).writeAndThen(
                            plaintextRecords, "secure " + direction, this::close);
                } else {
                    directionFrom(encryptedSource).write(plaintextRecords, "secure " + direction);
                }
            }
            if (decoder.isHeaderAccepted() && !encoder.isPeerBound()) {
                encoder.bindPeerHeader(decoder.acceptedHeader());
                cancelHandshakeTimer();
                directionFrom(plaintextSource).activate();
            }
            if (plaintextLength > 0) {
                logTransfer(direction, plaintextLength);
            }
            if (closeReceived && plaintextRecords.isEmpty()) {
                close();
            }
        } catch (GeneralSecurityException | SecureRecordCodec.ProtocolException error) {
            fail("secure " + direction + " authentication failed; closing connection", error);
        }
    }

    private void forward(NetSocket source, Buffer buffer, String direction) {
        logTransfer(direction, buffer.length());
        directionFrom(source).write(buffer, direction + " from " + source.remoteAddress());
    }

    private FlowControlledDirection directionFrom(NetSocket source) {
        return source == clientSocket ? requestDirection : responseDirection;
    }

    private FlowControlledDirection directionTo(NetSocket destination) {
        return destination == serverSocket ? requestDirection : responseDirection;
    }

    private void writeFailed(String operation, Throwable error) {
        fail(operation + " write failed", error);
    }

    private void logTransfer(String direction, int plaintextLength) {
        if (config.isVerbose()) {
            logger.info("Forwarded %s plaintext bytes in %s direction".formatted(plaintextLength, direction));
        }
    }

    private void fail(String message, Throwable error) {
        logger.error("%s [%s <-> %s]: %s".formatted(
                message, clientSocket.remoteAddress(), serverSocket.remoteAddress(), error.getMessage()), error);
        close();
    }

    Future<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return closeCompletion.future();
        }
        cancelHandshakeTimer();
        if (requestDirection != null) {
            requestDirection.stop();
        }
        if (responseDirection != null) {
            responseDirection.stop();
        }
        clientSocket.handler(null);
        serverSocket.handler(null);
        clientSocket.closeHandler(null);
        serverSocket.closeHandler(null);
        Future.join(clientSocket.close(), serverSocket.close()).mapEmpty().onComplete(result -> {
            closeCallback.accept(result.cause());
            if (result.succeeded()) {
                closeCompletion.complete();
            } else {
                closeCompletion.fail(result.cause());
            }
        });
        return closeCompletion.future();
    }

    private void cancelHandshakeTimer() {
        if (handshakeTimerId >= 0) {
            vertx.cancelTimer(handshakeTimerId);
            handshakeTimerId = -1;
        }
    }
}
