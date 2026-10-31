package com.livetv.app;

import android.content.Context;
import android.util.Log;
import com.livetv.app.catalog.ChannelClassifier;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Live DaddyLive channel list, borrowed from dlhd-cli's fetch_channels():
 * the bundled channels.m3u is a frozen snapshot (509 entries on 2026-10-22)
 * while the operator's 24-7 page listed 899 channels a week later, so the
 * catalog now refreshes from the live page and merges.
 *
 * Bundled channels keep their curated metadata (logo, group, EPG id). Live
 * entries the bundle does not know are appended, classified like the rest.
 * The fetched list is cached on disk for 12 hours so startup never waits on
 * the network: loadCached() is instant, refreshIfStale() runs in the
 * background from Repo.reload().
 */
public final class LiveCatalog {
    private static final String TAG = "LiveCatalog";
    private static final long STALE_MS = 12L * 60L * 60L * 1000L;
    private static final String CACHE_FILE = "live_channels.tsv";

    // Verified 2026-10-30 against thedaddy.pk/24-7-channels.php:
    // <a class="card" href="/watch.php?id=51" ...>
    //   <div class="card__title">ABC USA</div><div class="">ID: 51</div></a>
    private static final Pattern CARD = Pattern.compile(
            "<a[^>]*href=\"[^\"]*watch\\.php\\?id=(\\d+)\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "card__title\">([^<]+)</div>", Pattern.CASE_INSENSITIVE);

    private LiveCatalog() {
    }

    // One shared client (audit L4) and sweep discipline (audit L1):
    // no overlapping sweeps, and a failed sweep backs off instead of
    // being retried in full by every Repo.reload().
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastAttemptMs = 0L;
    private static final long FAIL_BACKOFF_MS = 15L * 60L * 1000L;

    /** Parses the 24-7 channels page into id -> name, in page order. */
    static Map<String, String> parseChannelsPage(String html) {
        Map<String, String> out = new LinkedHashMap<>();
        if (html == null) {
            return out;
        }
        Matcher cards = CARD.matcher(html);
        while (cards.find()) {
            Matcher title = TITLE.matcher(cards.group(2));
            String name = title.find() ? unescape(title.group(1).trim()) : "";
            if (!name.isEmpty()) {
                out.put(cards.group(1), name);
            }
        }
        return out;
    }

    // The 24-7 page serves HTML-escaped titles (audit L2).
    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Channels from the disk cache that the bundled catalog does not have. */
    public static List<Channel> loadCachedNew(Context context, List<Channel> bundled) {
        Map<String, String> live = readCache(context);
        return newChannels(live, bundled);
    }

    static List<Channel> newChannels(Map<String, String> live, List<Channel> bundled) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Channel c : bundled) {
            known.add(c.id);
        }
        List<Channel> fresh = new ArrayList<>();
        for (Map.Entry<String, String> e : live.entrySet()) {
            if (!known.contains(e.getKey())) {
                fresh.add(new Channel(e.getKey(), "", e.getValue(), "Live TV", "", null, 7));
            }
        }
        if (fresh.isEmpty()) {
            return fresh;
        }
        // Same adult filter, dedupe and ranking as the bundled catalog.
        return new ChannelClassifier().classify(fresh).channels;
    }

    /**
     * Fetches the live list when the cache is older than 12 hours.
     * Returns true when the cache changed, so the caller can reload.
     */
    public static boolean refreshIfStale(Context context) {
        File cache = new File(context.getCacheDir(), CACHE_FILE);
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < STALE_MS) {
            return false;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        try {
            return refreshSweep(context, cache);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static boolean refreshSweep(Context context, File cache) {
        // Backoff (audit L1): if the last full sweep failed recently,
        // do not pay for another one yet.
        if (!cache.exists() && System.currentTimeMillis() - lastAttemptMs < FAIL_BACKOFF_MS && lastAttemptMs > 0L) {
            return false;
        }
        lastAttemptMs = System.currentTimeMillis();
        OkHttpClient client = CLIENT;
        for (String base : StreamEndpoints.BASES) {
            try {
                Request req = new Request.Builder()
                        .url(StreamEndpoints.channelsUrl(base))
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Google TV Streamer) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                        .build();
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        continue;
                    }
                    Map<String, String> live = parseChannelsPage(resp.body().string());
                    if (live.size() < 100) {
                        Log.w(TAG, "Live list from " + base + " looked wrong (" + live.size() + " channels), skipping");
                        continue;
                    }
                    writeCache(context, live);
                    Log.i(TAG, "Live catalog refreshed from " + base + ": " + live.size() + " channels");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Live catalog fetch failed from " + base + ": " + e.getMessage());
            }
        }
        return false;
    }

    private static Map<String, String> readCache(Context context) {
        Map<String, String> out = new LinkedHashMap<>();
        File cache = new File(context.getCacheDir(), CACHE_FILE);
        if (!cache.exists()) {
            return out;
        }
        try {
            byte[] bytes = new byte[(int) cache.length()];
            try (FileInputStream in = new FileInputStream(cache)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    out.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Live catalog cache unreadable: " + e.getMessage());
        }
        return out;
    }

    private static void writeCache(Context context, Map<String, String> live) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : live.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().replaceAll("[\\t\\r\\n]+", " ")).append('\n');
        }
        // Write to a temp file and rename: truncating the live cache in
        // place meant a kill mid-write left a partial channel list with
        // a FRESH timestamp, treated as valid for the next 12 hours.
        File cache = new File(context.getCacheDir(), CACHE_FILE);
        File tmp = new File(context.getCacheDir(), CACHE_FILE + ".tmp");
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(cache)) {
            // Fail the write (audit L4): the old in-place fallback was
            // the same truncation race the temp file exists to avoid.
            tmp.delete();
            throw new java.io.IOException("could not store live catalog cache");
        }
    }
}
