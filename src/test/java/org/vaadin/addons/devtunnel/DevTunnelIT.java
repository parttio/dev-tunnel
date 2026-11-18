package org.vaadin.addons.devtunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * The developer's use cases in a real browser, with real Vaadin Copilot in play mode: the test app in dev mode, with
 * a fake tunnel that connects at once. Checks that Dev Tunnel still fits the Copilot version in use.
 */
class DevTunnelIT {

    private static ConfigurableApplicationContext app;
    private static Playwright playwright;
    private static Browser browser;
    private static String url;

    private BrowserContext developer;
    private Pill pill;

    @BeforeAll
    static void startTheApp() {
        TestApp.useFakeTunnel();
        app = SpringApplication.run(TestApp.class, "--server.port=0", "--vaadin.launch-browser=false");
        url = "http://localhost:" + app.getEnvironment().getProperty("local.server.port") + "/";
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stopTheApp() {
        browser.close();
        playwright.close();
        app.close();
    }

    @BeforeEach
    void openTheAppOnLocalhost() {
        developer = browser.newContext();
        Page page = developer.newPage();
        page.navigate(url);
        pill = new Pill(page);
    }

    @AfterEach
    void turnRemoteOff() {
        RemoteTunnel.get().disable();
        developer.close();
    }

    @Test
    void developerSeesTheLimitsAndTermsBeforeEnablingRemote() {
        pill.open();

        assertTrue(pill.says("Up to 200 requests in flight at a time"));
        assertTrue(pill.says("By enabling remote, you accept the Cloudflare Online Services Terms of Use"));
        assertEquals("Remote", pill.text());
    }

    @Test
    void developerEnablesRemoteAndGetsAQrCodeAndLink() {
        pill.open();

        pill.enableRemote();

        assertTrue(pill.showsQrCode());
        assertTrue(pill.says(TestApp.FAKE_URL));
        pill.waitForText("Remote · 0");
    }

    @Test
    void remoteVisitorIsCountedAndSeesNoPill() {
        pill.open();
        pill.enableRemote();

        Page phone = remoteVisitor();

        assertFalse(phone.locator("copilot-main").isVisible());
        pill.waitForText("Remote · 1");

        leave(phone);
        pill.waitForText("Remote · 0");
    }

    @Test
    void turningRemoteOffWithRemoteTabsOpenAsksFirst() {
        pill.open();
        pill.enableRemote();
        Page phone = remoteVisitor();
        pill.waitForText("Remote · 1");

        pill.disableRemote();
        assertTrue(pill.says("1 remote tab is open. Disconnect it?"));
        pill.cancel();
        assertEquals("Remote · 1", pill.text());

        pill.disableRemote();
        pill.disconnect();
        pill.waitForText("Remote");
        leave(phone);
    }

    @Test
    void popoverClosesWithEscape() {
        pill.open();

        pill.page.keyboard().press("Escape");

        assertFalse(pill.isOpen());
    }

    /** A visitor through the tunnel: the tunnel connects from localhost and adds Cloudflare's headers. */
    private Page remoteVisitor() {
        BrowserContext phone = browser.newContext(new Browser.NewContextOptions()
                .setExtraHTTPHeaders(Map.of("Cf-Connecting-Ip", "203.0.113.7", "X-Forwarded-For", "203.0.113.7")));
        Page page = phone.newPage();
        page.navigate(url);
        page.locator("h1").waitFor();
        return page;
    }

    /** The visitor leaves the app, as when closing the tab: the page tells Vaadin, which closes its UI. */
    private static void leave(Page phone) {
        phone.navigate("about:blank");
        phone.context().close();
    }

    /** Where things are: the pill and its popover, inside Copilot's shadow root (Playwright locators pierce it). */
    private record Pill(Page page) {

        Locator button() {
            return page.locator("#dev-tunnel .dt-pill");
        }

        String text() {
            return button().innerText().trim();
        }

        void waitForText(String text) {
            page.waitForCondition(() -> text().equals(text));
        }

        void open() {
            button().click();
            page.locator("#dev-tunnel-popover").waitFor();
        }

        boolean isOpen() {
            return page.locator("#dev-tunnel-popover").count() > 0;
        }

        void enableRemote() {
            page.locator("#dev-tunnel-enable").click();
            page.locator("#dev-tunnel img").waitFor();
        }

        void disableRemote() {
            page.locator("#dev-tunnel-enable").click();
        }

        void cancel() {
            page.locator("#dev-tunnel-cancel").click();
        }

        void disconnect() {
            page.locator("#dev-tunnel-disconnect").click();
        }

        boolean showsQrCode() {
            return page.locator("#dev-tunnel img").getAttribute("src").startsWith("data:image/svg+xml;base64,");
        }

        boolean says(String text) {
            return page.locator("#dev-tunnel-popover").innerText().replaceAll("\\s+", " ").contains(text);
        }
    }
}
