package com.oriontv.legacy.media;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-confidence HLS ad filtering for legacy playback.
 *
 * V2 combines explicit HLS signaling with playlist structure:
 * - CUE-OUT/CUE-IN and paired SCTE35-OUT/SCTE35-IN boundaries.
 * - Strong ad-shaped segment URIs.
 * - Short foreign-host A-B-A mid-roll runs, even without DISCONTINUITY.
 * - Short foreign-host pre-roll/post-roll runs when a dominant main-content host exists.
 *
 * A bare EXT-X-DISCONTINUITY is never considered an ad by itself.
 */
public final class HlsAdFilter {
    private static final long MAX_MIDROLL_MS = 120000L;
    private static final long MAX_BOUNDARY_ROLL_MS = 90000L;
    private static final long MAX_BOUNDARY_ROLL_WITHOUT_DISCONTINUITY_MS = 60000L;
    private static final int MAX_MIDROLL_SEGMENTS = 30;
    private static final int MAX_BOUNDARY_ROLL_SEGMENTS = 18;
    private static final int MIN_HOST_ANALYSIS_SEGMENTS = 6;

    private static final Pattern EXTINF_DURATION =
            Pattern.compile("^#EXTINF:([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern STRONG_AD_URI = Pattern.compile(
            "(?:^|[/?&._=\\-])(?:ads?|adserver|adservice|adcdn|"
                    + "advert(?:ise(?:ment)?)?|commercial|"
                    + "preroll|midroll|postroll|interstitial|vast|vmap)"
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
        public final boolean filteringBypassed;
        public final String signature;
        public final String diagnostics;

        Result(String playlist, int removedSegments, long removedDurationMs,
               int suppressedInterstitials, int detectedAdMarkers,
               boolean byteRangeBypass, boolean filteringBypassed,
               String signature, String diagnostics) {
            this.playlist = playlist;
            this.removedSegments = removedSegments;
            this.removedDurationMs = removedDurationMs;
            this.suppressedInterstitials = suppressedInterstitials;
            this.detectedAdMarkers = detectedAdMarkers;
            this.byteRangeBypass = byteRangeBypass;
            this.filteringBypassed = filteringBypassed;
            this.signature = signature == null ? "" : signature;
            this.diagnostics = diagnostics == null ? "" : diagnostics;
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
            return new Result(body == null ? "" : body,
                    0, 0L, 0, 0, false, false, "", "");
        }

        String normalized = body.replace("\r", "");
        String upper = normalized.toUpperCase(Locale.US);
        if (upper.indexOf("#EXTINF") < 0) {
            return suppressInterstitialMetadata(normalized);
        }

        // Removing byte-range segments can invalidate implicit byte offsets. We therefore
        // keep media intact and surface the ad evidence so PlayerActivity can prefer another
        // source instead of corrupting playback.
        if (upper.indexOf("#EXT-X-BYTERANGE") >= 0) {
            Result metadataOnly = suppressInterstitialMetadata(normalized);
            boolean bypassed = metadataOnly.detectedAdMarkers > 0
                    || metadataOnly.filteringBypassed;
            return new Result(
                    metadataOnly.playlist,
                    0,
                    0L,
                    metadataOnly.suppressedInterstitials,
                    metadataOnly.detectedAdMarkers,
                    true,
                    bypassed,
                    metadataOnly.signature,
                    appendDiagnostic(metadataOnly.diagnostics,
                            bypassed ? "byte-range-ad-evidence" : "byte-range-media")
            );
        }

        ForeignBlockResult foreign = removeHighConfidenceForeignHostRuns(
                playlistUrl, normalized);
        normalized = foreign.playlist;

        String[] lines = normalized.split("\n", -1);
        boolean pairedScte = hasScteBoundary(lines, 1) && hasScteBoundary(lines, -1);

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
        int unresolvedScteMarkers = 0;
        int uriRemovedSegments = 0;

        StringBuilder signature = new StringBuilder();
        signature.append(foreign.signature);
        String diagnostics = foreign.diagnostics;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            String trimmed = line.trim();
            String upperLine = trimmed.toUpperCase(Locale.US);

            if (isCueOut(upperLine)) {
                adBreak = true;
                detectedMarkers++;
                signature.append("CO|");
                diagnostics = appendDiagnostic(diagnostics, "cue-out");
                continue;
            }
            if (isCueIn(upperLine)) {
                adBreak = false;
                justEndedAdBreak = true;
                detectedMarkers++;
                signature.append("CI|");
                diagnostics = appendDiagnostic(diagnostics, "cue-in");
                continue;
            }

            int scteBoundary = scteBoundaryType(upperLine);
            if (scteBoundary != 0 && pairedScte) {
                adBreak = scteBoundary > 0;
                if (scteBoundary < 0) {
                    justEndedAdBreak = true;
                }
                detectedMarkers++;
                signature.append(scteBoundary > 0 ? "SO|" : "SI|");
                diagnostics = appendDiagnostic(
                        diagnostics, scteBoundary > 0 ? "scte-out" : "scte-in");
                continue;
            }

            if (isInterstitialMetadata(upperLine)) {
                suppressedInterstitials++;
                detectedMarkers++;
                signature.append("I|").append(trimmed).append('|');
                diagnostics = appendDiagnostic(diagnostics, "hls-interstitial");
                continue;
            }

            if (isScteMarker(upperLine)) {
                detectedMarkers++;
                unresolvedScteMarkers++;
                signature.append("SM|").append(trimmed).append('|');
                diagnostics = appendDiagnostic(diagnostics, "unresolved-scte");
                // Keep the media, but remove metadata that old Stagefright does not need.
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
                if (uriAd && !adBreak) {
                    uriRemovedSegments++;
                }
                signature.append(adBreak ? "B|" : "U|").append(trimmed).append('|');
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

        if (uriRemovedSegments > 0) {
            diagnostics = appendDiagnostic(
                    diagnostics, "strong-ad-uri:" + uriRemovedSegments);
        }

        boolean filteringBypassed = unresolvedScteMarkers > 0 && removedSegments == 0;
        if (filteringBypassed) {
            diagnostics = appendDiagnostic(diagnostics, "ad-evidence-not-safely-bounded");
        }

        return new Result(
                out.toString(),
                removedSegments,
                removedDurationMs,
                suppressedInterstitials,
                detectedMarkers,
                false,
                filteringBypassed,
                Integer.toHexString(signature.toString().hashCode()),
                diagnostics
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

    private static final class HostRun {
        final List<SegmentUnit> units = new ArrayList<SegmentUnit>();
        String host;
        long durationMs;
        boolean discontinuityBefore;
    }

    private static final class ForeignBlockResult {
        final String playlist;
        final int removedSegments;
        final long removedDurationMs;
        final String signature;
        final String diagnostics;

        ForeignBlockResult(String playlist, int removedSegments,
                           long removedDurationMs, String signature,
                           String diagnostics) {
            this.playlist = playlist;
            this.removedSegments = removedSegments;
            this.removedDurationMs = removedDurationMs;
            this.signature = signature == null ? "" : signature;
            this.diagnostics = diagnostics == null ? "" : diagnostics;
        }
    }

    private static ForeignBlockResult removeHighConfidenceForeignHostRuns(
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

        if (units.size() < MIN_HOST_ANALYSIS_SEGMENTS) {
            return new ForeignBlockResult(body, 0, 0L, "", "");
        }

        List<HostRun> runs = buildHostRuns(units);
        if (runs.size() < 2) {
            return new ForeignBlockResult(body, 0, 0L, "", "");
        }

        String dominantHost = dominantHost(units);
        int dominantCount = countHost(units, dominantHost);
        int total = units.size();

        int removed = 0;
        long removedMs = 0L;
        StringBuilder signature = new StringBuilder();
        String diagnostics = "";

        // High-confidence mid-roll: A -> short B -> A. This works with or without
        // EXT-X-DISCONTINUITY and avoids relying on ad-specific URI names.
        for (int i = 1; i + 1 < runs.size(); i++) {
            HostRun prev = runs.get(i - 1);
            HostRun mid = runs.get(i);
            HostRun next = runs.get(i + 1);

            if (!sameHost(prev.host, next.host)) continue;
            if (sameHost(prev.host, mid.host)) continue;
            if (prev.host == null || mid.host == null || next.host == null) continue;
            if (prev.units.size() < 2 || next.units.size() < 2) continue;
            if (!isShortAdRun(mid, MAX_MIDROLL_MS, MAX_MIDROLL_SEGMENTS)) continue;

            boolean discontinuityBounded = mid.discontinuityBefore
                    && next.discontinuityBefore;
            boolean dominantHostSandwich = dominantHost != null
                    && sameHost(prev.host, dominantHost)
                    && dominantCount * 100 >= total * 65
                    && mid.durationMs <= MAX_BOUNDARY_ROLL_WITHOUT_DISCONTINUITY_MS;
            if (!discontinuityBounded && !dominantHostSandwich) continue;

            int[] result = markRunDropped(mid, signature, "M");
            removed += result[0];
            removedMs += result[1];
            if (!next.units.isEmpty()) {
                next.units.get(0).stripDiscontinuity = true;
            }
            diagnostics = appendDiagnostic(
                    diagnostics,
                    "midroll-host:" + shortHost(mid.host)
                            + ":" + mid.durationMs + "ms"
            );
        }

        // Boundary pre-roll/post-roll needs stronger evidence than A-B-A:
        // a short foreign host plus a clearly dominant main-content host.
        if (dominantHost != null && dominantCount >= 4
                && dominantCount * 100 >= total * 65) {
            HostRun first = runs.get(0);
            HostRun second = runs.size() > 1 ? runs.get(1) : null;
            if (!sameHost(first.host, dominantHost)
                    && second != null
                    && sameHost(second.host, dominantHost)
                    && boundaryRunLooksLikeAd(first, second.discontinuityBefore,
                    dominantCount, total)) {
                int[] result = markRunDropped(first, signature, "P");
                removed += result[0];
                removedMs += result[1];
                if (!second.units.isEmpty()) {
                    second.units.get(0).stripDiscontinuity = true;
                }
                diagnostics = appendDiagnostic(
                        diagnostics,
                        "preroll-host:" + shortHost(first.host)
                                + ":" + first.durationMs + "ms"
                );
            }

            HostRun last = runs.get(runs.size() - 1);
            HostRun beforeLast = runs.size() > 1 ? runs.get(runs.size() - 2) : null;
            if (!sameHost(last.host, dominantHost)
                    && beforeLast != null
                    && sameHost(beforeLast.host, dominantHost)
                    && boundaryRunLooksLikeAd(last, last.discontinuityBefore,
                    dominantCount, total)) {
                int[] result = markRunDropped(last, signature, "T");
                removed += result[0];
                removedMs += result[1];
                diagnostics = appendDiagnostic(
                        diagnostics,
                        "postroll-host:" + shortHost(last.host)
                                + ":" + last.durationMs + "ms"
                );
            }
        }

        if (removed == 0) {
            return new ForeignBlockResult(body, 0, 0L, "", "");
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
                out.toString(),
                removed,
                removedMs,
                Integer.toHexString(signature.toString().hashCode()),
                diagnostics
        );
    }

    private static List<HostRun> buildHostRuns(List<SegmentUnit> units) {
        List<HostRun> runs = new ArrayList<HostRun>();
        HostRun current = null;

        for (int i = 0; i < units.size(); i++) {
            SegmentUnit unit = units.get(i);
            boolean boundary = current == null
                    || unit.discontinuityBefore
                    || !sameHost(current.host, unit.host);

            if (boundary) {
                current = new HostRun();
                current.host = unit.host;
                current.discontinuityBefore = unit.discontinuityBefore;
                runs.add(current);
            }

            current.units.add(unit);
            current.durationMs += unit.durationMs;
        }

        return runs;
    }

    private static String dominantHost(List<SegmentUnit> units) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < units.size(); i++) {
            String host = units.get(i).host;
            if (host == null || host.length() == 0) continue;
            Integer current = counts.get(host);
            counts.put(host, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }

        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue().intValue();
            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        return best;
    }

    private static int countHost(List<SegmentUnit> units, String host) {
        if (host == null) return 0;
        int count = 0;
        for (int i = 0; i < units.size(); i++) {
            if (sameHost(host, units.get(i).host)) count++;
        }
        return count;
    }

    private static boolean boundaryRunLooksLikeAd(HostRun run,
                                                   boolean transitionHasDiscontinuity,
                                                   int dominantCount,
                                                   int totalCount) {
        if (run == null || run.host == null) return false;
        if (!isShortAdRun(run, MAX_BOUNDARY_ROLL_MS, MAX_BOUNDARY_ROLL_SEGMENTS)) {
            return false;
        }

        if (transitionHasDiscontinuity) {
            return true;
        }

        // Without a discontinuity marker, require a shorter boundary run and stronger
        // dominance from the main host.
        return run.durationMs <= MAX_BOUNDARY_ROLL_WITHOUT_DISCONTINUITY_MS
                && dominantCount * 100 >= totalCount * 70;
    }

    private static boolean isShortAdRun(HostRun run, long maxDurationMs, int maxSegments) {
        return run != null
                && run.units.size() > 0
                && run.units.size() <= maxSegments
                && run.durationMs > 0L
                && run.durationMs <= maxDurationMs;
    }

    private static int[] markRunDropped(HostRun run, StringBuilder signature, String kind) {
        int removed = 0;
        long removedMs = 0L;
        for (int i = 0; i < run.units.size(); i++) {
            SegmentUnit unit = run.units.get(i);
            if (unit.drop) continue;
            unit.drop = true;
            removed++;
            removedMs += unit.durationMs;
            signature.append(kind).append('|').append(unit.uri).append('|');
        }
        return new int[] { removed, (int) Math.min(Integer.MAX_VALUE, removedMs) };
    }

    private static boolean sameHost(String left, String right) {
        if (left == null || right == null) return left == right;
        return left.equalsIgnoreCase(right);
    }

    private static String shortHost(String host) {
        if (host == null) return "?";
        return host.length() <= 48 ? host : host.substring(0, 48);
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
            String host = resolved.getHost();
            return host == null ? null : host.toLowerCase(Locale.US);
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
            if (host != null && host.length() > 0) {
                return host.toLowerCase(Locale.US);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static Result suppressInterstitialMetadata(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder(body.length());
        int suppressed = 0;
        int markers = 0;
        int unresolvedScte = 0;
        StringBuilder signature = new StringBuilder();
        String diagnostics = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            String upper = line.trim().toUpperCase(Locale.US);

            if (isInterstitialMetadata(upper)) {
                suppressed++;
                markers++;
                signature.append("I|").append(line.trim()).append('|');
                diagnostics = appendDiagnostic(diagnostics, "hls-interstitial");
                continue;
            }

            if (isCueOut(upper) || isCueIn(upper) || isScteMarker(upper)) {
                markers++;
                if (isScteMarker(upper)) unresolvedScte++;
            }
            appendLine(out, line);
        }

        boolean bypassed = unresolvedScte > 0;
        if (bypassed) {
            diagnostics = appendDiagnostic(diagnostics, "scte-without-media-boundaries");
        }

        return new Result(
                out.toString(),
                0,
                0L,
                suppressed,
                markers,
                false,
                bypassed,
                Integer.toHexString(signature.toString().hashCode()),
                diagnostics
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
                || upperLine.startsWith("#EXT-X-SCTE35")
                || (upperLine.startsWith("#EXT-X-DATERANGE")
                && upperLine.indexOf("SCTE35") >= 0);
    }

    private static int scteBoundaryType(String upperLine) {
        if (!isScteMarker(upperLine)) return 0;

        if (upperLine.indexOf("SCTE35-IN") >= 0
                || upperLine.indexOf("CUE-IN") >= 0
                || upperLine.indexOf("TYPE=IN") >= 0
                || upperLine.indexOf("TYPE=\"IN\"") >= 0
                || upperLine.indexOf("EVENT=IN") >= 0
                || upperLine.indexOf("EVENT=\"IN\"") >= 0
                || upperLine.indexOf("IN=YES") >= 0) {
            return -1;
        }

        if (upperLine.indexOf("SCTE35-OUT") >= 0
                || upperLine.indexOf("CUE-OUT") >= 0
                || upperLine.indexOf("TYPE=OUT") >= 0
                || upperLine.indexOf("TYPE=\"OUT\"") >= 0
                || upperLine.indexOf("EVENT=OUT") >= 0
                || upperLine.indexOf("EVENT=\"OUT\"") >= 0
                || upperLine.indexOf("OUT=YES") >= 0) {
            return 1;
        }

        return 0;
    }

    private static boolean hasScteBoundary(String[] lines, int wanted) {
        if (lines == null) return false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim().toUpperCase(Locale.US);
            if (scteBoundaryType(line) == wanted) return true;
        }
        return false;
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

    private static String appendDiagnostic(String current, String item) {
        if (item == null || item.length() == 0) return current == null ? "" : current;
        if (current == null || current.length() == 0) return item;
        if (current.indexOf(item) >= 0) return current;
        return current + "," + item;
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line == null ? "" : line);
    }
}
