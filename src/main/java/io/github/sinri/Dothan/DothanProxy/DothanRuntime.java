package io.github.sinri.Dothan.DothanProxy;

import io.github.sinri.Dothan.Config.DothanConfigManager;
import io.github.sinri.Dothan.Config.DothanConfigSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.logging.Logger;
import io.vertx.core.internal.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates listener generations and publishes configuration only after preparation succeeds.
 */
public final class DothanRuntime {
    interface ManagedListener {
        int port();

        Future<Void> start();

        Future<Void> retire();

        Future<Void> closeNow();
    }

    @FunctionalInterface
    interface ListenerFactory {
        ManagedListener create(int port);
    }

    private final DothanConfigManager configManager;
    private final ListenerFactory listenerFactory;
    private final Map<Integer, ManagedListener> activeListeners = new ConcurrentHashMap<>();
    private final Set<ManagedListener> allListeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean transitionInProgress = new AtomicBoolean();
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public DothanRuntime(Vertx vertx, DothanConfigManager configManager) {
        this(configManager, port -> new DothanListener(vertx, configManager, port));
    }

    DothanRuntime(DothanConfigManager configManager, ListenerFactory listenerFactory) {
        this.configManager = configManager;
        this.listenerFactory = listenerFactory;
    }

    public Future<DothanConfigSnapshot> start(DothanConfigSnapshot initial) {
        if (!transitionInProgress.compareAndSet(false, true)) {
            return Future.failedFuture("a configuration transition is already in progress");
        }
        if (configManager.current() != null) {
            transitionInProgress.set(false);
            return Future.failedFuture("runtime is already started");
        }
        List<ManagedListener> prepared = new ArrayList<>();
        return startPorts(initial.getListenPorts(), prepared)
                .compose(ignored -> {
                    if (!configManager.initialize(initial)) {
                        return Future.failedFuture("initial configuration publication failed");
                    }
                    prepared.forEach(listener -> activeListeners.put(listener.port(), listener));
                    return Future.succeededFuture(initial);
                })
                .recover(error -> rollbackPrepared(prepared, error))
                .onSuccess(snapshot -> logger.info("Started config version %d on ports %s"
                        .formatted(snapshot.getVersion(), snapshot.getListenPorts())))
                .andThen(ignored -> transitionInProgress.set(false));
    }

    public Future<DothanConfigSnapshot> reload(DothanConfigSnapshot candidate) {
        if (!transitionInProgress.compareAndSet(false, true)) {
            return Future.failedFuture("a configuration transition is already in progress");
        }
        DothanConfigSnapshot previous = configManager.current();
        if (previous == null) {
            transitionInProgress.set(false);
            return Future.failedFuture("runtime is not started");
        }
        if (candidate.getVersion() <= previous.getVersion()) {
            transitionInProgress.set(false);
            return Future.failedFuture("candidate version %d is not newer than active version %d"
                    .formatted(candidate.getVersion(), previous.getVersion()));
        }

        Set<Integer> addedPorts = new HashSet<>(candidate.getListenPorts());
        addedPorts.removeAll(activeListeners.keySet());
        List<ManagedListener> prepared = new ArrayList<>();
        return startPorts(addedPorts, prepared)
                .compose(ignored -> publish(previous, candidate, prepared))
                .recover(error -> rollbackPrepared(prepared, error))
                .andThen(ignored -> transitionInProgress.set(false));
    }

    public Future<Void> close() {
        List<Future<Void>> closures = allListeners.stream().map(this::safeClose).toList();
        return Future.join(closures).mapEmpty();
    }

    private Future<DothanConfigSnapshot> publish(DothanConfigSnapshot previous,
                                                  DothanConfigSnapshot candidate,
                                                  List<ManagedListener> prepared) {
        prepared.forEach(listener -> activeListeners.put(listener.port(), listener));
        if (!configManager.publish(previous, candidate)) {
            prepared.forEach(listener -> activeListeners.remove(listener.port(), listener));
            return Future.failedFuture("atomic configuration publication failed");
        }

        Set<Integer> removedPorts = new HashSet<>(previous.getListenPorts());
        removedPorts.removeAll(candidate.getListenPorts());
        for (int port : removedPorts) {
            ManagedListener listener = activeListeners.remove(port);
            if (listener != null) {
                try {
                    listener.retire().onComplete(result -> {
                        if (result.succeeded()) {
                            allListeners.remove(listener);
                        } else {
                            logRetirementFailure(port, result.cause());
                        }
                    });
                } catch (RuntimeException error) {
                    logRetirementFailure(port, error);
                }
            }
        }
        logger.info("Atomically published config version %d on ports %s"
                .formatted(candidate.getVersion(), candidate.getListenPorts()));
        return Future.succeededFuture(candidate);
    }

    private Future<Void> startPorts(Set<Integer> ports, List<ManagedListener> prepared) {
        Future<Void> chain = Future.succeededFuture();
        for (int port : ports) {
            chain = chain.compose(ignored -> {
                ManagedListener listener = listenerFactory.create(port);
                allListeners.add(listener);
                prepared.add(listener);
                return listener.start();
            });
        }
        return chain;
    }

    private <T> Future<T> rollbackPrepared(List<ManagedListener> prepared, Throwable primaryError) {
        for (ManagedListener listener : prepared) {
            activeListeners.remove(listener.port(), listener);
        }
        if (prepared.isEmpty()) {
            return Future.failedFuture(primaryError);
        }
        List<Future<Void>> rollbacks = prepared.stream().map(this::safeClose).toList();
        return Future.join(rollbacks)
                .compose(ignored -> {
                    allListeners.removeAll(prepared);
                    return Future.failedFuture(primaryError);
                }, rollbackError -> {
                    primaryError.addSuppressed(rollbackError);
                    return Future.failedFuture(primaryError);
                });
    }

    private Future<Void> safeClose(ManagedListener listener) {
        try {
            return listener.closeNow();
        } catch (RuntimeException error) {
            return Future.failedFuture(error);
        }
    }

    private void logRetirementFailure(int port, Throwable error) {
        logger.error("Retired listener %d failed to close: %s"
                .formatted(port, error.getMessage()), error);
    }
}
