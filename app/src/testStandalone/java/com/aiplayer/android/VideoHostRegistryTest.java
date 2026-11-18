package com.aiplayer.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VideoHostRegistryTest {
    @Test
    public void matchesLiteralDomainsOnlyOnLabelBoundaries() {
        assertTrue(VideoHostRegistry.matches("www.example.com", "example.com"));
        assertFalse(VideoHostRegistry.matches("notexample.com", "example.com"));
        assertFalse(VideoHostRegistry.matches("example.com.invalid", "example.com"));
    }

    @Test
    public void matchesRootAndSubdomainWildcardsSeparately() {
        assertTrue(VideoHostRegistry.matches("example.net", "example.*"));
        assertFalse(VideoHostRegistry.matches("www.example.net", "example.*"));
        assertTrue(VideoHostRegistry.matches("www.example.net", "*.example.*"));
        assertFalse(VideoHostRegistry.matches("www.notexample.net", "*.example.*"));
    }
}
