package org.vaadin.addons.devtunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.vaadin.base.devserver.DevToolsInterface;

/** The developer's use cases, as the Dev Tunnel popover sends them, with a stub tunnel that connects at once. */
class DevTunnelHandlerTest {

    private final List<Integer> openedPorts = new ArrayList<>();
    private boolean tunnelClosed;
    private final RemoteTunnel tunnel = new RemoteTunnel(new RemoteTunnel.Connector() {
        @Override
        public boolean needsDownload() {
            return false;
        }

        @Override
        public void download() {
        }

        @Override
        public PublicUrl open(int port) {
            openedPorts.add(port);
            return new PublicUrl("https://quiet-river-sample.trycloudflare.com", () -> tunnelClosed = true);
        }

        @Override
        public void awaitDns(String url) {
        }
    }, Runnable::run);

    private final Panel panel = new Panel();
    private DevTunnelHandler handler;


    @Test
    void developerEnablesRemoteAndGetsALinkAndQrCode() {
        open();

        panel.says("dev-tunnel-enable", "{\"port\": 8080}");

        assertEquals(List.of(8080), openedPorts);
        assertEquals("ON", panel.lastState().get("status"));
        assertEquals("https://quiet-river-sample.trycloudflare.com", panel.lastState().get("url"));
        assertTrue(((String) panel.lastState().get("qrCode")).startsWith("data:image/svg+xml;base64,"));
    }


    @Test
    void developerSeesHowManyRemoteTabsAreOpen() {
        open();
        panel.says("dev-tunnel-enable", "{\"port\": 8080}");

        tunnel.remoteTabOpened();
        tunnel.remoteTabOpened();

        assertEquals(2, panel.lastState().get("remoteTabs"));
    }

    @Test
    void developerDisablesRemoteAndTheLinkStopsWorking() {
        open();
        panel.says("dev-tunnel-enable", "{\"port\": 8080}");

        panel.says("dev-tunnel-disable", "{}");

        assertTrue(tunnelClosed);
        assertEquals("OFF", panel.lastState().get("status"));
    }

    @Test
    void leavesOtherDevToolsMessagesAlone() {
        open();

        assertFalse(handler.handleMessage("copilot-get-user-info", JsonMapper.shared().readTree("{}"), panel));
    }

    private void open() {
        handler = new DevTunnelHandler(tunnel);
        handler.handleConnect(panel);
    }

    /** The browser end of the dev tools connection. */
    private class Panel implements DevToolsInterface {

        private final List<Map<String, Object>> states = new ArrayList<>();

        void says(String command, String json) {
            JsonNode data = JsonMapper.shared().readTree(json);
            assertTrue(handler.handleMessage(command, data, this));
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(String command, Object data) {
            assertEquals(DevTunnelHandler.STATE, command);
            states.add((Map<String, Object>) data);
        }

        Map<String, Object> lastState() {
            return states.getLast();
        }
    }

}
