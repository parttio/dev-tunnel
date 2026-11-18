package org.vaadin.addons.devtunnel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The pieces that turn cloudflared and an HTTP request into a tunnel. */
class TunnelPartsTest {

    @Test
    void findsTheTunnelUrlInCloudflaredOutput() {
        assertEquals(Optional.of("https://quiet-river-sample.trycloudflare.com"), PublicUrl.findUrl(
                "2026-11-17T18:00:00Z INF |  https://quiet-river-sample.trycloudflare.com                    |"));
        assertEquals(Optional.empty(), PublicUrl.findUrl(
                "2026-11-17T18:00:00Z INF Requesting new quick Tunnel on trycloudflare.com..."));
        assertEquals(Optional.empty(), PublicUrl.findUrl("ERR failed to request quick Tunnel: Post "
                + "\"https://api.trycloudflare.com/tunnel\": dial tcp: lookup api.trycloudflare.com: no such host"));
    }

    @Test
    void choosesTheReleaseAssetForTheMachine() {
        assertEquals("darwin-arm64.tgz", Cloudflared.asset("Mac OS X", "aarch64"));
        assertEquals("darwin-amd64.tgz", Cloudflared.asset("Mac OS X", "x86_64"));
        assertEquals("linux-arm64", Cloudflared.asset("Linux", "aarch64"));
        assertEquals("linux-amd64", Cloudflared.asset("Linux", "amd64"));
        assertEquals("windows-amd64.exe", Cloudflared.asset("Windows 11", "amd64"));
        assertThrows(UnsupportedOperationException.class, () -> Cloudflared.asset("Windows 11", "aarch64"));
        assertThrows(UnsupportedOperationException.class, () -> Cloudflared.asset("FreeBSD", "amd64"));
    }

    @Test
    void readsTheBinaryOutOfATarArchive() throws IOException {
        byte[] tar = tar("README", "hello".getBytes(StandardCharsets.UTF_8), "./cloudflared",
                "binary".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("binary".getBytes(StandardCharsets.UTF_8),
                Cloudflared.tarEntry(new ByteArrayInputStream(tar), "cloudflared"));
        assertThrows(IOException.class, () -> Cloudflared.tarEntry(new ByteArrayInputStream(tar), "missing"));
    }

    @Test
    void rejectsABinaryWithTheWrongChecksum() {
        assertThrows(IOException.class,
                () -> Cloudflared.verify("not cloudflared".getBytes(StandardCharsets.UTF_8), "linux-arm64"));
    }

    @Test
    void onlyRequestsFromThisMachineAreLocal() {
        assertTrue(DevTunnelInitListener.isLocal("127.0.0.1", null, null, null));
        assertTrue(DevTunnelInitListener.isLocal("0:0:0:0:0:0:0:1", null, null, null));
        assertTrue(DevTunnelInitListener.isLocal("::1", null, null, null));
        // The tunnel and proxies connect from localhost, but say whom for
        assertFalse(DevTunnelInitListener.isLocal("127.0.0.1", "203.0.113.7", null, null));
        assertFalse(DevTunnelInitListener.isLocal("127.0.0.1", null, "203.0.113.7", null));
        assertFalse(DevTunnelInitListener.isLocal("127.0.0.1", null, null, "for=203.0.113.7"));
        assertFalse(DevTunnelInitListener.isLocal("192.168.1.20", null, null, null));
        assertFalse(DevTunnelInitListener.isLocal(null, null, null, null));
    }


    /** A minimal ustar archive with the given name and content pairs. */
    private static byte[] tar(Object... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < entries.length; i += 2) {
            byte[] content = (byte[]) entries[i + 1];
            byte[] header = new byte[512];
            byte[] name = ((String) entries[i]).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, name.length);
            byte[] size = String.format("%011o", content.length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, header, 124, size.length);
            out.write(header);
            out.write(content);
            out.write(new byte[(512 - content.length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }
}
