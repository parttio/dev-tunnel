package org.vaadin.addons.devtunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A public HTTPS URL for a local port: a Cloudflare quick tunnel, run by cloudflared as a child process.
 * Anyone with the URL can reach the port. Closed with {@link #close()}, or when the JVM shuts down.
 *
 * <pre>{@code
 * try (PublicUrl url = PublicUrl.open(8080)) {
 *     System.out.println(url.url());
 *     ...
 * }
 * }</pre>
 */
public final class PublicUrl implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PublicUrl.class.getName());

    /** A quick tunnel's URL; not the API host that cloudflared also mentions in errors. */
    private static final Pattern URL = Pattern.compile("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com");

    private final String url;
    private final Runnable closer;
    private final AtomicBoolean closed = new AtomicBoolean();

    PublicUrl(String url, Runnable closer) {
        this.url = url;
        this.closer = closer;
    }

    /** Opens a tunnel to {@code http://localhost:<port>}, waiting up to a minute for it to connect. */
    public static PublicUrl open(int port) throws IOException {
        return open(port, Duration.ofSeconds(60));
    }

    /**
     * Opens a tunnel to {@code http://localhost:<port>}. The first time, this downloads cloudflared (about
     * 40 MB) unless it is on the {@code PATH}.
     *
     * @throws IOException
     *             if cloudflared cannot be provided, or the tunnel does not connect within the timeout
     */
    public static PublicUrl open(int port, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(Cloudflared.binary().toString(), "tunnel", "--no-autoupdate",
                "--url", "http://localhost:" + port).redirectErrorStream(true).start();
        Thread shutdownHook = new Thread(process::destroy, "cloudflared-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Runnable stop = () -> stop(process, shutdownHook);

        CompletableFuture<String> connected = new CompletableFuture<>();
        Deque<String> lastLines = new ArrayDeque<>();
        Thread.ofPlatform().daemon().name("cloudflared-output").start(() -> {
            String url = null;
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = output.readLine()) != null;) {
                    LOG.log(System.Logger.Level.DEBUG, line);
                    synchronized (lastLines) {
                        lastLines.addLast(line);
                        if (lastLines.size() > 20) {
                            lastLines.removeFirst();
                        }
                    }
                    url = url != null ? url : findUrl(line).orElse(null);
                    // The URL comes first; it works once a connection to Cloudflare is registered
                    if (url != null && line.contains("Registered tunnel connection")) {
                        connected.complete(url);
                    }
                }
            } catch (IOException e) {
                // The process was stopped
            }
            connected.completeExceptionally(new IOException("cloudflared exited"));
        });

        try {
            String url = connected.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            LOG.log(System.Logger.Level.INFO, "Port {0} is public at {1}", String.valueOf(port), url);
            return new PublicUrl(url, stop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop.run();
            throw new IOException("Interrupted while opening the tunnel", e);
        } catch (ExecutionException | TimeoutException e) {
            stop.run();
            String output;
            synchronized (lastLines) {
                output = String.join("\n", lastLines);
            }
            throw new IOException((e instanceof TimeoutException ? "The tunnel did not connect in " + timeout
                    : "cloudflared exited") + ". Its last output:\n" + output);
        }
    }

    /** The tunnel URL in a line of cloudflared's output, if there is one. */
    static Optional<String> findUrl(String line) {
        Matcher matcher = URL.matcher(line);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    /** The public URL, such as {@code https://some-random-words.trycloudflare.com}. */
    public String url() {
        return url;
    }

    /** Closes the tunnel; the URL stops working. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closer.run();
        }
    }

    private static void stop(Process process, Thread shutdownHook) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Already shutting down
        }
    }
}
