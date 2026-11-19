package org.vaadin.addons.devtunnel;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a new quick tunnel's host name can be found on the internet yet. cloudflared reports the URL before its
 * DNS record is everywhere, and a phone that looks the name up too early is told it does not exist, which its
 * resolver then remembers for a while. Asks a public resolver over HTTPS, so the local network's DNS does not matter.
 */
final class PublicDns {

    private static final String[] RESOLVERS = { "https://cloudflare-dns.com/dns-query", "https://dns.google/resolve" };
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Duration GIVE_UP = Duration.ofMinutes(2);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(5)).build();

    private PublicDns() {
    }

    /**
     * Waits until the URL's host resolves publicly. Also returns after two minutes, or when no resolver can be
     * reached: this only decides when to stop warning, so it never waits forever.
     */
    static void await(String url) throws InterruptedException {
        String host = URI.create(url).getHost();
        Instant deadline = Instant.now().plus(GIVE_UP);
        while (Instant.now().isBefore(deadline)) {
            Boolean found = resolves(host);
            if (found == null || found) {
                return;
            }
            Thread.sleep(POLL);
        }
    }

    /** Whether the host has an address; {@code null} when no resolver answered. */
    static Boolean resolves(String host) throws InterruptedException {
        for (String resolver : RESOLVERS) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                        resolver + "?type=A&name=" + URLEncoder.encode(host, StandardCharsets.UTF_8)))
                        .header("Accept", "application/dns-json").timeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return hasAddress(JSON.readTree(response.body()));
                }
            } catch (IOException | RuntimeException e) {
                // try the next resolver
            }
        }
        return null;
    }

    static boolean hasAddress(JsonNode answer) {
        return answer.path("Status").asInt(-1) == 0 && !answer.path("Answer").isEmpty();
    }
}
