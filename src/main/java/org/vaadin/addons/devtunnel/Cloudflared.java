package org.vaadin.addons.devtunnel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Provides the cloudflared binary: the one set with the system property {@code dev.tunnel.cloudflared}, else the
 * one on {@code PATH}, else a pinned release downloaded once from GitHub into {@code ~/.cache/dev-tunnel} and
 * checked against Cloudflare's published SHA-256. The download goes through the JVM's proxy settings.
 */
final class Cloudflared {

    static final String VERSION = "2026.10.0";

    /** A cloudflared to use instead of finding or downloading one, for machines that cannot download. */
    static final String PATH_PROPERTY = "dev.tunnel.cloudflared";

    private static final String DOWNLOAD = "https://github.com/cloudflare/cloudflared/releases/download/"
            + VERSION + "/cloudflared-";

    /** SHA-256 of the binary, as published in the release notes; for macOS, of the binary inside the .tgz. */
    private static final Map<String, String> SHA256 = Map.of(
            "linux-amd64", "d33ff2d14475178d2012c2c56beba87389ac5ded27649519f198a7d3134a99db",
            "linux-arm64", "e6422b9d4f72d3194bc5a38676f13667c06666523217b842a877d72a80b5ac08",
            "windows-amd64.exe", "86aee4017b26625cee8484c113558f48effa4cd47f7aa05fcf425604e5d2b23c",
            "darwin-amd64.tgz", "0560c9ab7281ac3f746055323623ed23bc0405b6dab9400474020cba33a978da",
            "darwin-arm64.tgz", "72edfd3eea463aef4d5cb89e2e209cecb048cc756c2b01915de2e0ad7cb39830");

    private Cloudflared() {
    }

    static Path binary() throws IOException {
        String configured = System.getProperty(PATH_PROPERTY);
        if (configured != null) {
            Path path = Path.of(configured);
            if (!Files.isExecutable(path)) {
                throw new IOException(PATH_PROPERTY + " is " + configured + ", which is not an executable file");
            }
            return path;
        }
        Path onPath = findOnPath();
        if (onPath != null) {
            return onPath;
        }
        Path cached = cached();
        if (!Files.isExecutable(cached)) {
            download(asset(), cached);
        }
        return cached;
    }

    /** Whether {@link #binary()} has to download cloudflared first. */
    static boolean needsDownload() {
        return System.getProperty(PATH_PROPERTY) == null && findOnPath() == null && !Files.isExecutable(cached());
    }

    private static String asset() {
        return asset(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private static Path cached() {
        return Path.of(System.getProperty("user.home"), ".cache", "dev-tunnel",
                "cloudflared-" + VERSION + "-" + asset().replace(".tgz", ""));
    }

    /** The release asset for an OS and architecture, as in {@code os.name} and {@code os.arch}. */
    static String asset(String osName, String osArch) {
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        String os = osName.toLowerCase(Locale.ROOT);
        String asset = os.startsWith("mac") ? "darwin-" + arch + ".tgz"
                : os.startsWith("linux") ? "linux-" + arch
                : os.startsWith("windows") ? "windows-" + arch + ".exe"
                : null;
        if (arch == null || asset == null || !SHA256.containsKey(asset)) {
            throw new UnsupportedOperationException("No cloudflared download for " + osName + " " + osArch
                    + "; install cloudflared on the PATH instead");
        }
        return asset;
    }

    private static Path findOnPath() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "cloudflared.exe" : "cloudflared";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return Path.of(dir, name);
            }
        }
        return null;
    }

    private static void download(String asset, Path target) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault()).build();
        byte[] binary;
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(DOWNLOAD + asset)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Downloading cloudflared failed: HTTP " + response.statusCode());
                }
                binary = asset.endsWith(".tgz") ? tarEntry(new GZIPInputStream(body), "cloudflared") : body.readAllBytes();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Downloading cloudflared was interrupted", e);
        }
        verify(binary, asset);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "cloudflared", ".part");
        Files.write(temp, binary);
        if (!temp.toFile().setExecutable(true)) {
            throw new IOException("Cannot make " + temp + " executable");
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Throws unless the binary has the published checksum of the asset. */
    static void verify(byte[] binary, String asset) throws IOException {
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (!actual.equals(SHA256.get(asset))) {
            throw new IOException("The downloaded cloudflared (" + asset + ") has SHA-256 " + actual
                    + ", expected " + SHA256.get(asset));
        }
    }

    /** The content of the file with the given name in a tar stream, in any directory. */
    static byte[] tarEntry(InputStream tar, String name) throws IOException {
        byte[] header = new byte[512];
        while (tar.readNBytes(header, 0, 512) == 512 && header[0] != 0) {
            String path = field(header, 0, 100);
            long size = Long.parseLong(field(header, 124, 12).trim(), 8);
            if (path.equals(name) || path.endsWith("/" + name)) {
                return tar.readNBytes((int) size);
            }
            tar.skipNBytes((size + 511) / 512 * 512);
        }
        throw new IOException("No " + name + " in the archive");
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
