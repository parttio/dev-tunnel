package org.vaadin.addons.devtunnel;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The one public tunnel of this JVM, turned on and off from the Dev Tunnel popover, and the number of browser tabs
 * open through it. Connecting happens in the background: the first time, it downloads cloudflared.
 */
public final class RemoteTunnel {

    public enum Status {
        OFF, DOWNLOADING, STARTING, ON, FAILED
    }

    /**
     * The status, the URL when {@code ON}, whether the URL's host can be found on the internet yet, the reason when
     * {@code FAILED}, and the open remote tabs.
     */
    public record State(Status status, String url, boolean dnsReady, String error, int remoteTabs) {
    }

    /** The steps of connecting; cloudflared's, or stubs in tests. */
    interface Connector {
        boolean needsDownload();

        void download() throws IOException;

        PublicUrl open(int port) throws IOException;

        /** Waits until the URL's host can be looked up on the internet, or until it gives up. */
        void awaitDns(String url) throws InterruptedException;
    }

    private static final RemoteTunnel INSTANCE = new RemoteTunnel(new Connector() {
        @Override
        public boolean needsDownload() {
            return Cloudflared.needsDownload();
        }

        @Override
        public void download() throws IOException {
            Cloudflared.binary();
        }

        @Override
        public PublicUrl open(int port) throws IOException {
            return PublicUrl.open(port);
        }

        @Override
        public void awaitDns(String url) throws InterruptedException {
            PublicDns.await(url);
        }
    }, task -> Thread.ofPlatform().daemon().name("dev-tunnel-connect").start(task));

    private volatile Connector connector;
    private final Executor executor;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private Status status = Status.OFF;
    private String error;
    private int remoteTabs;
    private PublicUrl tunnel;
    private boolean dnsReady;
    private int generation; // which enable() a finished step belongs to; disable() makes it stale

    RemoteTunnel(Connector connector, Executor executor) {
        this.connector = connector;
        this.executor = executor;
    }

    public static RemoteTunnel get() {
        return INSTANCE;
    }

    /** Replaces how the tunnel connects; for the test app, which runs without a network. */
    void useConnector(Connector connector) {
        this.connector = connector;
    }

    public synchronized State state() {
        return new State(status, tunnel != null ? tunnel.url() : null, dnsReady, error, remoteTabs);
    }

    /** Makes the given local port public, unless the tunnel is already on or on its way. */
    public void enable(int port) {
        int current;
        synchronized (this) {
            if (status != Status.OFF && status != Status.FAILED) {
                return;
            }
            current = ++generation;
            error = null;
            dnsReady = false;
            changeStatus(Status.STARTING);
        }
        executor.execute(() -> connect(port, current));
    }

    /** Closes the tunnel, or abandons a connection in progress. */
    public void disable() {
        PublicUrl closing;
        synchronized (this) {
            generation++;
            closing = tunnel;
            tunnel = null;
            dnsReady = false;
            error = null;
            changeStatus(Status.OFF);
        }
        if (closing != null) {
            closing.close();
        }
    }

    public synchronized void remoteTabOpened() {
        remoteTabs++;
        notifyListeners();
    }

    public synchronized void remoteTabClosed() {
        remoteTabs = Math.max(0, remoteTabs - 1);
        notifyListeners();
    }

    /** Calls the listener on every change, on whatever thread made it; returns how to stop. */
    public Runnable addListener(Consumer<State> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void connect(int port, int connection) {
        PublicUrl opened = null;
        try {
            if (connector.needsDownload()) {
                if (!step(connection, Status.DOWNLOADING)) {
                    return;
                }
                connector.download();
                if (!step(connection, Status.STARTING)) {
                    return;
                }
            }
            opened = connector.open(port);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                if (connection == generation) {
                    error = e.getMessage();
                    changeStatus(Status.FAILED);
                }
            }
            return;
        }
        synchronized (this) {
            if (connection != generation) {
                opened.close(); // turned off while connecting
                return;
            }
            tunnel = opened;
            changeStatus(Status.ON);
        }
        // Shown at once, with a warning until the URL works everywhere
        try {
            connector.awaitDns(opened.url());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (connection == generation) {
                dnsReady = true;
                notifyListeners();
            }
        }
    }

    /** Moves to the status, unless the connection was abandoned meanwhile. */
    private synchronized boolean step(int connection, Status next) {
        if (connection != generation) {
            return false;
        }
        changeStatus(next);
        return true;
    }

    // Under the lock, so that listeners get the changes in order
    private void changeStatus(Status next) {
        status = next;
        notifyListeners();
    }

    private void notifyListeners() {
        State state = state();
        listeners.forEach(listener -> listener.accept(state));
    }
}
