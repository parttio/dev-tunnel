package org.vaadin.addons.devtunnel;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.router.Route;

/**
 * A minimal app to try Dev Tunnel with: run {@code mvn} and open http://localhost:8080. With {@code --fake-tunnel},
 * enabling remote connects at once to a made-up URL, without cloudflared or a network; the browser test uses it.
 */
@SpringBootApplication
public class TestApp implements AppShellConfigurator {

    static final String FAKE_URL = "https://fake-tunnel-for-tests.trycloudflare.com";

    public static void main(String[] args) {
        if (List.of(args).contains("--fake-tunnel")) {
            useFakeTunnel();
        }
        SpringApplication.run(TestApp.class, args);
    }

    static void useFakeTunnel() {
        RemoteTunnel.get().useConnector(new RemoteTunnel.Connector() {
            @Override
            public boolean needsDownload() {
                return false;
            }

            @Override
            public void download() {
            }

            @Override
            public PublicUrl open(int port) {
                return new PublicUrl(FAKE_URL, () -> {
                });
            }
        });
    }

    @Route("")
    public static class View extends Div {

        public View() {
            add(new H1("Dev Tunnel test app"),
                    new Paragraph("Click Remote in the top-right corner to make this app public."));
        }
    }
}
