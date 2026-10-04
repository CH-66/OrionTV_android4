package com.oriontv.legacy.media;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class PreparationRetryTest {
    @Test public void boundsUntrustedValues() {
        assertEquals(2000L, PreparationRetry.delayMs(null));
        assertEquals(2000L, PreparationRetry.delayMs("date"));
        assertEquals(2000L, PreparationRetry.delayMs("2"));
        assertEquals(250L, PreparationRetry.delayMs("-1"));
        assertEquals(10000L, PreparationRetry.delayMs("99999999999999"));
    }
}
