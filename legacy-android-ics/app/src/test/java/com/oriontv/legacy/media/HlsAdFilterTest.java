package com.oriontv.legacy.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HlsAdFilterTest {
    @Test
    public void removesForeignHostPrerollWithoutDiscontinuity() {
        String body = playlist(
                seg("https://altcdn.example.net/a1.ts"),
                seg("https://altcdn.example.net/a2.ts"),
                seg("https://video.example.com/v1.ts"),
                seg("https://video.example.com/v2.ts"),
                seg("https://video.example.com/v3.ts"),
                seg("https://video.example.com/v4.ts"),
                seg("https://video.example.com/v5.ts"),
                seg("https://video.example.com/v6.ts")
        );

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(2, result.removedSegments);
        assertFalse(result.playlist.contains("altcdn.example.net"));
        assertTrue(result.playlist.contains("v1.ts"));
        assertTrue(result.diagnostics.contains("preroll-host"));
    }

    @Test
    public void removesForeignHostPostroll() {
        String body = playlist(
                seg("https://video.example.com/v1.ts"),
                seg("https://video.example.com/v2.ts"),
                seg("https://video.example.com/v3.ts"),
                seg("https://video.example.com/v4.ts"),
                seg("https://video.example.com/v5.ts"),
                seg("https://video.example.com/v6.ts"),
                "#EXT-X-DISCONTINUITY\n" + seg("https://altcdn.example.net/a1.ts"),
                seg("https://altcdn.example.net/a2.ts")
        );

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(2, result.removedSegments);
        assertFalse(result.playlist.contains("altcdn.example.net"));
        assertTrue(result.diagnostics.contains("postroll-host"));
        assertFalse(result.playlist.contains("#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST"));
    }

    @Test
    public void removesShortDominantHostSandwichWithoutDiscontinuity() {
        String body = playlist(
                seg("https://video.example.com/v1.ts"),
                seg("https://video.example.com/v2.ts"),
                seg("https://video.example.com/v3.ts"),
                seg("https://altcdn.example.net/a1.ts"),
                seg("https://altcdn.example.net/a2.ts"),
                seg("https://video.example.com/v4.ts"),
                seg("https://video.example.com/v5.ts"),
                seg("https://video.example.com/v6.ts")
        );

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(2, result.removedSegments);
        assertFalse(result.playlist.contains("altcdn.example.net"));
        assertTrue(result.diagnostics.contains("midroll-host"));
        assertTrue(result.diagnostics.contains("splice-reset"));
        assertTrue(result.playlist.contains(
                "v3.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttps://video.example.com/v4.ts"));
    }

    @Test
    public void removesSameHostDiscontinuityIslandWithLongContentContext() {
        String body = sameHostIslandPlaylist(18, 3, 18);

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(3, result.removedSegments);
        assertFalse(result.playlist.contains("/mid/m1.ts"));
        assertFalse(result.playlist.contains("/mid/m2.ts"));
        assertFalse(result.playlist.contains("/mid/m3.ts"));
        assertTrue(result.playlist.contains("/main/b18.ts"));
        assertTrue(result.playlist.contains("/main/a1.ts"));
        assertTrue(result.diagnostics.contains("midroll-discontinuity"));
        assertTrue(result.diagnostics.contains("splice-reset"));
        assertEquals(1, countOccurrences(result.playlist, "#EXT-X-DISCONTINUITY"));
    }

    @Test
    public void keepsSameHostDiscontinuityIslandWithoutLongContentContext() {
        String body = sameHostIslandPlaylist(6, 3, 6);

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(0, result.removedSegments);
        assertTrue(result.playlist.contains("/mid/m1.ts"));
        assertEquals(2, countOccurrences(result.playlist, "#EXT-X-DISCONTINUITY"));
    }

    @Test
    public void doesNotDeleteWeakHostSwitchWithoutDominance() {
        String body = playlist(
                seg("https://a.example.com/a1.ts"),
                seg("https://a.example.com/a2.ts"),
                seg("https://b.example.com/b1.ts"),
                seg("https://b.example.com/b2.ts"),
                seg("https://a.example.com/a3.ts"),
                seg("https://a.example.com/a4.ts"),
                seg("https://c.example.com/c1.ts"),
                seg("https://c.example.com/c2.ts")
        );

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://manifest.example.com/master.m3u8", body);

        assertEquals(0, result.removedSegments);
        assertTrue(result.playlist.contains("b1.ts"));
        assertTrue(result.playlist.contains("c1.ts"));
    }

    @Test
    public void removesPairedDateRangeScteBreak() {
        String body = "#EXTM3U\n"
                + seg("https://video.example.com/v1.ts") + "\n"
                + seg("https://video.example.com/v2.ts") + "\n"
                + "#EXT-X-DATERANGE:ID=\"ad-1\",SCTE35-OUT=\"0xFC\"\n"
                + seg("https://video.example.com/adchunk1.ts") + "\n"
                + seg("https://video.example.com/adchunk2.ts") + "\n"
                + "#EXT-X-DATERANGE:ID=\"ad-1\",SCTE35-IN=\"0xFC\"\n"
                + seg("https://video.example.com/v3.ts") + "\n"
                + seg("https://video.example.com/v4.ts") + "\n"
                + "#EXT-X-ENDLIST";

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(2, result.removedSegments);
        assertFalse(result.playlist.contains("adchunk1.ts"));
        assertTrue(result.playlist.contains("v3.ts"));
        assertTrue(result.playlist.contains(
                "v2.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttps://video.example.com/v3.ts"));
        assertTrue(result.diagnostics.contains("splice-reset"));
        assertFalse(result.filteringBypassed);
    }

    @Test
    public void unpairedScteDoesNotDeleteRemainderAndRequestsFallback() {
        String body = "#EXTM3U\n"
                + seg("https://video.example.com/v1.ts") + "\n"
                + "#EXT-X-SCTE35:0xFC3020\n"
                + seg("https://video.example.com/v2.ts") + "\n"
                + seg("https://video.example.com/v3.ts") + "\n"
                + seg("https://video.example.com/v4.ts") + "\n"
                + seg("https://video.example.com/v5.ts") + "\n"
                + seg("https://video.example.com/v6.ts") + "\n"
                + "#EXT-X-ENDLIST";

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(0, result.removedSegments);
        assertTrue(result.filteringBypassed);
        assertTrue(result.playlist.contains("v6.ts"));
    }

    @Test
    public void unresolvedScteStillRequestsFallbackWhenUriAdWasRemoved() {
        String body = "#EXTM3U\n"
                + seg("https://video.example.com/v1.ts") + "\n"
                + "#EXT-X-SCTE35:0xFC3020\n"
                + seg("https://video.example.com/ads/preroll.ts") + "\n"
                + seg("https://video.example.com/v2.ts") + "\n"
                + seg("https://video.example.com/v3.ts") + "\n"
                + seg("https://video.example.com/v4.ts") + "\n"
                + seg("https://video.example.com/v5.ts") + "\n"
                + "#EXT-X-ENDLIST";

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(1, result.removedSegments);
        assertTrue(result.filteringBypassed);
        assertFalse(result.playlist.contains("/ads/preroll.ts"));
    }

    @Test
    public void byteRangeAdEvidenceNeverDeletesMedia() {
        String body = "#EXTM3U\n"
                + "#EXT-X-BYTERANGE:1000@0\n"
                + "#EXT-X-CUE-OUT:30\n"
                + seg("https://video.example.com/chunk.mp4") + "\n"
                + "#EXT-X-CUE-IN\n"
                + "#EXT-X-BYTERANGE:1000@1000\n"
                + seg("https://video.example.com/chunk.mp4") + "\n"
                + "#EXT-X-ENDLIST";

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(0, result.removedSegments);
        assertTrue(result.byteRangeBypass);
        assertTrue(result.filteringBypassed);
    }

    @Test
    public void strongAdUriStillFiltersSingleSegment() {
        String body = playlist(
                seg("https://video.example.com/v1.ts"),
                seg("https://video.example.com/v2.ts"),
                seg("https://video.example.com/ads/preroll.ts"),
                seg("https://video.example.com/v3.ts"),
                seg("https://video.example.com/v4.ts"),
                seg("https://video.example.com/v5.ts")
        );

        HlsAdFilter.Result result = HlsAdFilter.filter(
                "https://video.example.com/master.m3u8", body);

        assertEquals(1, result.removedSegments);
        assertFalse(result.playlist.contains("/ads/preroll.ts"));
        assertTrue(result.playlist.contains(
                "v2.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10.0,\nhttps://video.example.com/v3.ts"));
        assertTrue(result.diagnostics.contains("splice-reset"));
    }

    private static String sameHostIslandPlaylist(int before, int middle, int after) {
        StringBuilder out = new StringBuilder("#EXTM3U");
        for (int i = 1; i <= before; i++) {
            out.append('\n').append(seg("https://video.example.com/main/b" + i + ".ts"));
        }
        out.append("\n#EXT-X-DISCONTINUITY");
        for (int i = 1; i <= middle; i++) {
            out.append('\n').append(seg("https://video.example.com/mid/m" + i + ".ts"));
        }
        out.append("\n#EXT-X-DISCONTINUITY");
        for (int i = 1; i <= after; i++) {
            out.append('\n').append(seg("https://video.example.com/main/a" + i + ".ts"));
        }
        out.append("\n#EXT-X-ENDLIST");
        return out.toString();
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while (value != null && needle != null && needle.length() > 0) {
            int at = value.indexOf(needle, from);
            if (at < 0) break;
            count++;
            from = at + needle.length();
        }
        return count;
    }

    private static String playlist(String... blocks) {
        StringBuilder out = new StringBuilder("#EXTM3U");
        for (int i = 0; i < blocks.length; i++) {
            out.append('\n').append(blocks[i]);
        }
        out.append("\n#EXT-X-ENDLIST");
        return out.toString();
    }

    private static String seg(String url) {
        return "#EXTINF:10.0,\n" + url;
    }
}
