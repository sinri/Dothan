package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigParser;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesInitialSnapshotOnlyAfterEveryListenerStarts() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        FakeListenerFactory factory = new FakeListenerFactory();
        Promise<Void> delayedStart = Promise.promise();
        factory.startResults.put(20001, delayedStart.future());
        DothanRuntime runtime = new DothanRuntime(manager, factory);
        DothanConfigSnapshot initial = snapshot(1, "20001:old.example:3306");

        Future<DothanConfigSnapshot> start = runtime.start(initial);
        assertNull(manager.current());
        assertFalse(start.isComplete());

        delayedStart.complete();
        assertSame(initial, start.await(5, TimeUnit.SECONDS));
        assertSame(initial, manager.current());
    }

    @Test
    void rejectsOldVersionsAndKeepsExistingConnectionSnapshot() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        FakeListenerFactory factory = new FakeListenerFactory();
        DothanRuntime runtime = new DothanRuntime(manager, factory);
        DothanConfigSnapshot initial = snapshot(5, "20001:old.example:3306");
        runtime.start(initial).await(5, TimeUnit.SECONDS);

        DothanConfigSnapshot connectionSnapshot = manager.current();
        assertThrows(Exception.class,
                () -> runtime.reload(snapshot(5, "20001:ignored.example:3306"))
                        .await(5, TimeUnit.SECONDS));
        assertSame(initial, manager.current());

        DothanConfigSnapshot replacement = snapshot(6, "20001:new.example:3307");
        runtime.reload(replacement).await(5, TimeUnit.SECONDS);
        assertSame(replacement, manager.current());
        assertNotSame(manager.current(), connectionSnapshot);
        assertEquals("old.example", connectionSnapshot.findRequirement(20001).orElseThrow().serverHost);
        assertEquals("new.example", manager.current().findRequirement(20001).orElseThrow().serverHost);
        assertEquals(1, factory.createdCount(20001), "an unchanged port must reuse its listener");
    }

    @Test
    void failedAddedListenerIsClosedAndCannotPublishCandidate() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        FakeListenerFactory factory = new FakeListenerFactory();
        DothanRuntime runtime = new DothanRuntime(manager, factory);
        DothanConfigSnapshot initial = snapshot(1, "20001:old.example:3306");
        runtime.start(initial).await(5, TimeUnit.SECONDS);
        factory.startResults.put(20002, Future.failedFuture("port is occupied"));

        assertThrows(Exception.class,
                () -> runtime.reload(snapshot(2, "20001:old.example:3306\n20002:new.example:3307"))
                        .await(5, TimeUnit.SECONDS));

        assertSame(initial, manager.current());
        assertTrue(factory.latest(20002).closedNow);
        assertFalse(factory.latest(20001).closedNow);
    }

    @Test
    void removedListenerDrainsAfterNewSnapshotIsPublished() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        FakeListenerFactory factory = new FakeListenerFactory();
        DothanRuntime runtime = new DothanRuntime(manager, factory);
        DothanConfigSnapshot initial = snapshot(1, "20001:old.example:3306");
        runtime.start(initial).await(5, TimeUnit.SECONDS);
        FakeListener oldListener = factory.latest(20001);
        Promise<Void> draining = Promise.promise();
        oldListener.retireResult = draining.future();

        DothanConfigSnapshot replacement = snapshot(2, "20002:new.example:3307");
        assertSame(replacement, runtime.reload(replacement).await(5, TimeUnit.SECONDS));
        assertSame(replacement, manager.current());
        assertTrue(oldListener.retired);
        assertFalse(draining.future().isComplete(), "publication must not wait for old connections to drain");

        draining.complete();
    }

    @Test
    void concurrentTransitionIsRejectedAndCloseFailuresAreReported() throws Exception {
        DothanConfigManager manager = new DothanConfigManager();
        FakeListenerFactory factory = new FakeListenerFactory();
        DothanRuntime runtime = new DothanRuntime(manager, factory);
        runtime.start(snapshot(1, "20001:old.example:3306")).await(5, TimeUnit.SECONDS);
        Promise<Void> delayedStart = Promise.promise();
        factory.startResults.put(20002, delayedStart.future());

        Future<DothanConfigSnapshot> transition = runtime.reload(snapshot(2, "20002:new.example:3307"));
        assertThrows(Exception.class,
                () -> runtime.reload(snapshot(3, "20003:newer.example:3308"))
                        .await(5, TimeUnit.SECONDS));
        assertEquals(1, manager.current().getVersion());

        delayedStart.complete();
        transition.await(5, TimeUnit.SECONDS);
        factory.latest(20002).closeResult = Future.failedFuture("close failed");
        assertThrows(Exception.class, () -> runtime.close().await(5, TimeUnit.SECONDS));
    }

    private DothanConfigSnapshot snapshot(int version, String routes) throws Exception {
        Path path = temporaryDirectory.resolve("runtime-" + version + "-" + System.nanoTime() + ".config");
        Files.writeString(path, "# Dothan Config Version " + version + "\n" + routes + "\n");
        return DothanConfigParser.parse(path, false);
    }

    private static final class FakeListenerFactory implements DothanRuntime.ListenerFactory {
        private final Map<Integer, Future<Void>> startResults = new ConcurrentHashMap<>();
        private final Map<Integer, FakeListener> latest = new ConcurrentHashMap<>();
        private final Map<Integer, Integer> created = new ConcurrentHashMap<>();

        @Override
        public DothanRuntime.ManagedListener create(int port) {
            FakeListener listener = new FakeListener(port, startResults.getOrDefault(port, Future.succeededFuture()));
            latest.put(port, listener);
            created.merge(port, 1, Integer::sum);
            return listener;
        }

        private FakeListener latest(int port) {
            return latest.get(port);
        }

        private int createdCount(int port) {
            return created.getOrDefault(port, 0);
        }
    }

    private static final class FakeListener implements DothanRuntime.ManagedListener {
        private final int port;
        private final Future<Void> startResult;
        private Future<Void> retireResult = Future.succeededFuture();
        private Future<Void> closeResult = Future.succeededFuture();
        private boolean retired;
        private boolean closedNow;

        private FakeListener(int port, Future<Void> startResult) {
            this.port = port;
            this.startResult = startResult;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public Future<Void> start() {
            return startResult;
        }

        @Override
        public Future<Void> retire() {
            retired = true;
            return retireResult;
        }

        @Override
        public Future<Void> closeNow() {
            closedNow = true;
            return closeResult;
        }
    }
}
