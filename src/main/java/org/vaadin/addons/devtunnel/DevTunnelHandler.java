package org.vaadin.addons.devtunnel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.JsonNode;

import com.vaadin.base.devserver.DevToolsInterface;
import com.vaadin.base.devserver.DevToolsMessageHandler;

/**
 * The server side of the Remote popover, over the dev tools connection, which only the local developer has.
 * Registered through {@code META-INF/services}. Every connected page gets the state on each change.
 */
public class DevTunnelHandler implements DevToolsMessageHandler {

    static final String STATE = "dev-tunnel-state";

    private final RemoteTunnel tunnel;
    private final Set<DevToolsInterface> pages = ConcurrentHashMap.newKeySet();

    public DevTunnelHandler() {
        this(RemoteTunnel.get());
    }

    DevTunnelHandler(RemoteTunnel tunnel) {
        this.tunnel = tunnel;
        tunnel.addListener(state -> pages.forEach(page -> page.send(STATE, message(state))));
    }

    @Override
    public void handleConnect(DevToolsInterface devToolsInterface) {
        pages.add(devToolsInterface);
        devToolsInterface.send(STATE, message(tunnel.state())); // so a reloaded page shows a running tunnel
    }

    @Override
    public void handleDisconnect(DevToolsInterface devToolsInterface) {
        pages.remove(devToolsInterface);
    }

    @Override
    public boolean handleMessage(String command, JsonNode data, DevToolsInterface devToolsInterface) {
        switch (command) {
            case STATE -> devToolsInterface.send(STATE, message(tunnel.state()));
            case "dev-tunnel-enable" -> tunnel.enable(data.path("port").asInt());
            case "dev-tunnel-disable" -> tunnel.disable();
            default -> {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> message(RemoteTunnel.State state) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("status", state.status().name());
        message.put("url", state.url());
        message.put("qrCode", state.url() != null ? QrCode.svgDataUrl(state.url()) : null);
        message.put("error", state.error());
        message.put("remoteTabs", state.remoteTabs());
        return message;
    }
}
