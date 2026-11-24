package com.aiplayer.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContextModelStoreTest {
    @Test public void acceptsCompleteRangeStartingAtSavedByteOffset() {
        assertEquals(2_500_000_000L,
                ContextModelStore.resumedSize("bytes 3000000-2499999999/2500000000", 3_000_000));
    }

    @Test public void rejectsMissingMismatchedOrOversizedRanges() {
        assertEquals(-1, ContextModelStore.resumedSize(null, 10));
        assertEquals(-1, ContextModelStore.resumedSize("bytes 9-99/100", 10));
        assertEquals(-1, ContextModelStore.resumedSize("bytes 10-98/100", 10));
        assertEquals(-1, ContextModelStore.resumedSize("bytes 10-2600000000/2600000001", 10));
        assertEquals(-1, ContextModelStore.resumedSize("bytes 10-99/999999999999999999999", 10));
    }
}
