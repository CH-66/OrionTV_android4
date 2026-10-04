package com.oriontv.legacy.media;

/** Bound untrusted Retry-After values without changing system-player watchdogs. */
final class PreparationRetry {
    private PreparationRetry() {}
    static long delayMs(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0L ? 250L : seconds >= 10L ? 10000L : seconds * 1000L;
        } catch (RuntimeException error) {
            return 2000L;
        }
    }
}
