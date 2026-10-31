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
        final long expiresAt;
        CachedResolved(Resolved r, long exp) { this.resolved = r; this.expiresAt = exp; }
    }

    private static final class TokenSession {
        final String edge;
        final String chan;
        final String playerUrl;
        volatile String token;
        volatile long renewAt;
        volatile long expiresAt;

        TokenSession(String edge, String chan, String playerUrl, String token, long ttlSec) {
            this.edge = edge;
            this.chan = chan;
            this.playerUrl = playerUrl;
            this.token = token;
            long now = System.currentTimeMillis();
            this.renewAt = now + (ttlSec * 500L);
            this.expiresAt = now + (ttlSec * 1000L) - 2000L;
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
                            session.expiresAt = now + (ttl * 1000L) - 2000L;
                            Log.i(TAG, "Renewed playlist-token for channel " + channelId + " (valid for " + ttl + "s)");
                            Log.i("LiveProxyAudit", "token renewed id=" + channelId + " force=" + force);
                            return session.token;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to renew token for channel " + channelId + ": " + e.getMessage());
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
        this.segmentClient = new OkHttpClient.Builder().connectTimeout(5L, TimeUnit.SECONDS).readTimeout(8L, TimeUnit.SECONDS).followRedirects(true).build();
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
            Log.i(TAG, "Request: uri=" + uri + ", id=" + q.get("id") + ", sources=" + q.get("sources") + ", url=" + q.get("url"));
            if (uri.endsWith("manifest.m3u8") || uri.contains("/manifest")) {
                String id = q.get("id");
                int player = parseInt(q.get("player"), 0);
                String upstream = q.get("url");
                String sources = q.get("sources");
                if (upstream == null) {
                    if (id != null && !id.trim().isEmpty() && id.trim().matches("\\d+")) {
                        resolved = resolve(id.trim(), player);
                    } else if (sources != null && !sources.trim().isEmpty()) {
                        resolved = resolveExternal(sources, player);
                    } else {
                        throw new IllegalArgumentException("Missing id or sources");
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
        CachedResolved cached = RESOLVE_CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return cached.resolved;
        }

        Exception lastError = null;
        for (int i = 0; i < ROUTES.length; i++) {
            int player = (preferred + i) % ROUTES.length;
            try {
                Resolved res = resolveSingle(id, player);
                // 15 minutes. The playlist token itself is renewed separately
                // by TokenSession, and a failed manifest fetch drops the cache
                // entry, so a longer resolve cache is safe and makes zapping
                // back to a recent channel fast instead of re-walking embeds.
                RESOLVE_CACHE.put(cacheKey, new CachedResolved(res, System.currentTimeMillis() + 900_000L));
                return res;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Route " + ROUTES[player] + " failed for channel " + id + ": " + e.getMessage());
            }
        }
        throw (lastError != null ? lastError : new Exception("All routes failed for " + id));
    }

    private Resolved resolveSingle(String id, int player) throws Exception {
        String embed = playerPageUrl(id, player);
        String watch = (player == 0) ? StreamEndpoints.NTVS_WATCH + id : StreamEndpoints.watchUrl(StreamEndpoints.PRIMARY, id);
        String embedHtml = getText(embed, watch);
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
                        String manifestUrl = src + (src.contains("?") ? "&" : "?") + "t=" + token + "&_=" + System.currentTimeMillis();
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
                Log.w("LiveProxyAudit", "manifest BAD BODY id=" + id + " - keeping session, trying last-good manifest");
                NanoHTTPD.Response stale2 = staleManifest(id, player);
                if (stale2 != null) {
                    return stale2;
                }
                throw new Exception("Upstream returned a non-playlist body for channel " + id);
            }
            java.util.regex.Matcher seqM = java.util.regex.Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(manifestBody);
            Log.i("LiveProxyAudit", "manifest served id=" + id + " seq=" + (seqM.find() ? seqM.group(1) : "?"));
            String base = effectiveUrl.substring(0, effectiveUrl.lastIndexOf('/') + 1);
            Pattern uriAttr = Pattern.compile("URI=\"([^\"]+)\"");
            StringBuilder output = new StringBuilder();
            List<String> segmentUrls = new ArrayList<>();
            for (String raw : manifestBody.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME")) {
                    continue; // Strip to prevent upstream wallclock drift from desyncing ExoPlayer's live timeline
                }
                if (line.contains("URI=\"")) {
                    Matcher key = uriAttr.matcher(line);
                    if (key.find()) {
                        line = key.replaceFirst("URI=\"" + Matcher.quoteReplacement(resourceUrl(absolute(base, key.group(1)), resolved.referer, id)) + "\"");
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
            }
            NanoHTTPD.Response manifestResp = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", output.toString());
            manifestResp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            manifestResp.addHeader("Pragma", "no-cache");
            manifestResp.addHeader("Expires", "0");
            // Warm the segment prefetch cache for the channel actually
            // being watched (see the prefetch block above proxyResource).
            if (id != null && !segmentUrls.isEmpty()) {
                if (activeChannelId == null) {
                    activeChannelId = id;
                }
                if (id.equals(activeChannelId)) {
                    prefetchTail(segmentUrls, resolved.referer, id);
                }
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
    private static final Map<String, String> LAST_GOOD_MANIFEST = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_GOOD_MANIFEST_AT = new ConcurrentHashMap<>();

    private NanoHTTPD.Response staleManifest(String id, int player) {
        if (id == null) {
            return null;
        }
        String body = LAST_GOOD_MANIFEST.get(id + ":" + player);
        Long at = LAST_GOOD_MANIFEST_AT.get(id + ":" + player);
        if (body == null || at == null || System.currentTimeMillis() - at > 90000L) {
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
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                SEGMENT_INFLIGHT.remove(url, future);
            }
        }
    }

    private void prefetchTail(List<String> segmentUrls, String referer, String channelId) {
        int from = Math.max(0, segmentUrls.size() - PREFETCH_AHEAD);
        java.util.Set<String> wanted = new java.util.HashSet<>(segmentUrls.subList(from, segmentUrls.size()));
        // Drop queued warming for segments the live window has passed.
        PREFETCH_QUEUE.removeIf(t -> t instanceof PrefetchTask && !wanted.contains(((PrefetchTask) t).url));
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
        Response outResp = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp2t", new ByteArrayInputStream(bytes), bytes.length);
        outResp.addHeader("Accept-Ranges", "bytes");
        return outResp;
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
        if (pending != null) {
            try {
                byte[] bytes = pending.get(12L, TimeUnit.SECONDS);
                if (bytes != null && bytes.length > 0) {
                    return segmentResponse(bytes);
                }
            } catch (Exception e) {
                // The warmed copy failed or is too slow; fetch directly.
            }
        }
        // Player-first: fetch on this request thread, never queued
        // behind prefetch work.
        byte[] bytes = fetchSegmentBytes(url, referer, channelId);
        synchronized (SEGMENT_CACHE) { SEGMENT_CACHE.put(url, bytes); }
        return segmentResponse(bytes);
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
        okhttp3.Call head = this.segmentClient.newCall(request(url, referer).head().build());
        head.timeout().timeout(6L, TimeUnit.SECONDS);
        try (okhttp3.Response resp = head.execute()) {
            if (resp.code() == 403) {
                throw new HttpException(403);
            }
            if (resp.isSuccessful()) {
                total = resp.header("Content-Length") != null ? Long.parseLong(resp.header("Content-Length")) : -1L;
            }
        } catch (HttpException h) {
            throw h;
        } catch (Exception ignored) {
            // No usable HEAD: fall through to the range-probe flow.
        }
        if (total > CHUNK_SIZE) {
            int chunkCount = (int) ((total + CHUNK_SIZE - 1) / CHUNK_SIZE);
            byte[][] parts = new byte[chunkCount][];
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) {
                final int idx = i;
                final long chunkStart = (long) i * CHUNK_SIZE;
                final long chunkEnd = Math.min(total - 1L, chunkStart + CHUNK_SIZE - 1L);
                futures.add(pool.submit(() -> {
                    parts[idx] = fetchChunk(url, referer, chunkStart, chunkEnd);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(40L, TimeUnit.SECONDS);
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
            if (probedTotal <= first.length) {
                return first;
            }
            int chunkCount = (int) ((probedTotal + CHUNK_SIZE - 1) / CHUNK_SIZE);
            byte[][] parts = new byte[chunkCount][];
            parts[0] = first;
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 1; i < chunkCount; i++) {
                final int idx = i;
                final long chunkStart = (long) i * CHUNK_SIZE;
                final long chunkEnd = Math.min(probedTotal - 1L, chunkStart + CHUNK_SIZE - 1L);
                futures.add(pool.submit(() -> {
                    parts[idx] = fetchChunk(url, referer, chunkStart, chunkEnd);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(40L, TimeUnit.SECONDS);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) probedTotal);
            for (byte[] part : parts) {
                if (part == null) {
                    throw new Exception("segment chunk missing");
                }
                out.write(part);
            }
            return out.toByteArray();
        }
    }

    private byte[] fetchChunk(String url, String referer, long start, long end) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Request req = request(url, referer).header("Range", "bytes=" + start + "-" + end).build();
            okhttp3.Call call = this.segmentClient.newCall(req);
            call.timeout().timeout(8L, TimeUnit.SECONDS);
            try (okhttp3.Response resp = call.execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new Exception("chunk HTTP " + resp.code());
                }
                byte[] data = resp.body().bytes();
                if (data.length != end - start + 1L) {
                    throw new Exception("short chunk: " + data.length + " of " + (end - start + 1L));
                }
                return data;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private byte[] fetchSegmentBytes(String url, String referer, String channelId) throws Exception {
        return fetchSegmentBytes(url, referer, channelId, true);
    }

    private byte[] fetchSegmentBytes(String url, String referer, String channelId, boolean playerPriority) throws Exception {
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
            String retryUrl = cleanPlaylistUrl(url);
            retryUrl += (retryUrl.contains("?") ? "&" : "?") + "t=" + URLEncoder.encode(freshToken, StandardCharsets.UTF_8.name()) + "&_=" + System.currentTimeMillis();
            return unwrapPayload(downloadRaw(retryUrl, referer, playerPriority));
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

    static byte[] unwrapPayload(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            return bytes;
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
                    int stride = w * bpp;
                    int rawSize = h * (1 + stride);

                    byte[] raw = RAW_BUF.get();
                    if (raw.length < rawSize) {
                        raw = new byte[rawSize + 65536];
                        RAW_BUF.set(raw);
                    }

                    Inflater inflater = new Inflater();
                    try {
                        if (idatCount == 1) {
                            inflater.setInput(bytes, firstIdatOff, firstIdatLen);
                        } else {
                            inflater.setInput(zbuf, 0, totalIdatLen);
                        }
                        int inflated = 0;
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
                        if (ts != null && ts.length > 0 && ts[0] == 0x47) {
                            return ts;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unwrap payload error: " + e.getMessage());
            }
        }

        return bytes;
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
