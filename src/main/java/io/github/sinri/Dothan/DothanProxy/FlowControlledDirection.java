package io.github.sinri.Dothan.DothanProxy;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Owns the flow-control state for one direction of a proxied connection.
 */
final class FlowControlledDirection {
    static final int WRITE_QUEUE_MAX_SIZE = 64 * 1024;
    static final int MAX_PENDING_BYTES = 1024 * 1024;
    static final int MAX_WRITE_CHUNK_SIZE = 16 * 1024;

    private final NetSocket source;
    private final NetSocket destination;
    private final BiConsumer<String, Throwable> failureHandler;
    private final Deque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private boolean active;
    private boolean backpressured;
    private boolean stopped;
    private int pendingBytes;

    FlowControlledDirection(NetSocket source, NetSocket destination,
                            BiConsumer<String, Throwable> failureHandler) {
        this.source = source;
        this.destination = destination;
        this.failureHandler = failureHandler;
        source.pause();
        destination.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
        destination.drainHandler(ignored -> drain());
    }

    void activate() {
        if (stopped || active) {
            return;
        }
        active = true;
        updateSourceState();
    }

    void write(Buffer buffer, String operation) {
        write(List.of(buffer), operation, null);
    }

    void write(List<Buffer> buffers, String operation) {
        write(buffers, operation, null);
    }

    void writeAndThen(Buffer buffer, String operation, Runnable completionHandler) {
        write(List.of(buffer), operation, completionHandler);
    }

    void writeAndThen(List<Buffer> buffers, String operation, Runnable completionHandler) {
        write(buffers, operation, completionHandler);
    }

    private void write(List<Buffer> buffers, String operation, Runnable completionHandler) {
        if (stopped) {
            return;
        }
        int lastNonEmptyBuffer = -1;
        for (int i = 0; i < buffers.size(); i++) {
            if (buffers.get(i).length() > 0) {
                lastNonEmptyBuffer = i;
            }
        }
        if (lastNonEmptyBuffer < 0) {
            if (completionHandler != null) {
                completionHandler.run();
            }
            return;
        }

        for (int bufferIndex = 0; bufferIndex < buffers.size() && !stopped; bufferIndex++) {
            Buffer buffer = buffers.get(bufferIndex);
            for (int offset = 0; offset < buffer.length() && !stopped; offset += MAX_WRITE_CHUNK_SIZE) {
                int end = Math.min(buffer.length(), offset + MAX_WRITE_CHUNK_SIZE);
                boolean lastChunk = bufferIndex == lastNonEmptyBuffer && end == buffer.length();
                offer(new PendingWrite(buffer.slice(offset, end), operation,
                        lastChunk ? completionHandler : null));
            }
        }
    }

    private void offer(PendingWrite pendingWrite) {
        if (pendingWrites.isEmpty() && !destination.writeQueueFull()) {
            submit(pendingWrite);
            if (destination.writeQueueFull()) {
                applyBackpressure();
            }
            return;
        }

        if (pendingBytes + pendingWrite.buffer().length() > MAX_PENDING_BYTES) {
            fail(pendingWrite.operation(), new IllegalStateException(
                    "bounded proxy write buffer exceeded " + MAX_PENDING_BYTES + " bytes"));
            return;
        }
        pendingWrites.addLast(pendingWrite);
        pendingBytes += pendingWrite.buffer().length();
        applyBackpressure();
    }

    private void submit(PendingWrite pendingWrite) {
        Future<Void> write = destination.write(pendingWrite.buffer());
        write.onSuccess(ignored -> {
            if (!stopped && pendingWrite.completionHandler() != null) {
                pendingWrite.completionHandler().run();
            }
        });
        write.onFailure(error -> fail(pendingWrite.operation(), error));
    }

    private void drain() {
        if (stopped) {
            return;
        }
        while (!pendingWrites.isEmpty() && !destination.writeQueueFull()) {
            PendingWrite pendingWrite = pendingWrites.removeFirst();
            pendingBytes -= pendingWrite.buffer().length();
            submit(pendingWrite);
        }
        backpressured = !pendingWrites.isEmpty() || destination.writeQueueFull();
        updateSourceState();
    }

    private void applyBackpressure() {
        if (!backpressured) {
            backpressured = true;
            source.pause();
        }
    }

    private void updateSourceState() {
        if (active && !backpressured && !stopped) {
            source.resume();
        } else {
            source.pause();
        }
    }

    private void fail(String operation, Throwable error) {
        if (stopped) {
            return;
        }
        stop();
        failureHandler.accept(operation, error);
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        source.pause();
        destination.drainHandler(null);
        pendingWrites.clear();
        pendingBytes = 0;
    }

    int pendingBytes() {
        return pendingBytes;
    }

    boolean isBackpressured() {
        return backpressured;
    }

    private record PendingWrite(Buffer buffer, String operation, Runnable completionHandler) {
    }
}
