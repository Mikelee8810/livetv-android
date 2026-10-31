package com.livetv.app;

import android.util.Log;
import com.bumptech.glide.load.Key;
import com.google.common.net.HttpHeaders;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/* JADX INFO: loaded from: classes8.dex */
public final class OnDeviceStreamServer extends NanoHTTPD {
    private static final String TAG = "LiveResolver";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Google TV Streamer) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static OnDeviceStreamServer instance;
    private final OkHttpClient client;
    /** Segments fetch fail-fast: no retry sleeps, short read timeout. */
    private final OkHttpClient segmentClient;
    // Route names from dlhd-web (Lunatic16), which added a "hub" player this
    // app never had. Route 0 (ntvs) is the odd one out: its embed page lives
    // on the primary host's /stream/ path, the rest use /<route>/stream-<id>.php.
    private static final String[] ROUTES = {"ntvs", "stream", "cast", "watch", "plus", "casting", "player", "hub"};
    private static final Pattern IFRAME = Pattern.compile("<iframe\\s+src=[\"']([^\"']+)", 2);
    private static final Pattern SOURCE = Pattern.compile("source\\s*:\\s*(?:window\\.)?atob\\(['\"]([^'\"]+)['\"]\\)|source\\s*:\\s*['\"]([^'\"]+\\.m3u8[^'\"]*)['\"]", 2);
    private static final Pattern STREAM_URL = Pattern.compile("streamUrl\\s*:\\s*['\"]([^'\"]+\\.m3u8[^'\"]*)['\"]", 2);
    private static final Pattern STRING_VAR = Pattern.compile("(?:var|let|const)\\s+(\\w+)\\s*=\\s*['\"]([A-Za-z0-9_\\-+/=]+)['\"]");
    private static final Pattern JOINED_VAR = Pattern.compile("(?:var|let|const)\\s+(\\w+)\\s*=\\s*((?:\\w+\\(\\w+\\)\\s*\\+?\\s*)+)");
    private static final Pattern SOURCE_VAR = Pattern.compile("source\\s*:\\s*\\{?\\s*src\\s*:\\s*(\\w+)", 2);
    private static final Pattern CALL_ARG = Pattern.compile("\\w+\\((\\w+)\\)");
    private static final Pattern EDGE_PAT = Pattern.compile("EDGE\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CHAN_PAT = Pattern.compile("CHANNEL\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SRC_PAT = Pattern.compile("SRC\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOKEN_PAT = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    // Fallback extraction patterns borrowed from dlhd-cli (Lunatic16/dlhd-cli),
    // which resolves the same DaddyLive pages: direct .m3u8, any atob() blob,
    // a player config file: entry, the legacy playerFrame iframe, and data-url.
    private static final Pattern DIRECT_M3U8 = Pattern.compile("(https?://[^\\s'\"<>]+\\.m3u8[^\\s'\"<>]*)", 2);
    private static final Pattern ANY_ATOB = Pattern.compile("atob\\(['\"]([A-Za-z0-9+/=]{20,})['\"]\\)");
    private static final Pattern FILE_PAT = Pattern.compile("file\\s*:\\s*['\"](https?://[^'\"]+)['\"]", 2);
    private static final Pattern PLAYER_FRAME = Pattern.compile("<iframe[^>]*id=['\"]playerFrame['\"][^>]*src=['\"]([^'\"]+)['\"]", 2);
    private static final Pattern DATA_URL = Pattern.compile("data-url=['\"]([^'\"]*stream[^'\"]*)['\"]", 2);

    private static final byte[] TRAW = "TIKTIKRAW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TPIX = "TIKTIKPX".getBytes(StandardCharsets.US_ASCII);

    private static final Map<String, CachedResolved> RESOLVE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, TokenSession> TOKEN_SESSIONS = new ConcurrentHashMap<>();

    private static final class CachedResolved {
        final Resolved resolved;
        // Sliding, not fixed (audit S2): every successful manifest
        // serve pushes the expiry out, so a healthy stream never hits
        // a mid-playback re-resolve cliff at T+15:00.
        volatile long expiresAt;
        CachedResolved(Resolved r, long exp) { this.resolved = r; this.expiresAt = exp; }
    }

    // Audit fix state (2026-10-31). Consecutive manifest failures per
    // channel: after 2, the resolve cache entry is dropped so a dead
    // edge is re-resolved instead of reused for 15 minutes (S1).
    private static final Map<String, Integer> MANIFEST_FAIL_STREAK = new ConcurrentHashMap<>();
    // Single-flight resolves + a short negative cache + last-good
    // route memory, so a cold zap walks the routes once, not once per
    // player retry (S13).
    private static final Map<String, java.util.concurrent.CompletableFuture<Resolved>> RESOLVE_INFLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, Long> RESOLVE_NEGATIVE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LAST_GOOD_ROUTE = new ConcurrentHashMap<>();
    // Hosts where HEAD is refused (per-method signing); skipped for a
    // while so a segment does not pay a doomed HEAD first (S15).
    private static final Map<String, Long> HEAD_FAILED_HOSTS = new ConcurrentHashMap<>();
    // External (sources-based) resolves are cached like numeric ones
    // instead of re-walking embed pages on every poll (S19).
    private static final Map<String, CachedResolved> EXT_CACHE = new ConcurrentHashMap<>();

    private static final class TokenSession {
        final String edge;
        final String chan;
        final String playerUrl;
        volatile String token;
        volatile long renewAt;
        volatile long lastRenewAttemptMs;

        TokenSession(String edge, String chan, String playerUrl, String token, long ttlSec) {
            this.edge = edge;
            this.chan = chan;
            this.playerUrl = playerUrl;
            this.token = token;
            long now = System.currentTimeMillis();
            this.renewAt = now + (ttlSec * 500L);
        }
    }

    private String getOrRenewToken(String channelId, TokenSession session, boolean force) {
        if (session == null || session.edge == null || session.chan == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!force && session.token != null && now < session.renewAt) {
            return session.token;
        }
        synchronized (session) {
            now = System.currentTimeMillis();
            if (!force && session.token != null && now < session.renewAt) {
                return session.token;
            }
            // Forced-renew discipline (audit S8): if the token changed
            // since the caller read it, or a renew was attempted in the
            // last 5 s, the caller's 403 is already being answered -
            // do not queue another network renew behind the lock.
            if (force && session.token != null && now - session.lastRenewAttemptMs < 5000L) {
                return session.token;
            }
            session.lastRenewAttemptMs = now;
            try {
                String tokenUrl = session.edge + "/api/playlist-token?slug=" + URLEncoder.encode(session.chan, StandardCharsets.UTF_8.name());
                okhttp3.Request req = request(tokenUrl, session.playerUrl).build();
                try (okhttp3.Response resp = this.segmentClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String body = resp.body().string();
                        Matcher m = TOKEN_PAT.matcher(body);
                        if (m.find()) {
                            session.token = m.group(1);
                            long ttl = 120L;
                            Matcher ttlM = Pattern.compile("\"ttl\"\\s*:\\s*(\\d+)").matcher(body);
                            if (ttlM.find()) {
                                ttl = Math.max(20L, Long.parseLong(ttlM.group(1)));
                            }
                            session.renewAt = now + (ttl * 500L);
                            Log.i(TAG, "Renewed playlist-token for channel " + channelId + " (valid for " + ttl + "s)");
                            Log.i("LiveProxyAudit", "token renewed id=" + channelId + " force=" + force);
                            return session.token;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to renew token for channel " + channelId + ": " + e.getMessage());
                // Back off on failure (audit S8): without this every
                // poll retries the renew and a dead channel pays a
                // forced renew plus retry on every manifest request.
                session.renewAt = System.currentTimeMillis() + 5000L;
            }
            return session.token;
        }
    }

    private static String cleanPlaylistUrl(String u) {
        int i = u.indexOf('?');
        if (i < 0) return u;
        String base = u.substring(0, i);
        String query = u.substring(i + 1);
        StringBuilder cleanQuery = new StringBuilder();
        for (String param : query.split("&")) {
            if (!param.isEmpty() && !param.startsWith("t=") && !param.startsWith("_=")) {
                if (cleanQuery.length() > 0) cleanQuery.append('&');
                cleanQuery.append(param);
            }
        }
        return cleanQuery.length() > 0 ? (base + "?" + cleanQuery.toString()) : base;
    }

    public OnDeviceStreamServer() {
        super("127.0.0.1", 48913);
        this.client = new OkHttpClient.Builder().connectTimeout(5L, TimeUnit.SECONDS).readTimeout(12L, TimeUnit.SECONDS).followRedirects(true).build();
        // A segment fetch sits on the player's critical path: the player
        // would rather learn about a dead segment in seconds and move on
        // than wait out the manifest retry policy (3 attempts, backoff
        // sleeps, 12 s reads - up to ~38 s for one segment, which drains
        // any buffer and reads on screen as a long freeze).
        // HTTP/1.1 with a real connection pool (audit S17): the
        // parallel-chunk design needs parallel TCP connections, and
        // HTTP/2 would multiplex every chunk over ONE connection - a
        // single stall would then stall the whole segment. Dispatcher
        // limits are gone: they only govern async enqueue(), and every
        // call here is a blocking execute().
        this.segmentClient = new OkHttpClient.Builder().connectTimeout(5L, TimeUnit.SECONDS).readTimeout(8L, TimeUnit.SECONDS).followRedirects(true)
                .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                .connectionPool(new okhttp3.ConnectionPool(16, 5L, TimeUnit.MINUTES))
                .build();
    }

    public static synchronized OnDeviceStreamServer ensureStarted() throws Exception {
        if (instance == null || !instance.isAlive()) {
            instance = new OnDeviceStreamServer();
            instance.start(5000, false);
        }
        return instance;
    }

    @Override // fi.iki.elonen.NanoHTTPD
    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session) {
        Resolved resolved;
        try {
            String uri = session.getUri();
            if (uri.equals("/health")) {
                return newFixedLengthResponse("ok");
            }
            Map<String, String> q = session.getParms();
            // Never log raw url=/sources= params: they can carry
            // tokens (audit S24).
            Log.i(TAG, "Request: uri=" + uri + ", id=" + q.get("id"));
            if (uri.endsWith("manifest.m3u8") || uri.contains("/manifest")) {
                String id = q.get("id");
                int player = parseInt(q.get("player"), 0);
                String upstream = q.get("url");
                String sources = q.get("sources");
                if (upstream == null) {
                    // A failed re-resolve must not kill a playing
                    // stream (audit S2): fall back to the last good
                    // manifest, then to the expired cache entry, and
                    // only then surface the error.
                    try {
                        if (id != null && !id.trim().isEmpty() && id.trim().matches("\\d+")) {
                            resolved = resolve(id.trim(), player);
                        } else if (sources != null && !sources.trim().isEmpty()) {
                            resolved = resolveExternal(sources, player);
                        } else {
                            throw new IllegalArgumentException("Missing id or sources");
                        }
                    } catch (Exception resolveError) {
                        NanoHTTPD.Response staleR = staleManifest(id, player);
                        if (staleR != null) {
                            return staleR;
                        }
                        throw resolveError;
                    }
                } else {
                    resolved = new Resolved(upstream, q.get("referer"), "");
                }
                if (upstream == null) {
                    upstream = resolved.url;
                }
                try {
                    return proxyManifest(id, player, upstream, resolved);
                } catch (Exception manifestError) {
                    noteManifestFailure(id, "proxyManifest threw");
                    NanoHTTPD.Response stale = staleManifest(id, player);
                    if (stale != null) {
                        return stale;
                    }
                    throw manifestError;
                }
            }
            if (uri.endsWith("/resource") || uri.contains("resource")) {
                return proxyResource(q.get("url"), q.get("referer"), q.get("id"));
            }
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found: " + uri);
        } catch (Exception error) {
            Log.e(TAG, "Proxy failure for " + session.getUri(), error);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, error.getMessage());
        }
    }

    private Resolved resolveExternal(String packedSources, int preferred) throws Exception {
        // Cached like numeric resolves (audit S19): non-numeric ids
        // used to re-walk embed pages on every manifest poll.
        String cacheKey = packedSources + ":" + preferred;
        CachedResolved hit = EXT_CACHE.get(cacheKey);
        if (hit != null && System.currentTimeMillis() < hit.expiresAt) {
            return hit.resolved;
        }
        try {
            Resolved r = resolveExternalWalk(packedSources, preferred);
            EXT_CACHE.put(cacheKey, new CachedResolved(r, System.currentTimeMillis() + 900_000L));
            return r;
        } catch (Exception e) {
            if (hit != null) {
                return hit.resolved;
            }
            throw e;
        }
    }

    private Resolved resolveExternalWalk(String packedSources, int preferred) throws Exception {
        String[] sources = packedSources.split("\\|");
        if (sources.length == 0) {
            throw new Exception("No external feeds");
        }
        Exception lastError = null;
        for (int i = 0; i < sources.length; i++) {
            int selected = (preferred + i) % sources.length;
            try {
                return resolveSingleExternal(sources[selected], selected);
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "External route " + selected + " failed: " + e.getMessage());
            }
        }
        throw (lastError != null ? lastError : new Exception("All external routes failed"));
    }

    private Resolved resolveSingleExternal(String pageUrl, int selected) throws Exception {
        if (pageUrl.startsWith("legacy:")) {
            String[] legacy = pageUrl.split(":");
            if (legacy.length == 3) {
                return resolve(legacy[1], parseInt(legacy[2], 0));
            }
            throw new Exception("Invalid legacy feed");
        }
        if (!pageUrl.startsWith("http")) {
            throw new Exception("Invalid external feed");
        }
        if (pageUrl.contains("hesgoal.team/") && pageUrl.contains("id=")) {
            String id = pageUrl.substring(pageUrl.indexOf("id=") + 3).split("&", 2)[0];
            pageUrl = "https://wideiptv.top/player/" + id;
        }
        String referer = pageUrl;
        int depth = 0;
        while (depth < 4) {
            if (pageUrl.contains(".m3u8")) {
                return new Resolved(pageUrl, referer, origin(referer));
            }
            String html = getText(pageUrl, depth == 0 ? null : referer);
            try {
                String streamUrl = extractStreamUrl(html);
                Log.i(TAG, "resolved external route=" + selected);
                return new Resolved(streamUrl, pageUrl, origin(pageUrl));
            } catch (Exception e) {
                Matcher frame = IFRAME.matcher(html);
                if (!frame.find()) {
                    throw new Exception("external player unavailable");
                }
                referer = pageUrl;
                pageUrl = new URL(new URL(pageUrl), frame.group(1)).toString();
                depth++;
            }
        }
        throw new Exception("external redirect limit reached");
    }

    static String extractStreamUrl(String html) throws Exception {
        Matcher stream = STREAM_URL.matcher(html == null ? "" : html);
        if (stream.find()) {
            return validateStreamUrl(stream.group(1).replace("\\/", "/"));
        }
        Matcher source = SOURCE.matcher(html == null ? "" : html);
        if (source.find()) {
            String value = source.group(1) != null ? source.group(1) : source.group(2);
            if (!value.contains(".m3u8")) {
                value = new String(decodeBase64(value), StandardCharsets.UTF_8);
            }
            return validateStreamUrl(value.replace("\\/", "/"));
        }
        Map<String, String> values = new HashMap<>();
        Matcher strings = STRING_VAR.matcher(html == null ? "" : html);
        while (strings.find()) {
            values.put(strings.group(1), strings.group(2));
        }
        Matcher wanted = SOURCE_VAR.matcher(html != null ? html : "");
        if (wanted.find()) {
            String outputName = wanted.group(1);
            Matcher joined = JOINED_VAR.matcher(html);
            while (joined.find()) {
                if (outputName.equals(joined.group(1))) {
                    StringBuilder decoded = new StringBuilder();
                    Matcher argument = CALL_ARG.matcher(joined.group(2));
                    while (argument.find()) {
                        String encoded = values.get(argument.group(1));
                        if (encoded == null) {
                            throw new Exception("missing stream fragment");
                        }
                        decoded.append(new String(decodeBase64(encoded), StandardCharsets.UTF_8));
                    }
                    return validateStreamUrl(decoded.toString());
                }
            }
        }
        // dlhd-cli fallback chain, in its order: direct .m3u8 anywhere in the
        // page, any other atob() blob that decodes to a URL, a player config
        // file: value, the legacy playerFrame iframe, then a data-url attr.
        String page = html == null ? "" : html;
        Matcher direct = DIRECT_M3U8.matcher(page);
        if (direct.find()) {
            return validateStreamUrl(direct.group(1).replace("\\/", "/"));
        }
        Matcher anyAtob = ANY_ATOB.matcher(page);
        while (anyAtob.find()) {
            try {
                String decoded = new String(decodeBase64(anyAtob.group(1)), StandardCharsets.UTF_8);
                Matcher inner = DIRECT_M3U8.matcher(decoded);
                if (inner.find()) {
                    return validateStreamUrl(inner.group(1));
                }
                if (decoded.startsWith("http")) {
                    return validateStreamUrl(decoded.trim());
                }
            } catch (Exception ignored) {
            }
        }
        Matcher file = FILE_PAT.matcher(page);
        if (file.find() && file.group(1).contains(".m3u8")) {
            return validateStreamUrl(file.group(1).replace("\\/", "/"));
        }
        Matcher frameSrc = PLAYER_FRAME.matcher(page);
        if (frameSrc.find()) {
            throw new Exception("playerFrame redirect: " + frameSrc.group(1));
        }
        Matcher dataUrl = DATA_URL.matcher(page);
        if (dataUrl.find() && dataUrl.group(1).contains(".m3u8")) {
            return validateStreamUrl(dataUrl.group(1).replace("\\/", "/"));
        }
        throw new Exception("stream URL unavailable");
    }

    private static String validateStreamUrl(String value) throws Exception {
        if (value == null || !value.startsWith("http") || !value.contains(".m3u8")) {
            throw new Exception("invalid stream URL");
        }
        return value;
    }

    private static byte[] decodeBase64(String encoded) throws Exception {
        char c;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < encoded.length() && (c = encoded.charAt(i)) != '='; i++) {
            if (c == '-') {
                c = '+';
            }
            if (c == '_') {
                c = '/';
            }
            int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf(c);
            if (value >= 0) {
                buffer = (buffer << 6) | value;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    output.write((buffer >> bits) & 255);
                }
            }
        }
        int i2 = output.size();
        if (i2 == 0) {
            throw new Exception("invalid base64");
        }
        return output.toByteArray();
    }

    private static String origin(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private Resolved resolve(String id, int preferred) throws Exception {
        if (id == null || !id.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid channel");
        }
        String cacheKey = id + ":" + preferred;
        long now = System.currentTimeMillis();
        CachedResolved cached = RESOLVE_CACHE.get(cacheKey);
        if (cached != null && now < cached.expiresAt) {
            return cached.resolved;
        }
        // Negative cache (audit S13): a channel that just failed to
        // resolve does not get a fresh multi-route walk on every poll.
        Long negAt = RESOLVE_NEGATIVE.get(cacheKey);
        if (cached == null && negAt != null && now - negAt < 10_000L) {
            throw new Exception("Resolve recently failed for channel " + id);
        }
        // Single-flight (audit S13): concurrent polls for the same
        // channel share one walk instead of spawning their own.
        java.util.concurrent.CompletableFuture<Resolved> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Resolved> existing = RESOLVE_INFLIGHT.putIfAbsent(cacheKey, mine);
        if (existing != null) {
            try {
                return existing.get(60L, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (cached != null) {
                    return cached.resolved;
                }
                throw new Exception("Shared resolve failed for channel " + id, e);
            }
        }
        try {
            Resolved res = resolveWalk(id, preferred, cacheKey);
            mine.complete(res);
            return res;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            if (cached == null) {
                RESOLVE_NEGATIVE.put(cacheKey, System.currentTimeMillis());
            } else {
                // Keep the expired entry rather than erroring the
                // poll (audit S2): its manifest may still serve.
                return cached.resolved;
            }
            throw e;
        } finally {
            RESOLVE_INFLIGHT.remove(cacheKey, mine);
        }
    }

    private Resolved resolveWalk(String id, int preferred, String cacheKey) throws Exception {
        // Route memory (audit S13): start from the route that worked
        // last time for this channel instead of always from route 0.
        Integer lastGood = LAST_GOOD_ROUTE.get(id);
        int start = lastGood != null ? lastGood : preferred;
        long walkStart = System.currentTimeMillis();
        Exception lastError = null;
        for (int i = 0; i < ROUTES.length; i++) {
            int player = (start + i) % ROUTES.length;
            // Overall walk deadline: the old worst case was 8 routes
            // times minutes of retries (audit S13).
            if (i > 0 && System.currentTimeMillis() - walkStart > 45_000L) {
                break;
            }
            try {
                Resolved res = resolveSingle(id, player);
                // 15 minutes, sliding on every successful serve. The
                // playlist token is renewed separately by TokenSession,
                // and repeated manifest failures drop the entry (S1),
                // so a long cache is safe and zapping back to a recent
                // channel stays fast instead of re-walking embeds.
                RESOLVE_CACHE.put(cacheKey, new CachedResolved(res, System.currentTimeMillis() + 900_000L));
                LAST_GOOD_ROUTE.put(id, player);
                RESOLVE_NEGATIVE.remove(cacheKey);
                pruneCaches(id);
                return res;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Route " + ROUTES[player] + " failed for channel " + id + ": " + e.getMessage());
            }
        }
        throw (lastError != null ? lastError : new Exception("All routes failed for " + id));
    }

    // Failure/success bookkeeping for the resolve cache (audit S1).
    private void noteManifestFailure(String id, String why) {
        if (id == null) {
            return;
        }
        int streak = MANIFEST_FAIL_STREAK.merge(id, 1, Integer::sum);
        Log.w("LiveProxyAudit", "manifest failure id=" + id + " streak=" + streak + " (" + why + ")");
        if (streak >= 2) {
            // The cached edge/src is dead: drop it so the next poll
            // re-resolves. TOKEN_SESSIONS is deliberately kept - its
            // timeline continuity is what the stale manifest protects.
            RESOLVE_CACHE.keySet().removeIf(k -> k.startsWith(id + ":"));
            MANIFEST_FAIL_STREAK.remove(id);
        }
    }

    private void noteManifestSuccess(String id) {
        if (id != null) {
            MANIFEST_FAIL_STREAK.remove(id);
        }
    }

    // Opportunistic eviction so the static caches cannot grow forever
    // (audit S22).
    private void pruneCaches(String keepId) {
        long now = System.currentTimeMillis();
        if (RESOLVE_CACHE.size() > 64) {
            RESOLVE_CACHE.entrySet().removeIf(e -> now > e.getValue().expiresAt);
        }
        if (TOKEN_SESSIONS.size() > 64) {
            TOKEN_SESSIONS.entrySet().removeIf(e -> !e.getKey().equals(keepId) && now > e.getValue().renewAt + 1_800_000L);
        }
        if (LAST_GOOD_MANIFEST.size() > 48) {
            LAST_GOOD_MANIFEST.keySet().removeIf(k -> !k.startsWith(keepId + ":"));
            LAST_GOOD_MANIFEST_AT.keySet().removeIf(k -> !k.startsWith(keepId + ":"));
            PDT_ANCHOR.keySet().removeIf(k -> !k.startsWith(keepId + ":"));
            SEG_DURATIONS.keySet().removeIf(k -> !k.startsWith(keepId + ":"));
        }
    }

    private Resolved resolveSingle(String id, int player) throws Exception {
        // A fresh resolve must not inherit a token session minted on
        // another route (audit S9): its token would be appended to
        // this route's manifest URL.
        TOKEN_SESSIONS.remove(id);
        String embed = playerPageUrl(id, player);
        String watch = (player == 0) ? StreamEndpoints.NTVS_WATCH + id : StreamEndpoints.watchUrl(StreamEndpoints.PRIMARY, id);
        String embedHtml;
        try {
            embedHtml = getText(embed, watch);
        } catch (Exception firstFailure) {
            // The primary host is not the only base (audit S14): the
            // domain has hopped before, so walk the mirror bases for
            // the embed page before giving up on this route.
            embedHtml = null;
            for (String base : StreamEndpoints.BASES) {
                if (embed.startsWith(base)) {
                    continue;
                }
                String alt = playerPageUrlOn(base, id, player);
                try {
                    embedHtml = getText(alt, watch);
                    embed = alt;
                    break;
                } catch (Exception ignored) {
                }
            }
            if (embedHtml == null) {
                throw firstFailure;
            }
        }
        Matcher frame = IFRAME.matcher(embedHtml);
        if (!frame.find()) {
            throw new Exception("player page unavailable on " + ROUTES[player]);
        }
        String playerUrl = new URL(new URL(embed), frame.group(1)).toString();
        String playerHtml = getText(playerUrl, embed);

        Matcher edgeM = EDGE_PAT.matcher(playerHtml);
        Matcher chanM = CHAN_PAT.matcher(playerHtml);
        Matcher srcM = SRC_PAT.matcher(playerHtml);
        if (edgeM.find() && chanM.find() && srcM.find()) {
            String edge = edgeM.group(1);
            String chan = chanM.group(1);
            String src = srcM.group(1);
            String tokenUrl = edge + "/api/playlist-token?slug=" + URLEncoder.encode(chan, StandardCharsets.UTF_8.name());
            okhttp3.Request tokenReq = request(tokenUrl, playerUrl).build();
            try (okhttp3.Response tokResp = this.client.newCall(tokenReq).execute()) {
                if (tokResp.isSuccessful() && tokResp.body() != null) {
                    String tokJson = tokResp.body().string();
                    Matcher tokM = TOKEN_PAT.matcher(tokJson);
                    if (tokM.find()) {
                        String token = tokM.group(1);
                        long ttl = 120L;
                        Matcher ttlM = Pattern.compile("\"ttl\"\\s*:\\s*(\\d+)").matcher(tokJson);
                        if (ttlM.find()) {
                            ttl = Math.max(20L, Long.parseLong(ttlM.group(1)));
                        }
                        // Encode the token here exactly as everywhere
                        // else (audit S26): a raw + / = in it made
                        // this route test fail spuriously.
                        String manifestUrl = src + (src.contains("?") ? "&" : "?") + "t=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name()) + "&_=" + System.currentTimeMillis();
                        okhttp3.Request testReq = request(manifestUrl, playerUrl).build();
                        try (okhttp3.Response testResp = this.client.newCall(testReq).execute()) {
                            if (!testResp.isSuccessful()) {
                                throw new Exception("Route " + ROUTES[player] + " returned HTTP " + testResp.code());
                            }
                        }
                        TOKEN_SESSIONS.put(id, new TokenSession(edge, chan, playerUrl, token, ttl));
                        Log.i("LiveProxyAudit", "session created id=" + id + " edge=" + edge + " ttl=" + ttl);
                        Log.i(TAG, "resolved token channelId=" + id + " route=" + ROUTES[player]);
                        return new Resolved(manifestUrl, playerUrl, origin(playerUrl));
                    }
                }
            }
        }

        Matcher source = SOURCE.matcher(playerHtml);
        if (!source.find()) {
            throw new Exception("stream token unavailable on " + ROUTES[player]);
        }
        String encoded = source.group(source.group(1) == null ? 2 : 1);
        String url = encoded.contains(".m3u8") ? encoded : new String(decodeBase64(encoded), StandardCharsets.UTF_8);
        if (!url.startsWith("http")) {
            throw new Exception("invalid stream URL");
        }
        Log.i(TAG, "resolved channelId=" + id + " route=" + ROUTES[player]);
        return new Resolved(url, playerUrl, origin(playerUrl));
    }

    private NanoHTTPD.Response proxyManifest(String id, int player, String upstream, Resolved resolved) throws Exception {
        String effectiveUrl = upstream;
        TokenSession session = (id != null) ? TOKEN_SESSIONS.get(id) : null;
        if (session != null) {
            String token = getOrRenewToken(id, session, false);
            if (token != null && !token.isEmpty()) {
                effectiveUrl = cleanPlaylistUrl(upstream);
                effectiveUrl += (effectiveUrl.contains("?") ? "&" : "?") + "t=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name()) + "&_=" + System.currentTimeMillis();
            }
        }

        Request request = request(effectiveUrl, resolved.referer).build();
        okhttp3.Response response = null;
        try {
            response = executeOnce(request);
            if (response.code() == 403 && session != null) {
                response.close();
                Log.w(TAG, "Upstream returned 403 for channel " + id + ", forcing token refresh and retry...");
                String freshToken = getOrRenewToken(id, session, true);
                if (freshToken != null && !freshToken.isEmpty()) {
                    effectiveUrl = cleanPlaylistUrl(upstream);
                    effectiveUrl += (effectiveUrl.contains("?") ? "&" : "?") + "t=" + URLEncoder.encode(freshToken, StandardCharsets.UTF_8.name()) + "&_=" + System.currentTimeMillis();
                    request = request(effectiveUrl, resolved.referer).build();
                    response = executeOnce(request);
                }
            }

            if (!response.isSuccessful() || response.body() == null) {
                int code = response.code();
                response.close();
                noteManifestFailure(id, "HTTP " + code);
                Log.w("LiveProxyAudit", "manifest FAILED id=" + id + " http=" + code + " - keeping session, trying last-good manifest");
                NanoHTTPD.Response stale = staleManifest(id, player);
                if (stale != null) {
                    return stale;
                }
                throw new Exception("Manifest HTTP " + code);
            }

            String manifestBody = response.body().string();
            // The web player validates the same thing (REA notes, 2026-10-30):
            // a 200 response that is not a playlist is an error page in
            // disguise, and handing it to ExoPlayer just stalls playback.
            if (!manifestBody.trim().startsWith("#EXTM3U")) {
                response.close();
                noteManifestFailure(id, "non-playlist body");
                Log.w("LiveProxyAudit", "manifest BAD BODY id=" + id + " - keeping session, trying last-good manifest");
                NanoHTTPD.Response stale2 = staleManifest(id, player);
                if (stale2 != null) {
                    return stale2;
                }
                throw new Exception("Upstream returned a non-playlist body for channel " + id);
            }
            java.util.regex.Matcher seqM = java.util.regex.Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(manifestBody);
            Log.i("LiveProxyAudit", "manifest served id=" + id + " seq=" + (seqM.find() ? seqM.group(1) : "?"));
            // Synthetic PDT for the first segment of this playlist.
            String pdtLine = null;
            if (id != null) {
                java.util.regex.Matcher seqM2 = java.util.regex.Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(manifestBody);
                if (seqM2.find()) {
                    // Long, not int (audit S12): media sequences pass
                    // 2^31 on long-lived channels and parseInt threw
                    // the whole manifest away.
                    long firstSeq = Long.parseLong(seqM2.group(1));
                    java.util.List<Double> durs = new ArrayList<>();
                    for (String rawLine : manifestBody.split("\\r?\\n")) {
                        String t = rawLine.trim();
                        if (t.startsWith("#EXTINF:")) {
                            try {
                                // A comma-less EXTINF still carries a
                                // duration (audit S12): dropping it
                                // desynchronised durations from
                                // sequence numbers.
                                int comma = t.indexOf(',');
                                durs.add(Double.parseDouble(t.substring(8, comma < 0 ? t.length() : comma)));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!durs.isEmpty()) {
                        String key = id + ":" + player;
                        Map<Long, Double> known = SEG_DURATIONS.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                        for (int i = 0; i < durs.size(); i++) {
                            known.put(firstSeq + i, durs.get(i));
                        }
                        known.keySet().removeIf(s -> s < firstSeq - 120);
                        double totalDur = 0;
                        for (double d : durs) totalDur += d;
                        long nowMs = System.currentTimeMillis();
                        long liveEdgeStartMs = nowMs - Math.round((totalDur - durs.get(0)) * 1000.0);
                        long[] anchor = PDT_ANCHOR.get(key);
                        if (anchor == null || firstSeq < anchor[1]) {
                            anchor = new long[]{liveEdgeStartMs, firstSeq};
                            PDT_ANCHOR.put(key, anchor);
                        }
                        double acc = 0;
                        if (firstSeq - anchor[1] > 1000L) {
                            // Timeline jump (audit S12): never spin
                            // summing a billion phantom segments on
                            // the request thread - just re-anchor.
                            anchor = new long[]{liveEdgeStartMs, firstSeq};
                            PDT_ANCHOR.put(key, anchor);
                        } else {
                            for (long s = anchor[1]; s < firstSeq; s++) {
                                Double d = known.get(s);
                                acc += (d != null ? d : durs.get(0));
                            }
                        }
                        long firstSegStartMs = anchor[0] + Math.round(acc * 1000.0);
                        // 20 s, not 45 (audit S12): a bigger jump
                        // exceeds the ~36 s window and made PDT
                        // non-monotonic.
                        if (Math.abs(firstSegStartMs - liveEdgeStartMs) > 20000L) {
                            anchor = new long[]{liveEdgeStartMs, firstSeq};
                            PDT_ANCHOR.put(key, anchor);
                            firstSegStartMs = liveEdgeStartMs;
                        }
                        pdtLine = "#EXT-X-PROGRAM-DATE-TIME:" + isoUtc(firstSegStartMs);
                    }
                }
            }
            // Resolve relatives against the URL that actually
            // answered (audit S10): the requested URL still carries
            // the t=/_= query and the pre-redirect host.
            String finalUrl = response.request().url().toString();
            int qIdx = finalUrl.indexOf('?');
            if (qIdx >= 0) {
                finalUrl = finalUrl.substring(0, qIdx);
            }
            String base = finalUrl.substring(0, finalUrl.lastIndexOf('/') + 1);
            Pattern uriAttr = Pattern.compile("URI=\"([^\"]+)\"");
            StringBuilder output = new StringBuilder();
            List<String> segmentUrls = new ArrayList<>();
            boolean pdtEmitted = false;
            for (String raw : manifestBody.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                    continue; // Upstream PDT drifts in bursts; replaced by the synthetic one below
                }
                if (pdtLine != null && !pdtEmitted && line.startsWith("#EXTINF:")) {
                    output.append(pdtLine).append('\n');
                    pdtEmitted = true;
                }
                if (line.contains("URI=\"")) {
                    Matcher key = uriAttr.matcher(line);
                    if (key.find()) {
                        String target = absolute(base, key.group(1));
                        // A URI attribute can point at another
                        // playlist (EXT-X-MEDIA, I-FRAME): proxy it as
                        // a manifest, not as a video segment (S11).
                        String proxied = target.contains(".m3u8")
                                ? manifestUrl(id, player, target, resolved.referer)
                                : resourceUrl(target, resolved.referer, id);
                        line = key.replaceFirst("URI=\"" + Matcher.quoteReplacement(proxied) + "\"");
                    }
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    String absolute = absolute(base, line);
                    if (absolute.contains(".m3u8")) {
                        line = manifestUrl(id, player, absolute, resolved.referer);
                    } else {
                        segmentUrls.add(absolute);
                        line = resourceUrl(absolute, resolved.referer, id);
                    }
                }
                output.append(line).append('\n');
            }
            if (id != null) {
                LAST_GOOD_MANIFEST.put(id + ":" + player, output.toString());
                LAST_GOOD_MANIFEST_AT.put(id + ":" + player, System.currentTimeMillis());
                noteManifestSuccess(id);
                // Sliding resolve TTL (audit S2).
                CachedResolved cr = RESOLVE_CACHE.get(id + ":" + player);
                if (cr != null) {
                    cr.expiresAt = System.currentTimeMillis() + 900_000L;
                }
            }
            NanoHTTPD.Response manifestResp = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", output.toString());
            manifestResp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            manifestResp.addHeader("Pragma", "no-cache");
            manifestResp.addHeader("Expires", "0");
            // Warm the segment prefetch cache for the channel actually
            // being watched (see the prefetch block above proxyResource).
            if (id != null && !segmentUrls.isEmpty()) {
                // The newest manifest served IS the channel being
                // watched (audit S21): the old first-come-sticky id
                // left zapped-to channels with no prefetch at all.
                activeChannelId = id;
                prefetchTail(segmentUrls, resolved.referer, id);
            }
            return manifestResp;
        } finally {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
            }
        }
    }

    // Last successfully rewritten manifest per channel+route. Served
    // when the upstream manifest path fails transiently: a dropped
    // token session used to force a full re-resolve whose new edge
    // could carry a DIFFERENT playlist timeline, and the player's
    // position would plunge negative and never recover. Session state
    // is therefore never discarded on a manifest failure, and the
    // player rides out the failure on the last good playlist.
    // Synthetic program-date-time state, per channel+route. The
    // upstream PDT cannot be trusted (it advances in bursts, up to
    // 1.25x wall clock), but stripping PDT entirely leaves Media3
    // with no live offset at all: currentLiveOffset stayed TIME_UNSET
    // on device, which disabled both the live-offset logic and the
    // live playback speed control. So the proxy synthesizes a PDT
    // from the media sequence and the real EXTINF durations it has
    // served: monotonic, media-accurate, wall-clock anchored once.
    private static final Map<String, long[]> PDT_ANCHOR = new ConcurrentHashMap<>();
    private static final Map<String, Map<Long, Double>> SEG_DURATIONS = new ConcurrentHashMap<>();

    private static String isoUtc(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private static final Map<String, String> LAST_GOOD_MANIFEST = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_GOOD_MANIFEST_AT = new ConcurrentHashMap<>();

    private NanoHTTPD.Response staleManifest(String id, int player) {
        if (id == null) {
            return null;
        }
        String body = LAST_GOOD_MANIFEST.get(id + ":" + player);
        Long at = LAST_GOOD_MANIFEST_AT.get(id + ":" + player);
        if (body == null || at == null || System.currentTimeMillis() - at > 25000L) {
            return null;
        }
        Log.w("LiveProxyAudit", "serving last-good manifest id=" + id + " ageMs=" + (System.currentTimeMillis() - at));
        NanoHTTPD.Response resp = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", body);
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.addHeader("Pragma", "no-cache");
        resp.addHeader("Expires", "0");
        return resp;
    }

    // -- Segment prefetch (bounded, player-first) --------------------------
    // Why bounded: the first prefetch build (4.2.9) queued tail segments
    // at every manifest serve with no cap, and the player blocked up to
    // 30 s on futures queued behind stale work - under the slow image
    // CDN the queue collapsed within a minute (SocketTimeout at the
    // proxy, 150 MB of cached segment bytes, picture frozen solid).
    // The discipline here: at most PREFETCH_AHEAD segments are ever
    // warmed, only the ones at the live edge of the latest manifest;
    // queued warming for segments the window has passed is dropped from
    // the queue at the next manifest serve; finished segments live in a
    // small LRU; and a segment the player asks for is fetched INLINE on
    // the player's own request thread whenever it is not already in
    // flight - the player never queues behind warming work.
    private static final int PREFETCH_AHEAD = 3;
    private static final int SEGMENT_CACHE_MAX = 12;
    private static final Map<String, byte[]> SEGMENT_CACHE = new java.util.LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > SEGMENT_CACHE_MAX;
        }
    };
    private static final Map<String, java.util.concurrent.CompletableFuture<byte[]>> SEGMENT_INFLIGHT = new ConcurrentHashMap<>();
    private static final java.util.concurrent.LinkedBlockingQueue<Runnable> PREFETCH_QUEUE = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final java.util.concurrent.ExecutorService PREFETCH_POOL = new java.util.concurrent.ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS, PREFETCH_QUEUE,
            r -> { Thread t = new Thread(r, "seg-prefetch"); t.setDaemon(true); return t; });
    /** Channel the player is actually consuming segments for right now. */
    private static volatile String activeChannelId = null;

    private static final class PrefetchTask implements Runnable {
        final OnDeviceStreamServer server;
        final String url;
        final String referer;
        final String channelId;
        final java.util.concurrent.CompletableFuture<byte[]> future = new java.util.concurrent.CompletableFuture<>();
        PrefetchTask(OnDeviceStreamServer s, String u, String r, String c) {
            server = s; url = u; referer = r; channelId = c;
        }
        @Override public void run() {
            try {
                byte[] bytes = server.fetchSegmentBytes(url, referer, channelId, false);
                synchronized (SEGMENT_CACHE) { SEGMENT_CACHE.put(url, bytes); }
                future.complete(bytes);
            } catch (Throwable e) {
                // Catch Throwable, not Exception (audit S18): an
                // OutOfMemoryError inside unwrap used to leave this
                // future uncompleted forever, and the player blocked
                // on it for the full wait.
                Log.w(TAG, "Prefetch failed for channel " + channelId, e);
                future.completeExceptionally(e);
            } finally {
                SEGMENT_INFLIGHT.remove(url, future);
            }
        }
    }

    private void prefetchTail(List<String> segmentUrls, String referer, String channelId) {
        int from = Math.max(0, segmentUrls.size() - PREFETCH_AHEAD);
        java.util.Set<String> wanted = new java.util.HashSet<>(segmentUrls.subList(from, segmentUrls.size()));
        // Drop queued warming for segments the live window has
        // passed - and unregister them (audit S3): a purged task used
        // to leave its future in SEGMENT_INFLIGHT forever, so the
        // player blocked its full wait on a future that could never
        // complete, exactly when the pool was stalled.
        PREFETCH_QUEUE.removeIf(t -> {
            if (t instanceof PrefetchTask && !wanted.contains(((PrefetchTask) t).url)) {
                PrefetchTask pt = (PrefetchTask) t;
                SEGMENT_INFLIGHT.remove(pt.url, pt.future);
                pt.future.cancel(true);
                return true;
            }
            return false;
        });
        // Cached segments outside the window are dead weight.
        synchronized (SEGMENT_CACHE) {
            SEGMENT_CACHE.keySet().removeIf(k -> !segmentUrls.contains(k));
        }
        for (int i = from; i < segmentUrls.size(); i++) {
            String u = segmentUrls.get(i);
            synchronized (SEGMENT_CACHE) { if (SEGMENT_CACHE.containsKey(u)) continue; }
            if (SEGMENT_INFLIGHT.containsKey(u)) continue;
            PrefetchTask task = new PrefetchTask(this, u, referer, channelId);
            SEGMENT_INFLIGHT.put(u, task.future);
            PREFETCH_POOL.execute(task);
        }
    }

    private NanoHTTPD.Response segmentResponse(byte[] bytes) {
        // No Accept-Ranges header (audit S20): Range requests are not
        // honoured here, and advertising them breaks BYTERANGE
        // playlists.
        return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp2t", new ByteArrayInputStream(bytes), bytes.length);
    }

    private NanoHTTPD.Response proxyResource(String url, String referer, String channelId) throws Exception {
        if (channelId != null && !channelId.isEmpty()) {
            activeChannelId = channelId;
        }
        byte[] hit;
        synchronized (SEGMENT_CACHE) { hit = SEGMENT_CACHE.get(url); }
        if (hit != null) {
            return segmentResponse(hit);
        }
        java.util.concurrent.CompletableFuture<byte[]> pending = SEGMENT_INFLIGHT.get(url);
        if (pending != null && !pending.isDone() && !pending.isCancelled()) {
            try {
                // 4 s, not 12 (audit S3): past that, fetching inline
                // beats waiting on a possibly stalled prefetch.
                byte[] bytes = pending.get(4L, TimeUnit.SECONDS);
                if (bytes != null && bytes.length > 0) {
                    return segmentResponse(bytes);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // The warmed copy failed or is too slow; fetch directly.
            }
        }
        // Player-first: fetch on this request thread, never queued
        // behind prefetch work - and register the fetch (audit S7) so
        // a duplicate request joins it instead of starting a second
        // full parallel download of the same segment.
        java.util.concurrent.CompletableFuture<byte[]> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<byte[]> raced = SEGMENT_INFLIGHT.putIfAbsent(url, mine);
        if (raced != null && !raced.isDone() && !raced.isCancelled()) {
            try {
                byte[] bytes = raced.get(4L, TimeUnit.SECONDS);
                if (bytes != null && bytes.length > 0) {
                    return segmentResponse(bytes);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Joining failed; fall through and fetch inline.
            }
        }
        boolean registered = raced == null;
        try {
            byte[] bytes = fetchSegmentBytes(url, referer, channelId);
            synchronized (SEGMENT_CACHE) { SEGMENT_CACHE.put(url, bytes); }
            if (registered) {
                mine.complete(bytes);
            }
            return segmentResponse(bytes);
        } catch (Exception e) {
            if (registered) {
                mine.completeExceptionally(e);
            }
            throw e;
        } finally {
            if (registered) {
                SEGMENT_INFLIGHT.remove(url, mine);
            }
        }
    }

    private static final class HttpException extends Exception {
        final int code;
        HttpException(int c) { super("HTTP " + c); code = c; }
    }

    private static final int CHUNK_SIZE = 256 * 1024;
    private static final java.util.concurrent.ExecutorService CHUNK_POOL = new java.util.concurrent.ThreadPoolExecutor(
            6, 6, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "seg-chunk"); t.setDaemon(true); return t; });
    // Player-first chunks run on their own pool: the segment the player
    // is waiting for must never sit in a queue behind prefetch chunks.
    private static final java.util.concurrent.ExecutorService PLAYER_CHUNK_POOL = new java.util.concurrent.ThreadPoolExecutor(
            6, 6, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "seg-chunk-player"); t.setDaemon(true); return t; });

    /**
     * Download one segment body. Measured root problem (2026-10-30,
     * bench-verified): the image CDN's per-connection delivery is a
     * lottery - usually 2-7 s for a ~6 s segment, sometimes a mid-body
     * stall of 40 s+. A sequential consumer drawing one connection at
     * a time starves within minutes at 0.35-0.6x real time no matter
     * where it sits in the window. The CDN honors Range requests
     * (verified 206), so each segment is pulled as ~256 KB chunks over
     * several parallel connections: no single stalled connection can
     * hold a segment hostage, and a chunk that times out is re-issued
     * once on a fresh connection while the others stand.
     */
    private byte[] downloadRaw(String url, String referer, boolean playerPriority) throws Exception {
        java.util.concurrent.ExecutorService pool = playerPriority ? PLAYER_CHUNK_POOL : CHUNK_POOL;
        long total = -1L;
        // HEAD discipline (audit S15): a CDN that signs per method
        // 403s HEAD - that used to be fatal and triggered a token
        // renew plus another doomed HEAD. HEAD failure now means "no
        // usable HEAD", the host is remembered, and the segment goes
        // straight to the range probe.
        String host = hostOf(url);
        Long headFailedAt = HEAD_FAILED_HOSTS.get(host);
        boolean skipHead = headFailedAt != null && System.currentTimeMillis() - headFailedAt < 600_000L;
        if (!skipHead) {
            okhttp3.Call head = this.segmentClient.newCall(request(url, referer).head().build());
            head.timeout().timeout(6L, TimeUnit.SECONDS);
            try (okhttp3.Response resp = head.execute()) {
                if (resp.code() >= 400) {
                    HEAD_FAILED_HOSTS.put(host, System.currentTimeMillis());
                } else if (resp.isSuccessful()) {
                    total = resp.header("Content-Length") != null ? Long.parseLong(resp.header("Content-Length")) : -1L;
                }
            } catch (Exception ignored) {
                // No usable HEAD: fall through to the range-probe flow.
            }
        }
        if (total > CHUNK_SIZE) {
            return collectChunks(url, referer, pool, total, null);
        }
        // Small body or no HEAD: range-probe flow (first chunk doubles
        // as the probe), or a plain stream if ranges are refused.
        Request firstReq = request(url, referer).header("Range", "bytes=0-" + (CHUNK_SIZE - 1)).build();
        try (okhttp3.Response resp = executeOnce(firstReq)) {
            if (resp.code() == 403) {
                throw new HttpException(403);
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new HttpException(resp.code());
            }
            if (resp.code() == 200) {
                return resp.body().bytes();
            }
            String cr = resp.header("Content-Range");
            long probedTotal = -1L;
            if (cr != null && cr.contains("/")) {
                try {
                    probedTotal = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                } catch (Exception ignored) {
                }
            }
            byte[] first = resp.body().bytes();
            if (probedTotal < 0L) {
                // The probe could not report a total (audit S5): this
                // body may be only the first 256 KB of the segment,
                // and returning it as complete poisoned the cache.
                // Re-issue a plain GET for the whole body.
                Request fullReq = request(url, referer).build();
                try (okhttp3.Response full = executeOnce(fullReq)) {
                    if (full.code() == 403) {
                        throw new HttpException(403);
                    }
                    if (!full.isSuccessful() || full.body() == null) {
                        throw new HttpException(full.code());
                    }
                    return full.body().bytes();
                }
            }
            if (probedTotal <= first.length) {
                return first;
            }
            return collectChunks(url, referer, pool, probedTotal, first);
        }
    }

    /**
     * Fetch all chunks of one segment in parallel and assemble them.
     * A chunk whose connection has not delivered in 3 s is hedged: a
     * second connection races the same range and the winner is kept.
     * On this CDN a stalled connection is routine, and waiting out an
     * 8 s timeout per chunk was the difference between a 2 s segment
     * and a 16 s one.
     */
    /** Thrown when a server answers a Range request with 200: ranges
     * are ignored there, so the segment must be fetched whole (S16). */
    private static final class RangeIgnoredException extends Exception {
    }

    /** One chunk fetch with its Call exposed, so a loser can be
     * cancelled at the socket (audit S6): Future.cancel(true) alone
     * cannot interrupt a blocking OkHttp read. */
    private final class ChunkFetch implements java.util.concurrent.Callable<byte[]> {
        final String url;
        final String referer;
        final long start;
        final long end;
        final long startedAt = System.currentTimeMillis();
        volatile okhttp3.Call call;

        ChunkFetch(String u, String r, long s, long e) {
            url = u; referer = r; start = s; end = e;
        }

        void cancelCall() {
            okhttp3.Call c = call;
            if (c != null) {
                c.cancel();
            }
        }

        @Override public byte[] call() throws Exception {
            Request req = request(url, referer).header("Range", "bytes=" + start + "-" + end).build();
            okhttp3.Call c = segmentClient.newCall(req);
            call = c;
            c.timeout().timeout(8L, TimeUnit.SECONDS);
            try (okhttp3.Response resp = c.execute()) {
                // 403 must stay an HttpException so the token-refresh
                // path in fetchSegmentBytesInner runs (audit S16).
                if (resp.code() == 403) {
                    throw new HttpException(403);
                }
                if (resp.code() == 200) {
                    throw new RangeIgnoredException();
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new HttpException(resp.code());
                }
                byte[] data = resp.body().bytes();
                if (data.length != end - start + 1L) {
                    throw new Exception("short chunk: " + data.length + " of " + (end - start + 1L));
                }
                return data;
            }
        }
    }

    private byte[] plainGetSegment(String url, String referer) throws Exception {
        Request req = request(url, referer).build();
        try (okhttp3.Response resp = executeOnce(req)) {
            if (resp.code() == 403) {
                throw new HttpException(403);
            }
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new HttpException(resp.code());
            }
            return resp.body().bytes();
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    private byte[] collectChunks(String url, String referer, java.util.concurrent.ExecutorService pool, long total, byte[] firstChunk) throws Exception {
        int chunkCount = (int) ((total + CHUNK_SIZE - 1) / CHUNK_SIZE);
        byte[][] parts = new byte[chunkCount][];
        int firstIdx = 0;
        if (firstChunk != null) {
            parts[0] = firstChunk;
            firstIdx = 1;
        }
        List<ChunkFetch> jobs = new ArrayList<>(chunkCount);
        List<java.util.concurrent.Future<byte[]>> futures = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            jobs.add(null);
            futures.add(null);
        }
        for (int i = firstIdx; i < chunkCount; i++) {
            ChunkFetch job = new ChunkFetch(url, referer, (long) i * CHUNK_SIZE, Math.min(total - 1L, (long) i * CHUNK_SIZE + CHUNK_SIZE - 1L));
            jobs.set(i, job);
            futures.set(i, pool.submit(job));
        }
        try {
            for (int i = firstIdx; i < chunkCount; i++) {
                ChunkFetch job = jobs.get(i);
                // The hedge clock starts when the chunk STARTS, not
                // when this loop reaches it (audit S6): queued chunks
                // used to look instantly "stalled" and spawn duplicate
                // hedges behind the same queue.
                long waitMs = Math.max(1L, job.startedAt + 3000L - System.currentTimeMillis());
                try {
                    parts[i] = futures.get(i).get(waitMs, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    ChunkFetch hedgeJob = new ChunkFetch(url, referer, job.start, job.end);
                    java.util.concurrent.Future<byte[]> hedge = pool.submit(hedgeJob);
                    try {
                        parts[i] = hedge.get(9L, TimeUnit.SECONDS);
                        futures.get(i).cancel(true);
                        job.cancelCall();
                    } catch (Exception hedgeFailed) {
                        hedge.cancel(true);
                        hedgeJob.cancelCall();
                        parts[i] = futures.get(i).get(12L, TimeUnit.SECONDS);
                    }
                } catch (java.util.concurrent.ExecutionException ee) {
                    if (ee.getCause() instanceof RangeIgnoredException) {
                        // Ranges are ignored on this host: one plain
                        // GET for the whole segment (audit S16).
                        return plainGetSegment(url, referer);
                    }
                    if (ee.getCause() instanceof HttpException) {
                        throw (HttpException) ee.getCause();
                    }
                    // A quick chunk failure gets one retry instead of
                    // killing the segment outright (audit S6).
                    ChunkFetch retry = new ChunkFetch(url, referer, job.start, job.end);
                    jobs.set(i, retry);
                    futures.set(i, pool.submit(retry));
                    parts[i] = futures.get(i).get(12L, TimeUnit.SECONDS);
                }
            }
        } finally {
            // Win or lose, no chunk connection outlives its segment.
            for (int i = 0; i < jobs.size(); i++) {
                java.util.concurrent.Future<byte[]> f = futures.get(i);
                if (f != null && !f.isDone()) {
                    f.cancel(true);
                    ChunkFetch j = jobs.get(i);
                    if (j != null) {
                        j.cancelCall();
                    }
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) total);
        for (byte[] part : parts) {
            if (part == null) {
                throw new Exception("segment chunk missing");
            }
            out.write(part);
        }
        return out.toByteArray();
    }

    private byte[] fetchSegmentBytes(String url, String referer, String channelId) throws Exception {
        return fetchSegmentBytes(url, referer, channelId, true);
    }

    private byte[] fetchSegmentBytes(String url, String referer, String channelId, boolean playerPriority) throws Exception {
        long t0 = System.currentTimeMillis();
        byte[] out = fetchSegmentBytesInner(url, referer, channelId, playerPriority);
        Log.i("LiveProxyAudit", "seg id=" + channelId + " ms=" + (System.currentTimeMillis() - t0) + " bytes=" + out.length + " player=" + playerPriority);
        return out;
    }

    private byte[] fetchSegmentBytesInner(String url, String referer, String channelId, boolean playerPriority) throws Exception {
        try {
            return unwrapPayload(downloadRaw(url, referer, playerPriority));
        } catch (HttpException h) {
            // Segments can expire with the same token as the manifest. A
            // 403 here used to kill playback mid-stream even though the
            // manifest path already knew how to recover: force one token
            // renewal and retry with the fresh token, like proxyManifest.
            if (h.code != 403 || channelId == null) {
                throw h;
            }
            TokenSession session = TOKEN_SESSIONS.get(channelId);
            if (session == null) {
                throw h;
            }
            String freshToken = getOrRenewToken(channelId, session, true);
            if (freshToken == null || freshToken.isEmpty()) {
                throw h;
            }
            // Retry the SAME url (audit S16): segment URLs are signed
            // CDN URLs, and appending the manifest token (t=) can
            // break the signature. The renewal still happens - the
            // manifest path benefits - but the segment URL is left
            // exactly as the playlist issued it.
            return unwrapPayload(downloadRaw(url, referer, playerPriority));
        }
    }

    private static final ThreadLocal<byte[]> Z_BUF = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[3 * 1024 * 1024]; }
    };
    private static final ThreadLocal<byte[]> RAW_BUF = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[4 * 1024 * 1024]; }
    };
    private static final ThreadLocal<byte[]> ROW_PREV = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[16384]; }
    };
    private static final ThreadLocal<byte[]> ROW_CURR = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[16384]; }
    };

    /** A payload that cannot be unwrapped into a transport stream.
     * Thrown instead of returning the original bytes (audit S4): the
     * old fall-through SERVED the undecodable PNG as video/mp2t and
     * cached it, so the bad segment replayed forever. */
    static final class UnwrapException extends RuntimeException {
        UnwrapException(String m) { super(m); }
    }

    static byte[] unwrapPayload(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            throw new UnwrapException("segment body too small to be a transport stream");
        }
        if (bytes[0] == 0x47 && (bytes.length <= 188 || bytes[188] == 0x47)) {
            return bytes;
        }

        // 1. TIKTIKRAW check
        for (int i = 0; i + TRAW.length < bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < TRAW.length; j++) {
                if (bytes[i + j] != TRAW[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                int off = i + TRAW.length;
                if (off < bytes.length && bytes[off] == 0x47) {
                    byte[] out = new byte[bytes.length - off];
                    System.arraycopy(bytes, off, out, 0, out.length);
                    return out;
                }
            }
        }

        // 2. WebP EXIF check
        if (bytes.length >= 16 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
                bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            int off = 12;
            while (off + 8 <= bytes.length) {
                String tag = new String(bytes, off, 4, StandardCharsets.US_ASCII);
                int len = ((bytes[off + 4] & 0xFF)) | ((bytes[off + 5] & 0xFF) << 8) |
                        ((bytes[off + 6] & 0xFF) << 16) | ((bytes[off + 7] & 0xFF) << 24);
                off += 8;
                if (len < 0 || off + len > bytes.length) break;
                if ("EXIF".equals(tag)) {
                    if (len >= 188 && bytes[off] == 0x47 && bytes[off + 188] == 0x47) {
                        byte[] out = new byte[len];
                        System.arraycopy(bytes, off, out, 0, len);
                        return out;
                    }
                    break;
                }
                off += len + (len & 1);
            }
        }

        // 3. PNG check (IDAT / IEND / TIKTIKPX)
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            try {
                int off = 8;
                int w = 0, h = 0, depth = 0, ctype = 0, interlace = 0;
                int firstIdatOff = -1, firstIdatLen = 0, idatCount = 0, totalIdatLen = 0;
                List<int[]> idatList = null;

                while (off + 8 <= bytes.length) {
                    int chunkLen = ((bytes[off] & 0xFF) << 24) | ((bytes[off + 1] & 0xFF) << 16) |
                            ((bytes[off + 2] & 0xFF) << 8) | (bytes[off + 3] & 0xFF);
                    if (chunkLen < 0 || chunkLen > bytes.length - off - 12) break;
                    String type = new String(bytes, off + 4, 4, StandardCharsets.US_ASCII);
                    int dataOff = off + 8;
                    if ("IHDR".equals(type) && chunkLen >= 13) {
                        w = ((bytes[dataOff] & 0xFF) << 24) | ((bytes[dataOff + 1] & 0xFF) << 16) |
                                ((bytes[dataOff + 2] & 0xFF) << 8) | (bytes[dataOff + 3] & 0xFF);
                        h = ((bytes[dataOff + 4] & 0xFF) << 24) | ((bytes[dataOff + 5] & 0xFF) << 16) |
                                ((bytes[dataOff + 6] & 0xFF) << 8) | (bytes[dataOff + 7] & 0xFF);
                        depth = bytes[dataOff + 8] & 0xFF;
                        ctype = bytes[dataOff + 9] & 0xFF;
                        interlace = bytes[dataOff + 12] & 0xFF;
                    } else if ("IDAT".equals(type)) {
                        if (idatCount == 0) {
                            firstIdatOff = dataOff;
                            firstIdatLen = chunkLen;
                        } else if (idatCount == 1) {
                            idatList = new ArrayList<>();
                            idatList.add(new int[]{firstIdatOff, firstIdatLen});
                            idatList.add(new int[]{dataOff, chunkLen});
                        } else {
                            idatList.add(new int[]{dataOff, chunkLen});
                        }
                        idatCount++;
                        totalIdatLen += chunkLen;
                    } else if ("IEND".equals(type)) {
                        int remOff = off + 12;
                        if (remOff < bytes.length && bytes[remOff] == 0x47 &&
                                (remOff + 188 >= bytes.length || bytes[remOff + 188] == 0x47)) {
                            byte[] out = new byte[bytes.length - remOff];
                            System.arraycopy(bytes, remOff, out, 0, out.length);
                            return out;
                        }
                        break;
                    }
                    off += 12 + chunkLen;
                }

                if (w > 0 && h > 0 && depth == 8 && interlace == 0 && (ctype == 2 || ctype == 6) && totalIdatLen > 0) {
                    byte[] zbuf;
                    if (idatCount == 1) {
                        zbuf = bytes;
                    } else {
                        zbuf = Z_BUF.get();
                        if (zbuf.length < totalIdatLen) {
                            zbuf = new byte[totalIdatLen + 65536];
                            Z_BUF.set(zbuf);
                        }
                        int zoff = 0;
                        for (int[] chunk : idatList) {
                            System.arraycopy(bytes, chunk[0], zbuf, zoff, chunk[1]);
                            zoff += chunk[1];
                        }
                    }

                    int bpp = ctype == 6 ? 4 : 3;
                    // Bound the geometry with long math (audit S18):
                    // a crafted header could overflow int here and
                    // size a multi-GB allocation.
                    long strideL = (long) w * bpp;
                    long rawSizeL = (long) h * (1L + strideL);
                    if (w > 20000 || h > 20000 || rawSizeL > 64_000_000L) {
                        throw new UnwrapException("implausible PNG geometry " + w + "x" + h);
                    }
                    int stride = (int) strideL;
                    int rawSize = (int) rawSizeL;

                    byte[] raw = RAW_BUF.get();
                    if (raw.length < rawSize) {
                        raw = new byte[rawSize + 65536];
                        RAW_BUF.set(raw);
                    }

                    Inflater inflater = new Inflater();
                    int inflated = 0;
                    try {
                        if (idatCount == 1) {
                            inflater.setInput(bytes, firstIdatOff, firstIdatLen);
                        } else {
                            inflater.setInput(zbuf, 0, totalIdatLen);
                        }
                        while (!inflater.finished() && inflated < rawSize) {
                            int count = inflater.inflate(raw, inflated, rawSize - inflated);
                            if (count <= 0) {
                                if (inflater.needsInput() || count == 0) break;
                            }
                            inflated += count;
                        }
                    } finally {
                        inflater.end();
                    }
                    // Rows past `inflated` hold the PREVIOUS segment's
                    // data in the reused buffer (audit S4): a truncated
                    // inflate must fail, never un-filter stale rows.
                    if (inflated < rawSize) {
                        throw new UnwrapException("truncated PNG payload: inflated " + inflated + " of " + rawSize);
                    }

                    byte[] prevRecon = ROW_PREV.get();
                    if (prevRecon.length < stride) {
                        prevRecon = new byte[stride + 1024];
                        ROW_PREV.set(prevRecon);
                    }
                    byte[] currRecon = ROW_CURR.get();
                    if (currRecon.length < stride) {
                        currRecon = new byte[stride + 1024];
                        ROW_CURR.set(currRecon);
                    }
                    Arrays.fill(prevRecon, 0, stride, (byte) 0);

                    byte[] gz = null;
                    int gzOffset = 0, gzTargetLen = 0;
                    int src = 0;

                    for (int y = 0; y < h; y++) {
                        if (src + 1 + stride > rawSize) break;
                        int filter = raw[src++] & 0xFF;
                        if (filter > 4) {
                            throw new UnwrapException("invalid PNG filter type " + filter);
                        }

                        if (filter == 0) {
                            System.arraycopy(raw, src, currRecon, 0, stride);
                        } else if (filter == 1) {
                            for (int i = 0; i < stride; i++) {
                                int a = i >= bpp ? (currRecon[i - bpp] & 0xFF) : 0;
                                currRecon[i] = (byte) ((raw[src + i] & 0xFF) + a);
                            }
                        } else if (filter == 2) {
                            for (int i = 0; i < stride; i++) {
                                int b = prevRecon[i] & 0xFF;
                                currRecon[i] = (byte) ((raw[src + i] & 0xFF) + b);
                            }
                        } else if (filter == 3) {
                            for (int i = 0; i < stride; i++) {
                                int a = i >= bpp ? (currRecon[i - bpp] & 0xFF) : 0;
                                int b = prevRecon[i] & 0xFF;
                                currRecon[i] = (byte) ((raw[src + i] & 0xFF) + ((a + b) >> 1));
                            }
                        } else if (filter == 4) {
                            for (int i = 0; i < stride; i++) {
                                int a = i >= bpp ? (currRecon[i - bpp] & 0xFF) : 0;
                                int b = prevRecon[i] & 0xFF;
                                int c = i >= bpp ? (prevRecon[i - bpp] & 0xFF) : 0;
                                currRecon[i] = (byte) ((raw[src + i] & 0xFF) + paeth(a, b, c));
                            }
                        }
                        src += stride;

                        if (y == 0) {
                            byte[] header = new byte[12];
                            int hIdx = 0;
                            if (ctype == 2) {
                                System.arraycopy(currRecon, 0, header, 0, 12);
                            } else {
                                for (int i = 0; i < 16 && hIdx < 12; i += 4) {
                                    header[hIdx++] = currRecon[i];
                                    header[hIdx++] = currRecon[i + 1];
                                    header[hIdx++] = currRecon[i + 2];
                                }
                            }

                            boolean match = true;
                            for (int k = 0; k < 8; k++) {
                                if (header[k] != TPIX[k]) {
                                    match = false;
                                    break;
                                }
                            }
                            if (!match) break;

                            gzTargetLen = ((header[8] & 0xFF) << 24) | ((header[9] & 0xFF) << 16) |
                                          ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
                            if (gzTargetLen <= 0 || gzTargetLen > 30_000_000) break;

                            gz = new byte[gzTargetLen];
                            if (ctype == 2) {
                                int copyLen = Math.min(stride - 12, gzTargetLen);
                                System.arraycopy(currRecon, 12, gz, 0, copyLen);
                                gzOffset += copyLen;
                            } else {
                                for (int i = 16; i < stride && gzOffset < gzTargetLen; i += 4) {
                                    gz[gzOffset++] = currRecon[i];
                                    if (gzOffset < gzTargetLen) gz[gzOffset++] = currRecon[i + 1];
                                    if (gzOffset < gzTargetLen) gz[gzOffset++] = currRecon[i + 2];
                                }
                            }
                        } else {
                            if (ctype == 2) {
                                int copyLen = Math.min(stride, gzTargetLen - gzOffset);
                                System.arraycopy(currRecon, 0, gz, gzOffset, copyLen);
                                gzOffset += copyLen;
                            } else {
                                for (int i = 0; i < stride && gzOffset < gzTargetLen; i += 4) {
                                    gz[gzOffset++] = currRecon[i];
                                    if (gzOffset < gzTargetLen) gz[gzOffset++] = currRecon[i + 1];
                                    if (gzOffset < gzTargetLen) gz[gzOffset++] = currRecon[i + 2];
                                }
                            }
                        }

                        if (gz != null && gzOffset >= gzTargetLen) {
                            break;
                        }

                        byte[] tmp = prevRecon;
                        prevRecon = currRecon;
                        currRecon = tmp;
                    }

                    if (gz != null && gzOffset >= gzTargetLen) {
                        byte[] ts = gunzip(gz);
                        if (ts != null && ts.length > 0 && ts[0] == 0x47 &&
                                (ts.length <= 188 || ts[188] == 0x47)) {
                            return ts;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unwrap payload error: " + e.getMessage());
            }
        }

        // Nothing matched: this is not a transport stream, and
        // serving the raw bytes as one (the old fall-through) cached
        // garbage as a segment (audit S4).
        throw new UnwrapException("payload is not a transport stream");
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    private static byte[] gunzip(byte[] data) {
        if (data == null || data.length < 18) return null;
        try {
            int len = data.length;
            int isize = (data[len - 4] & 0xFF) |
                    ((data[len - 3] & 0xFF) << 8) |
                    ((data[len - 2] & 0xFF) << 16) |
                    ((data[len - 1] & 0xFF) << 24);

            if (isize > 0 && isize <= 25_000_000) {
                byte[] ts = new byte[isize];
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    int totalRead = 0;
                    while (totalRead < isize) {
                        int r = gis.read(ts, totalRead, isize - totalRead);
                        if (r <= 0) break;
                        totalRead += r;
                    }
                    if (totalRead == isize) {
                        return ts;
                    }
                }
            }

            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
                 ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(data.length * 2, 262144))) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = gis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Request.Builder request(String url, String referer) {
        Request.Builder request = new Request.Builder().url(url).header(HttpHeaders.USER_AGENT, UA);
        if (referer != null && !referer.isEmpty()) {
            request.header(HttpHeaders.REFERER, referer);
            try {
                URI uri = URI.create(referer);
                request.header(HttpHeaders.ORIGIN, uri.getScheme() + "://" + uri.getHost());
            } catch (Exception e) {
            }
        }
        return request;
    }

    /**
     * Executes a request with dlhd-cli's retry policy: up to 3 attempts with
     * exponential backoff (0.75s, 1.5s) on transport failures and 5xx
     * responses. 4xx responses are returned to the caller immediately.
     */
    /** Single attempt on the fail-fast segment client. See constructor. */
    private okhttp3.Response executeOnce(okhttp3.Request req) throws Exception {
        return this.segmentClient.newCall(req).execute();
    }

    private okhttp3.Response executeWithRetry(okhttp3.Request req) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                okhttp3.Response resp = this.client.newCall(req).execute();
                if (resp.code() < 500 || attempt == 2) {
                    return resp;
                }
                Log.w(TAG, "Upstream HTTP " + resp.code() + " for " + req.url() + ", retrying...");
                resp.close();
            } catch (Exception e) {
                last = e;
                if (attempt == 2) {
                    throw e;
                }
                Log.w(TAG, "Request failed for " + req.url() + ": " + e.getMessage() + ", retrying...");
            }
            try {
                Thread.sleep(750L << attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new Exception("interrupted while backing off", ie);
            }
        }
        throw (last != null ? last : new Exception("request failed"));
    }

    private String getText(String url, String referer) throws Exception {
        okhttp3.Response response = executeWithRetry(request(url, referer).build());
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            String strString = response.body().string();
            if (response != null) {
                response.close();
            }
            return strString;
        } catch (Throwable th) {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static String absolute(String base, String value) throws Exception {
        return new URL(new URL(base), value).toString();
    }

    static String playerPageUrl(String id, int player) {
        return player == 0 ? StreamEndpoints.streamUrl(StreamEndpoints.PRIMARY, id) : StreamEndpoints.streamUrl(StreamEndpoints.PRIMARY, ROUTES[player], id);
    }

    private static String playerPageUrlOn(String base, String id, int player) {
        return player == 0 ? StreamEndpoints.streamUrl(base, id) : StreamEndpoints.streamUrl(base, ROUTES[player], id);
    }

    private static String resourceUrl(String url, String referer) {
        return resourceUrl(url, referer, null);
    }

    private static String resourceUrl(String url, String referer, String channelId) {
        String base = "/live/resource?url=" + enc(url) + "&referer=" + enc(referer);
        return channelId == null || channelId.isEmpty() ? base : base + "&id=" + enc(channelId);
    }

    static String manifestUrl(String id, int player, String url, String referer) {
        return "/live/manifest.m3u8?id=" + enc(id) + "&player=" + player + "&url=" + enc(url) + "&referer=" + enc(referer);
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, Key.STRING_CHARSET_NAME);
        } catch (Exception error) {
            throw new IllegalStateException("UTF-8 unavailable", error);
        }
    }

    private static final class Resolved {
        final String origin;
        final String referer;
        final String url;

        Resolved(String u, String r, String o) {
            this.url = u;
            this.referer = r;
            this.origin = o;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            int n = Integer.parseInt(value);
            return n >= 0 ? n : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
