package org.vaadin.addons.devtunnel;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinServletRequest;

/**
 * In dev mode, adds the Dev Tunnel pill to pages opened from this machine, counts the browser tabs opened through
 * the tunnel, and closes the tunnel when the app stops or restarts. Registered through {@code META-INF/services},
 * so having the jar on the classpath is enough. Turned off with the system property
 * {@code dev.tunnel.enabled=false}.
 */
public class DevTunnelInitListener implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        if (event.getSource().getDeploymentConfiguration().isProductionMode()
                || "false".equals(System.getProperty("dev.tunnel.enabled"))) {
            return;
        }
        // Dev restarts (Spring Boot devtools, hotswap) keep the JVM, so its shutdown hook would leave cloudflared running
        event.getSource().addServiceDestroyListener(destroy -> RemoteTunnel.get().disable());
        event.addIndexHtmlRequestListener(response -> {
            VaadinRequest request = response.getVaadinRequest();
            if (isLocal(request.getRemoteAddr(), request.getHeader("Cf-Connecting-Ip"),
                    request.getHeader("X-Forwarded-For"), request.getHeader("Forwarded"))) {
                // The port the server listens on, which the browser's differs from behind port mapping (containers)
                response.getDocument().body().appendElement("script").attr("type", "module")
                        .attr("src", "dev-tunnel/dev-tunnel.js")
                        .attr("data-port", String.valueOf(localPort(request)));
            }
        });
        event.getSource().addUIInitListener(uiInit -> {
            VaadinRequest request = VaadinRequest.getCurrent();
            if (request != null && request.getHeader("Cf-Connecting-Ip") != null) {
                RemoteTunnel.get().remoteTabOpened();
                uiInit.getUI().addDetachListener(detach -> RemoteTunnel.get().remoteTabClosed());
            }
        });
    }

    private static int localPort(VaadinRequest request) {
        return request instanceof VaadinServletRequest servletRequest ? servletRequest.getLocalPort() : 0;
    }

    /**
     * Whether a request comes from someone at this machine: from a loopback address, and not passed on by the
     * tunnel or a proxy, which also connect from localhost but add forwarding headers.
     */
    static boolean isLocal(String remoteAddress, String... forwardingHeaders) {
        for (String header : forwardingHeaders) {
            if (header != null) {
                return false;
            }
        }
        return remoteAddress != null && (remoteAddress.startsWith("127.") || remoteAddress.equals("::1")
                || remoteAddress.equals("0:0:0:0:0:0:0:1"));
    }
}
