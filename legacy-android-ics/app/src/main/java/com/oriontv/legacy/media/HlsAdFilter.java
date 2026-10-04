package com.oriontv.legacy.media;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative HLS ad filtering for legacy playback.
 *
 * The filter intentionally requires explicit HLS ad signaling or a strongly ad-shaped
 * segment URI. A bare EXT-X-DISCONTINUITY is never considered an ad by itself.
 */
public final class HlsAdFilter {
    private static final Pattern EXTINF_DURATION =
            Pattern.compile("^#EXTINF:([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern STRONG_AD_URI = Pattern.compile(
            "(?:^|[/?&._=\\-])(?:ads?|advert(?:ise(?:ment)?)?|commercial|"
                    + "preroll|midroll|postroll|interstitial|vast)"
                    + "(?:[/?&._=\\-]|$)",
            Pattern.CASE_INSENSITIVE);

    private HlsAdFilter() {}

    public static final class Result {
        public final String playlist;
        public final int removedSegments;
        public final long removedDurationMs;
        public final int suppressedInterstitials;
        public final int detectedAdMarkers;
        public final boolean byteRangeBypass;
        public final String signature;

        Result(String playlist, int removedSegments, long removedDurationMs,
               int suppressedInterstitials, int detectedAdMarkers,
               boolean byteRangeBypass, String signature) {
            this.playlist = playlist;
            this.removedSegments = removedSegments;
            this.removedDurationMs = removedDurationMs;
            this.suppressedInterstitials = suppressedInterstitials;
            this.detectedAdMarkers = detectedAdMarkers;
            this.byteRangeBypass = byteRangeBypass;
            this.signature = signature == null ? "" : signature;
        }

        public boolean changed() {
            return removedSegments > 0 || suppressedInterstitials > 0;
        }

        public boolean hasAdEvidence() {
            return changed() || detectedAdMarkers > 0;
        }
    }

    public static Result filter(String playlistUrl, String body) {
        if (body == null || body.length() == 0) {
            return new Result(body == null ? "" : body, 0, 0L, 0, 0, false, "");
        }

        String normalized = body.replace("\r", "");
        String upper = normalized.toUpperCase(Locale.US);
        if (upper.indexOf("#EXTINF") < 0) {
            return suppressInterstitialMetadata(normalized);
        }

        // Removing byte-range segments can invalidate implicit byte offsets. For those
        // playlists we suppress metadata-only interstitials but never delete media.
        if (upper.indexOf("#EXT-X-BYTERANGE") >= 0) {
            Result metadataOnly = suppressInterstitialMetadata(normalized);
            return new Result(metadataOnly.playlist, 0, 0L,
                    metadataOnly.suppressedInterstitials,
                    metadataOnly.detectedAdMarkers,
                    true,
                    metadataOnly.signature);
        }

        ForeignBlockResult foreign = removeSandwichedForeignHostBlocks(playlistUrl, normalized);
        normalized = foreign.playlist;

        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length());
        List<String> pending = new ArrayList<String>();
        List<String> block = new ArrayList<String>();

        boolean seenSegment = false;
        boolean collectingBlock = false;
        boolean adBreak = false;
        boolean previousRemoved = false;
        boolean justEndedAdBreak = false;

        int removedSegments = foreign.removedSegments;
        long removedDurationMs = foreign.removedDurationMs;
        int suppressedInterstitials = 0;
        int detectedMarkers = foreign.removedSegments > 0 ? 1 : 0;
        StringBuilder signature = new StringBuilder();
        signature.append(foreign.signature);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            String trimmed = line.trim();
            String upperLine = trimmed.toUpperCase(Locale.US);

            if (isCueOut(upperLine)) {
                adBreak = true;
                detectedMarkers++;
                continue;
            }
            if (isCueIn(upperLine)) {
                adBreak = false;
                justEndedAdBreak = true;
                detectedMarkers++;
                continue;
            }
            if (isScteMarker(upperLine)) {
                detectedMarkers++;
                // SCTE metadata alone does not define the segment boundaries safely.
                continue;
            }
            if (isInterstitialMetadata(upperLine)) {
                suppressedInterstitials++;
                detectedMarkers++;
                signature.append("I|").append(trimmed).append('|');
                continue;
            }

