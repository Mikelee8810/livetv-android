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
    private static final String[] ROUTES = {"ntvs", "stream", "cast", "watch", "plus", "casting", "player"};
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

    private static final byte[] TRAW = "TIKTIKRAW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TPIX = "TIKTIKPX".getBytes(StandardCharsets.US_ASCII);

    private static final Map<String, CachedResolved> RESOLVE_CACHE = new ConcurrentHashMap<>();

    private static final class CachedResolved {
        final Resolved resolved;
        final long expiresAt;
        CachedResolved(Resolved r, long exp) { this.resolved = r; this.expiresAt = exp; }
    }

    public OnDeviceStreamServer() {
        super("127.0.0.1", 48913);
        this.client = new OkHttpClient.Builder().connectTimeout(5L, TimeUnit.SECONDS).readTimeout(12L, TimeUnit.SECONDS).followRedirects(true).build();
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
            if (uri.endsWith("manifest.m3u8") || uri.contains("/manifest")) {
                String id = q.get("id");
                int player = parseInt(q.get("player"), 0);
                String upstream = q.get("url");
                String sources = q.get("sources");
                if (upstream == null) {
                    resolved = sources == null ? resolve(id, player) : resolveExternal(sources, player);
                } else {
                    resolved = new Resolved(upstream, q.get("referer"), "");
                }
                if (upstream == null) {
                    upstream = resolved.url;
                }
                return proxyManifest(id, player, upstream, resolved);
            }
            if (uri.endsWith("/resource") || uri.contains("resource")) {
                return proxyResource(q.get("url"), q.get("referer"));
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
                RESOLVE_CACHE.put(cacheKey, new CachedResolved(res, System.currentTimeMillis() + 50000L));
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
        String watch = (player == 0) ? "https://ntvs.cx/channel/phoenix/" + id : "https://dlstreams.st/watch.php?id=" + id;
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
                        String manifestUrl = src + (src.contains("?") ? "&" : "?") + "t=" + token;
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
        Request request = request(upstream, resolved.referer).build();
        try (okhttp3.Response response = this.client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("Manifest HTTP " + response.code());
            }
            String base = upstream.substring(0, upstream.lastIndexOf('/') + 1);
            Pattern uriAttr = Pattern.compile("URI=\"([^\"]+)\"");
            StringBuilder output = new StringBuilder();
            for (String raw : response.body().string().split("\\r?\\n")) {
                String line = raw.trim();
                if (line.contains("URI=\"")) {
                    Matcher key = uriAttr.matcher(line);
                    if (key.find()) {
                        line = key.replaceFirst("URI=\"" + Matcher.quoteReplacement(resourceUrl(absolute(base, key.group(1)), resolved.referer)) + "\"");
                    }
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    String absolute = absolute(base, line);
                    line = absolute.contains(".m3u8") ? manifestUrl(id, player, absolute, resolved.referer) : resourceUrl(absolute, resolved.referer);
                }
                output.append(line).append('\n');
            }
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", output.toString());
        }
    }

    private NanoHTTPD.Response proxyResource(String url, String referer) throws Exception {
        okhttp3.Request request = request(url, referer).build();
        try (okhttp3.Response upstream = this.client.newCall(request).execute()) {
            ResponseBody body = upstream.body();
            if (!upstream.isSuccessful() || body == null) {
                int code = upstream.code();
                throw new Exception("Resource HTTP " + code);
            }
            byte[] rawBytes = body.bytes();
            byte[] unwrapped = unwrapPayload(rawBytes);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "video/mp2t", new ByteArrayInputStream(unwrapped), unwrapped.length);
        }
    }

    private static byte[] unwrapPayload(byte[] bytes) {
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
                List<byte[]> idats = new ArrayList<>();
                int totalIdatLen = 0;

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
                        byte[] idat = new byte[chunkLen];
                        System.arraycopy(bytes, dataOff, idat, 0, chunkLen);
                        idats.add(idat);
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
                    byte[] zbuf = new byte[totalIdatLen];
                    int zoff = 0;
                    for (byte[] p : idats) {
                        System.arraycopy(p, 0, zbuf, zoff, p.length);
                        zoff += p.length;
                    }
                    byte[] raw = inflateZlib(zbuf);
                    int bpp = ctype == 6 ? 4 : 3;
                    int stride = w * bpp;
                    byte[] rgb = new byte[w * h * 3];
                    int src = 0, dst = 0;
                    byte[] prev = new byte[stride];

                    for (int y = 0; y < h; y++) {
                        if (src + 1 + stride > raw.length) break;
                        int filter = raw[src++] & 0xFF;
                        byte[] row = new byte[stride];
                        System.arraycopy(raw, src, row, 0, stride);
                        src += stride;

                        if (filter == 0) {
                            if (ctype == 2) {
                                System.arraycopy(row, 0, rgb, dst, stride);
                                dst += stride;
                            } else {
                                for (int i = 0; i < stride; i += 4) {
                                    rgb[dst++] = row[i];
                                    rgb[dst++] = row[i + 1];
                                    rgb[dst++] = row[i + 2];
                                }
                            }
                            prev = row;
                            continue;
                        }

                        byte[] recon = new byte[stride];
                        for (int i = 0; i < stride; i++) {
                            int a = i >= bpp ? (recon[i - bpp] & 0xFF) : 0;
                            int b = prev[i] & 0xFF;
                            int c = i >= bpp ? (prev[i - bpp] & 0xFF) : 0;
                            int v = row[i] & 0xFF;
                            if (filter == 1) v += a;
                            else if (filter == 2) v += b;
                            else if (filter == 3) v += (a + b) >> 1;
                            else if (filter == 4) v += paeth(a, b, c);
                            recon[i] = (byte) (v & 0xFF);
                        }

                        if (ctype == 2) {
                            System.arraycopy(recon, 0, rgb, dst, stride);
                            dst += stride;
                        } else {
                            for (int i = 0; i < stride; i += 4) {
                                rgb[dst++] = recon[i];
                                rgb[dst++] = recon[i + 1];
                                rgb[dst++] = recon[i + 2];
                            }
                        }
                        prev = recon;
                    }

                    if (rgb.length >= 12) {
                        boolean tpixMatch = true;
                        for (int k = 0; k < 8; k++) {
                            if (rgb[k] != TPIX[k]) {
                                tpixMatch = false;
                                break;
                            }
                        }
                        if (tpixMatch) {
                            int n = ((rgb[8] & 0xFF) << 24) | ((rgb[9] & 0xFF) << 16) |
                                    ((rgb[10] & 0xFF) << 8) | (rgb[11] & 0xFF);
                            if (n > 0 && 12 + n <= rgb.length) {
                                byte[] gz = new byte[n];
                                System.arraycopy(rgb, 12, gz, 0, n);
                                byte[] ts = gunzip(gz);
                                if (ts != null && ts.length > 0 && ts[0] == 0x47) {
                                    return ts;
                                }
                            }
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

    private static byte[] inflateZlib(byte[] data) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count <= 0 && inflater.needsInput()) break;
            baos.write(buffer, 0, count);
        }
        inflater.end();
        return baos.toByteArray();
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = gis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
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

    private String getText(String url, String referer) throws Exception {
        okhttp3.Response response = this.client.newCall(request(url, referer).build()).execute();
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
        return player == 0 ? "https://dlhd.st/stream/stream-" + id + ".php" : "https://dlstreams.st/" + ROUTES[player] + "/stream-" + id + ".php";
    }

    private static String resourceUrl(String url, String referer) {
        return "/live/resource?url=" + enc(url) + "&referer=" + enc(referer);
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
