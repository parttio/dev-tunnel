package org.vaadin.addons.devtunnel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PublicDnsTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aNameWithAnAddressIsFound() {
        assertTrue(PublicDns.hasAddress(json.readTree("""
                {"Status":0,"Answer":[{"name":"a-b.trycloudflare.com","type":1,"data":"104.16.230.132"}]}""")));
    }

    @Test
    void aNameThatDoesNotExistYetIsNotFound() {
        assertFalse(PublicDns.hasAddress(json.readTree("""
                {"Status":3,"Authority":[{"name":"trycloudflare.com","type":6}]}""")));
    }

    @Test
    void anEmptyAnswerIsNotFound() {
        assertFalse(PublicDns.hasAddress(json.readTree("""
                {"Status":0}""")));
    }
}
