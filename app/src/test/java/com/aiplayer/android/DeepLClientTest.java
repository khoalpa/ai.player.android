package com.aiplayer.android;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class DeepLClientTest {
    @Test public void requestPreservesOrderAndLetsDeepLDetectUnknownSource() throws Exception {
        List<String> texts = Arrays.asList("日本語", "한글 \"text\"");
        JSONObject body = new JSONObject(new String(DeepLClient.requestBody("auto", texts), StandardCharsets.UTF_8));
        assertFalse(body.has("source_lang"));
        assertEquals("VI", body.getString("target_lang"));
        assertEquals(texts.get(0), body.getJSONArray("text").getString(0));
        assertEquals(texts.get(1), body.getJSONArray("text").getString(1));

        JSONObject knownSource = new JSONObject(new String(DeepLClient.requestBody("ja", texts), StandardCharsets.UTF_8));
        assertEquals("JA", knownSource.getString("source_lang"));
    }

    @Test public void responseMustContainOneValidTranslationPerInput() throws Exception {
        String response = "{\"translations\":[{\"text\":\"one\"},{\"text\":\"two\"}]}";
        assertEquals(Arrays.asList("one", "two"), DeepLClient.readTranslations(response, 2));
        assertThrows(IOException.class, () -> DeepLClient.readTranslations(response, 3));
        assertThrows(IOException.class,
                () -> DeepLClient.readTranslations("{\"translations\":[{\"text\":null}]}", 1));
    }
}