            if (!collectingBlock) {
                if (upperLine.startsWith("#EXTINF")) {
                    block.clear();
                    if (seenSegment && !pending.isEmpty()) {
                        block.addAll(pending);
                        pending.clear();
                    }
                    block.add(line);
                    collectingBlock = true;
                    continue;
                }

                if (!seenSegment) {
                    appendLine(out, line);
                } else {
                    pending.add(line);
                }
                continue;
            }

            block.add(line);
            if (trimmed.length() == 0 || trimmed.startsWith("#")) {
                continue;
            }

            boolean uriAd = isStrongAdUri(trimmed);
            boolean drop = adBreak || uriAd;
            if (drop) {
                removedSegments++;
                removedDurationMs += blockDurationMs(block);
                signature.append(adBreak ? "C|" : "U|").append(trimmed).append('|');
            } else {
                appendBlock(out, block, previousRemoved || justEndedAdBreak);
            }

            seenSegment = true;
            collectingBlock = false;
            previousRemoved = drop;
            if (!drop) {
                justEndedAdBreak = false;
            }
            block.clear();
        }

        // Preserve trailing ENDLIST and other safe metadata. A discontinuity immediately
        // after a removed ad block is discarded so Stagefright does not reset unnecessarily.
        if (collectingBlock && !block.isEmpty()) {
            appendBlock(out, block, previousRemoved || justEndedAdBreak);
        }
        if (!pending.isEmpty()) {
            appendBlock(out, pending, previousRemoved || justEndedAdBreak);
        }

        return new Result(
                out.toString(),
                removedSegments,
                removedDurationMs,
                suppressedInterstitials,
                detectedMarkers,
                false,
                Integer.toHexString(signature.toString().hashCode())
        );
    }

    private static final class SegmentUnit {
        final List<String> lines = new ArrayList<String>();
        String uri;
        String host;
        long durationMs;
        boolean discontinuityBefore;
        boolean drop;
        boolean stripDiscontinuity;
    }

    private static final class SegmentBlock {
        final List<SegmentUnit> units = new ArrayList<SegmentUnit>();
        String host;
        long durationMs;
    }

    private static final class ForeignBlockResult {
        final String playlist;
        final int removedSegments;
        final long removedDurationMs;
        final String signature;

        ForeignBlockResult(String playlist, int removedSegments,
                           long removedDurationMs, String signature) {
            this.playlist = playlist;
            this.removedSegments = removedSegments;
            this.removedDurationMs = removedDurationMs;
            this.signature = signature == null ? "" : signature;
        }
    }

    private static ForeignBlockResult removeSandwichedForeignHostBlocks(
            String playlistUrl, String body) {
        String[] lines = body.split("\n", -1);
        List<String> header = new ArrayList<String>();
        List<String> pending = new ArrayList<String>();
        List<String> trailer = new ArrayList<String>();
        List<SegmentUnit> units = new ArrayList<SegmentUnit>();

        boolean seenSegment = false;
        boolean collecting = false;
        SegmentUnit current = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            String trimmed = line.trim();
            String upper = trimmed.toUpperCase(Locale.US);

            if (!collecting && upper.startsWith("#EXTINF")) {
                current = new SegmentUnit();
                if (seenSegment && !pending.isEmpty()) {
                    current.lines.addAll(pending);
                    pending.clear();
                }
                current.lines.add(line);
                current.durationMs = parseExtinfMs(trimmed);
                current.discontinuityBefore = containsDiscontinuity(current.lines);
                collecting = true;
                continue;
            }

            if (collecting) {
                current.lines.add(line);
                if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                    current.uri = trimmed;
                    current.host = effectiveHost(playlistUrl, trimmed);
                    units.add(current);
                    current = null;
                    collecting = false;
                    seenSegment = true;
                }
                continue;
            }

            if (!seenSegment) {
                header.add(line);
            } else {
                pending.add(line);
            }
        }

        if (collecting && current != null) {
            pending.addAll(current.lines);
        }
        trailer.addAll(pending);

        if (units.size() < 5) {
            return new ForeignBlockResult(body, 0, 0L, "");
        }

        List<SegmentBlock> blocks = new ArrayList<SegmentBlock>();
        SegmentBlock block = null;
        for (int i = 0; i < units.size(); i++) {
            SegmentUnit unit = units.get(i);
            if (block == null || (unit.discontinuityBefore && !block.units.isEmpty())) {
                block = new SegmentBlock();
                blocks.add(block);
            }
            block.units.add(unit);
            block.durationMs += unit.durationMs;
        }

        for (int i = 0; i < blocks.size(); i++) {
            SegmentBlock b = blocks.get(i);
            b.host = stableHost(b.units);
        }

        int removed = 0;
        long removedMs = 0L;
        StringBuilder signature = new StringBuilder();

        for (int i = 1; i + 1 < blocks.size(); i++) {
            SegmentBlock prev = blocks.get(i - 1);
            SegmentBlock mid = blocks.get(i);
            SegmentBlock next = blocks.get(i + 1);

            if (prev.host == null || mid.host == null || next.host == null) continue;
            if (!prev.host.equalsIgnoreCase(next.host)) continue;
            if (prev.host.equalsIgnoreCase(mid.host)) continue;
            if (prev.units.size() < 2 || next.units.size() < 2) continue;
            if (mid.units.size() == 0 || mid.units.size() > 30) continue;
            if (mid.durationMs <= 0L || mid.durationMs > 120000L) continue;

            for (int j = 0; j < mid.units.size(); j++) {
                SegmentUnit unit = mid.units.get(j);
                if (!unit.drop) {
                    unit.drop = true;
                    removed++;
                    removedMs += unit.durationMs;
                    signature.append("H|").append(unit.uri).append('|');
                }
            }

            if (!next.units.isEmpty()) {
                next.units.get(0).stripDiscontinuity = true;
            }
        }

        if (removed == 0) {
            return new ForeignBlockResult(body, 0, 0L, "");
        }

        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < header.size(); i++) {
            appendLine(out, header.get(i));
        }
        for (int i = 0; i < units.size(); i++) {
            SegmentUnit unit = units.get(i);
            if (unit.drop) continue;
            appendBlock(out, unit.lines, unit.stripDiscontinuity);
        }
        for (int i = 0; i < trailer.size(); i++) {
            String line = trailer.get(i);
            if (removed > 0 && line != null
                    && line.trim().toUpperCase(Locale.US)
                    .startsWith("#EXT-X-DISCONTINUITY")) {
                continue;
            }
            appendLine(out, line);
        }

        return new ForeignBlockResult(
                out.toString(), removed, removedMs,
                Integer.toHexString(signature.toString().hashCode()));
    }

    private static String stableHost(List<SegmentUnit> units) {
        if (units == null || units.size() == 0) return null;
        String host = null;
        for (int i = 0; i < units.size(); i++) {
            String candidate = units.get(i).host;
            if (candidate == null || candidate.length() == 0) return null;
            if (host == null) {
                host = candidate;
            } else if (!host.equalsIgnoreCase(candidate)) {
                return null;
            }
        }
        return host;
    }

    private static boolean containsDiscontinuity(List<String> lines) {
        if (lines == null) return false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && line.trim().toUpperCase(Locale.US)
                    .startsWith("#EXT-X-DISCONTINUITY")) {
                return true;
            }
        }
        return false;
    }

    private static long parseExtinfMs(String line) {
        if (line == null) return 0L;
        Matcher matcher = EXTINF_DURATION.matcher(line.trim());
        if (!matcher.find()) return 0L;
        try {
            return Math.max(0L, (long) (Double.parseDouble(matcher.group(1)) * 1000.0));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String effectiveHost(String playlistUrl, String segmentUri) {
        if (segmentUri == null || segmentUri.length() == 0) return null;

        String nested = queryTarget(segmentUri);
        if (nested != null) {
            String nestedHost = uriHost(nested);
            if (nestedHost != null) return nestedHost;
        }

        String direct = uriHost(segmentUri);
        if (direct != null) return direct;

        try {
            URI base = URI.create(playlistUrl);
            URI resolved = base.resolve(segmentUri);
            String nestedResolved = queryTarget(resolved.toString());
            if (nestedResolved != null) {
                String nestedHost = uriHost(nestedResolved);
                if (nestedHost != null) return nestedHost;
            }
            return resolved.getHost();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String queryTarget(String value) {
        if (value == null) return null;
        int q = value.indexOf('?');
        if (q < 0 || q + 1 >= value.length()) return null;
        String query = value.substring(q + 1);
        String[] parts = query.split("&");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq);
            if (!"u".equalsIgnoreCase(key) && !"url".equalsIgnoreCase(key)) continue;
            try {
                return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
            } catch (Exception ignored) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    private static String uriHost(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host != null && host.length() > 0) return host.toLowerCase(Locale.US);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static Result suppressInterstitialMetadata(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder(body.length());
        int suppressed = 0;
        int markers = 0;
        StringBuilder signature = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            String upper = line.trim().toUpperCase(Locale.US);

            if (isInterstitialMetadata(upper)) {
                suppressed++;
                markers++;
                signature.append("I|").append(line.trim()).append('|');
                continue;
            }
            if (isCueOut(upper) || isCueIn(upper) || isScteMarker(upper)) {
                markers++;
            }
            appendLine(out, line);
        }

        return new Result(
                out.toString(), 0, 0L, suppressed, markers, false,
                Integer.toHexString(signature.toString().hashCode())
        );
    }

    private static boolean isCueOut(String upperLine) {
        return upperLine.startsWith("#EXT-X-CUE-OUT")
                || upperLine.startsWith("#EXT-X-CUE-OUT-CONT");
    }

    private static boolean isCueIn(String upperLine) {
        return upperLine.startsWith("#EXT-X-CUE-IN");
    }

    private static boolean isScteMarker(String upperLine) {
        return upperLine.startsWith("#EXT-OATCLS-SCTE35")
                || upperLine.startsWith("#EXT-X-SCTE35");
    }

    private static boolean isInterstitialMetadata(String upperLine) {
        if (!upperLine.startsWith("#EXT-X-DATERANGE")) {
            return false;
        }
        return upperLine.indexOf("INTERSTITIAL") >= 0
                || upperLine.indexOf("X-ASSET-URI=") >= 0
                || upperLine.indexOf("X-ASSET-LIST=") >= 0;
    }

    private static boolean isStrongAdUri(String uri) {
        if (uri == null || uri.length() == 0) return false;
        if (STRONG_AD_URI.matcher(uri).find()) return true;
        try {
            String decoded = URLDecoder.decode(uri, "UTF-8");
            return STRONG_AD_URI.matcher(decoded).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long blockDurationMs(List<String> block) {
        for (int i = 0; i < block.size(); i++) {
            String line = block.get(i);
            if (line == null) continue;
            Matcher matcher = EXTINF_DURATION.matcher(line.trim());
            if (!matcher.find()) continue;
            try {
                double seconds = Double.parseDouble(matcher.group(1));
                return Math.max(0L, (long) (seconds * 1000.0));
            } catch (RuntimeException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static void appendBlock(StringBuilder out, List<String> block,
                                    boolean stripDiscontinuity) {
        for (int i = 0; i < block.size(); i++) {
            String line = block.get(i);
            String trimmed = line == null ? "" : line.trim();
            if (stripDiscontinuity
                    && trimmed.toUpperCase(Locale.US).startsWith("#EXT-X-DISCONTINUITY")) {
                continue;
            }
            appendLine(out, line == null ? "" : line);
        }
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line == null ? "" : line);
    }
}
