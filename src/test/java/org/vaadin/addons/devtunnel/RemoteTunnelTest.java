package org.vaadin.addons.devtunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RemoteTunnelTest {

    private final List<Runnable> pendingConnections = new ArrayList<>();
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicInteger downloads = new AtomicInteger();
    private boolean cloudflaredMissing;
    private IOException failure;

    private final RemoteTunnel tunnel = new RemoteTunnel(new RemoteTunnel.Connector() {
        @Override
        public boolean needsDownload() {
            return cloudflaredMissing;
        }

        @Override
        public void download() {
            downloads.incrementAndGet();
            cloudflaredMissing = false;
        }

        @Override
        public PublicUrl open(int port) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return new PublicUrl("https://a-b.trycloudflare.com", closed::incrementAndGet);
        }

        @Override
        public void awaitDns(String url) {
        }
    }, pendingConnections::add);

    private final List<RemoteTunnel.Status> seen = new ArrayList<>();
    private final List<RemoteTunnel.State> states = new ArrayList<>();

    {
        tunnel.addListener(state -> {
            if (seen.isEmpty() || seen.getLast() != state.status()) {
                seen.add(state.status());
            }
            states.add(state);
        });
    }

    @Test
    void firstTimeSaysItDownloadsThenStartsTheTunnel() {
        cloudflaredMissing = true;

        connect();

        assertEquals(List.of(RemoteTunnel.Status.STARTING, RemoteTunnel.Status.DOWNLOADING,
                RemoteTunnel.Status.STARTING, RemoteTunnel.Status.ON), seen);
        assertEquals("https://a-b.trycloudflare.com", tunnel.state().url());
    }

    @Test
    void laterTimesOnlySayItStartsTheTunnel() {
        connect();

        assertEquals(List.of(RemoteTunnel.Status.STARTING, RemoteTunnel.Status.ON), seen);
        assertEquals(0, downloads.get());
    }

    @Test
    void showsTheUrlAtOnceAndSaysWhenItWorksEverywhere() {
        connect();

        RemoteTunnel.State on = states.stream().filter(state -> state.status() == RemoteTunnel.Status.ON)
                .findFirst().orElseThrow();
        assertEquals("https://a-b.trycloudflare.com", on.url());
        assertFalse(on.dnsReady());
        assertTrue(tunnel.state().dnsReady());
    }

    @Test
    void enablingTwiceOpensOneTunnel() {
        tunnel.enable(8080);
        tunnel.enable(8080);

        assertEquals(1, pendingConnections.size());
    }

    @Test
    void aTunnelThatConnectsAfterDisablingIsClosed() {
        tunnel.enable(8080);
        tunnel.disable();

        pendingConnections.getFirst().run();

        assertEquals(RemoteTunnel.Status.OFF, tunnel.state().status());
        assertEquals(1, closed.get());
    }

    @Test
    void disablingClosesTheTunnel() {
        connect();

        tunnel.disable();

        assertEquals(RemoteTunnel.Status.OFF, tunnel.state().status());
        assertEquals(1, closed.get());
    }

    @Test
    void aFailureSaysWhyAndCanBeRetried() {
        failure = new IOException("no network");
        connect();
        assertEquals(new RemoteTunnel.State(RemoteTunnel.Status.FAILED, null, false, "no network", 0), tunnel.state());

        failure = null;
        connect();
        assertEquals(RemoteTunnel.Status.ON, tunnel.state().status());
    }

    @Test
    void countsTheOpenRemoteTabs() {
        connect();

        tunnel.remoteTabOpened();
        tunnel.remoteTabOpened();
        tunnel.remoteTabClosed();

        assertEquals(1, tunnel.state().remoteTabs());
    }

    private void connect() {
        tunnel.enable(8080);
        pendingConnections.removeFirst().run();
    }
}
