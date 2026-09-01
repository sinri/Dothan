package io.github.sinri.Dothan.DothanProxy;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlledDirectionTest {
    @Test
    void pausesAtTheWriteThresholdAndDrainsQueuedChunksInOrder() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.becomeFullAfterWrites = 1;
        List<Throwable> failures = new ArrayList<>();
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket, (operation, error) -> failures.add(error));
        byte[] payload = bytes(FlowControlledDirection.MAX_WRITE_CHUNK_SIZE * 3, 17);

        direction.activate();
        direction.write(Buffer.buffer(payload), "request");

        assertTrue(source.paused);
        assertTrue(direction.isBackpressured());
        assertEquals(FlowControlledDirection.MAX_WRITE_CHUNK_SIZE * 2, direction.pendingBytes());
        assertEquals(1, destination.writes.size());
        assertEquals(FlowControlledDirection.WRITE_QUEUE_MAX_SIZE, destination.writeQueueMaxSize);

        destination.full = false;
        destination.becomeFullAfterWrites = Integer.MAX_VALUE;
        destination.fireDrain();

        assertFalse(source.paused);
        assertFalse(direction.isBackpressured());
        assertEquals(0, direction.pendingBytes());
        assertEquals(3, destination.writes.size());
        assertArrayEquals(payload, destination.writtenBytes());
        assertTrue(failures.isEmpty());
    }

    @Test
    void drainCannotBypassHandshakeGate() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.full = true;
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket, (operation, error) -> {
                    throw new AssertionError(error);
                });

        direction.write(Buffer.buffer("header"), "secure header");
        destination.full = false;
        destination.fireDrain();

        assertTrue(source.paused, "drain must not activate a handshake-gated source");
        direction.activate();
        assertFalse(source.paused);
        assertEquals("header", destination.writes.get(0).toString());
    }

    @Test
    void boundsPendingMemoryAndReportsOverflowOnlyOnce() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.full = true;
        AtomicInteger failures = new AtomicInteger();
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket, (operation, error) -> failures.incrementAndGet());

        direction.activate();
        direction.write(Buffer.buffer(new byte[
                FlowControlledDirection.MAX_PENDING_BYTES + FlowControlledDirection.MAX_WRITE_CHUNK_SIZE]),
                "oversized burst");
        direction.write(Buffer.buffer("ignored after failure"), "second write");

        assertEquals(1, failures.get());
        assertEquals(0, direction.pendingBytes());
        assertTrue(source.paused);
        assertTrue(destination.writes.isEmpty());
    }

    @Test
    void propagatesWriteFailureOnceAndStopsFurtherWrites() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.writeFailure = new IllegalStateException("socket closed");
        List<String> failedOperations = new ArrayList<>();
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket,
                (operation, error) -> failedOperations.add(operation + ": " + error.getMessage()));

        direction.activate();
        direction.write(Buffer.buffer("first"), "request");
        direction.write(Buffer.buffer("second"), "request again");

        assertEquals(List.of("request: socket closed"), failedOperations);
        assertEquals(1, destination.writes.size());
        assertTrue(source.paused);
    }

    @Test
    void invokesCompletionOnlyAfterTheFinalQueuedWriteIsAccepted() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.full = true;
        AtomicInteger completions = new AtomicInteger();
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket, (operation, error) -> {
                    throw new AssertionError(error);
                });

        direction.writeAndThen(Buffer.buffer("close-record"), "secure close", completions::incrementAndGet);
        assertEquals(0, completions.get());

        destination.full = false;
        destination.fireDrain();
        assertEquals(1, completions.get());
    }

    @Test
    void slowConnectionDoesNotPauseOrConsumeTheBudgetOfAnotherConnection() {
        FakeSocket slowSource = new FakeSocket();
        FakeSocket slowDestination = new FakeSocket();
        slowDestination.full = true;
        FlowControlledDirection slow = new FlowControlledDirection(
                slowSource.socket, slowDestination.socket, (operation, error) -> {
                    throw new AssertionError(error);
                });
        FakeSocket fastSource = new FakeSocket();
        FakeSocket fastDestination = new FakeSocket();
        FlowControlledDirection fast = new FlowControlledDirection(
                fastSource.socket, fastDestination.socket, (operation, error) -> {
                    throw new AssertionError(error);
                });

        slow.activate();
        fast.activate();
        slow.write(Buffer.buffer(new byte[FlowControlledDirection.MAX_WRITE_CHUNK_SIZE * 4]), "slow");
        fast.write(Buffer.buffer("unrelated traffic"), "fast");

        assertTrue(slowSource.paused);
        assertEquals(FlowControlledDirection.MAX_WRITE_CHUNK_SIZE * 4, slow.pendingBytes());
        assertFalse(fastSource.paused);
        assertEquals(0, fast.pendingBytes());
        assertEquals("unrelated traffic", fastDestination.writes.get(0).toString());
    }

    @Test
    void stopRemovesDrainHandlerAndReleasesPendingBuffers() {
        FakeSocket source = new FakeSocket();
        FakeSocket destination = new FakeSocket();
        destination.full = true;
        FlowControlledDirection direction = new FlowControlledDirection(
                source.socket, destination.socket, (operation, error) -> {
                    throw new AssertionError(error);
                });
        direction.activate();
        direction.write(Buffer.buffer(new byte[FlowControlledDirection.MAX_WRITE_CHUNK_SIZE]), "queued");

        direction.stop();

        assertTrue(source.paused);
        assertEquals(0, direction.pendingBytes());
        assertEquals(null, destination.drainHandler);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }

    private static final class FakeSocket implements InvocationHandler {
        private final NetSocket socket = (NetSocket) Proxy.newProxyInstance(
                NetSocket.class.getClassLoader(), new Class<?>[]{NetSocket.class}, this);
        private final List<Buffer> writes = new ArrayList<>();
        private Handler<Void> drainHandler;
        private Throwable writeFailure;
        private boolean paused;
        private boolean full;
        private int becomeFullAfterWrites = Integer.MAX_VALUE;
        private int writeQueueMaxSize;

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "pause" -> {
                    paused = true;
                    yield socket;
                }
                case "resume" -> {
                    paused = false;
                    yield socket;
                }
                case "setWriteQueueMaxSize" -> {
                    writeQueueMaxSize = (int) arguments[0];
                    yield socket;
                }
                case "writeQueueFull" -> full;
                case "drainHandler" -> {
                    @SuppressWarnings("unchecked")
                    Handler<Void> handler = (Handler<Void>) arguments[0];
                    drainHandler = handler;
                    yield socket;
                }
                case "write" -> {
                    Buffer buffer = ((Buffer) arguments[0]).copy();
                    writes.add(buffer);
                    if (writes.size() >= becomeFullAfterWrites) {
                        full = true;
                    }
                    yield writeFailure == null
                            ? Future.succeededFuture()
                            : Future.failedFuture(writeFailure);
                }
                case "toString" -> "FakeSocket";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private void fireDrain() {
            drainHandler.handle(null);
        }

        private byte[] writtenBytes() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Buffer write : writes) {
                output.writeBytes(write.getBytes());
            }
            return output.toByteArray();
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            if (type == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
