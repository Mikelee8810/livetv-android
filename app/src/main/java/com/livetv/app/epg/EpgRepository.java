package com.livetv.app.epg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.common.net.HttpHeaders;
import com.livetv.app.Channel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/* JADX INFO: loaded from: classes3.dex */
public final class EpgRepository {
    private static final long FRESH_MILLIS = 21600000;
    private static final long MAX_COMPRESSED_BYTES = 67108864;
    private static final long MAX_DECOMPRESSED_BYTES = 268435456L;
    private static final String TAG = "LiveTV-EPG";
    // Audit E1: refreshes are process-wide serialized (callers build
    // a fresh repository per call, so an instance guard is useless);
    // a refresh requested mid-flight coalesces into one follow-up.
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static final Object PENDING_LOCK = new Object();
    private static Runnable pendingRefresh;
    private final Context context;
    private final List<String> sources;

    public interface Callback {
        void onLoaded(EpgSnapshot epgSnapshot, Report report);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface ProgressCallback {
        void onProgress(LoadOutcome loadOutcome);
    }

    public EpgRepository(Context context, List<String> sources) {
        this.context = context.getApplicationContext();
        this.sources = sources == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList(sources));
    }

    public EpgRepository(Context context, String... sources) {
        this(context, (List<String>) (sources == null ? Collections.emptyList() : Arrays.asList(sources)));
    }

    public void refresh(final List<Channel> channels, final Callback callback) {
        final Runnable task = () -> doRefresh(channels, callback);
        synchronized (PENDING_LOCK) {
            if (REFRESHING.get()) {
                // Coalesce: only the latest queued request runs next.
                pendingRefresh = task;
                return;
            }
            REFRESHING.set(true);
        }
        task.run();
    }

    private void doRefresh(final List<Channel> channels, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                load(channels == null ? Collections.<Channel>emptyList() : channels, outcome -> {
                    if (callback != null) {
                        main.post(() -> callback.onLoaded(outcome.snapshot, outcome.report));
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "EPG refresh failed: " + safeMessage(e));
                // Audit E7: a failed load must still reach the
                // callback, or callers waiting on it (the "Update
                // EPG now" dialog) hang forever.
                if (callback != null) {
                    List<Channel> safeChannels = channels == null ? Collections.<Channel>emptyList() : channels;
                    EpgSnapshot empty = EpgSnapshot.map(safeChannels, Collections.<Program>emptyList(), defaultAliases());
                    Report failure = new Report(this.sources.size(), 0, 0, this.sources.size(), 0L, 0, 0, safeChannels.size(), 0, 0);
                    main.post(() -> callback.onLoaded(empty, failure));
                }
            } finally {
                Runnable next;
                synchronized (PENDING_LOCK) {
                    next = pendingRefresh;
                    pendingRefresh = null;
                    if (next == null) {
                        REFRESHING.set(false);
                    }
                }
                if (next != null) {
                    next.run();
                }
            }
        }, "epg-refresh").start();
    }

    private LoadOutcome load(final List<Channel> channels, ProgressCallback progress) throws Exception {
        final Instant now = Instant.now();
        final Instant start = now.minus(6L, ChronoUnit.HOURS);
        final Instant end = now.plus(24L, ChronoUnit.HOURS);
        EpgProgressiveMerge merge = new EpgProgressiveMerge(channels, this.sources);
        long downloadedBytes = 0;
        int parsedPrograms = 0;
        int successful = 0;
        int cached = 0;
        int failed = 0;
        final File cacheDirectory = new File(this.context.getCacheDir(), "xmltv");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            Log.w(TAG, "Unable to create XMLTV cache directory");
        }
        final Set<String> targetChannels = new HashSet<>();
        for (Channel ch : channels) {
            if (ch != null) {
                if (ch.tvgId != null && !ch.tvgId.isEmpty()) {
                    targetChannels.add(ch.tvgId);
                    targetChannels.add(ch.tvgId.toLowerCase(Locale.ROOT));
                }
                if (ch.name != null && !ch.name.isEmpty()) {
                    targetChannels.add(ch.name);
                    targetChannels.add(ch.name.toLowerCase(Locale.ROOT));
                }
                if (ch.id != null && !ch.id.isEmpty()) {
                    targetChannels.add(ch.id);
                }
            }
        }
        for (Map.Entry<String, String> alias : defaultAliases().entrySet()) {
            targetChannels.add(alias.getKey());
            targetChannels.add(alias.getValue());
        }
        // Audit E6: canonical name keys let the XMLTV parser accept
        // programmes whose channel id differs from the display name.
        final Set<String> canonicalTargets = new HashSet<>();
        for (Channel ch : channels) {
            if (ch != null) {
                String byName = EpgSnapshot.canonical(ch.name);
                if (!byName.isEmpty()) {
                    canonicalTargets.add(byName);
                }
                String byId = EpgSnapshot.canonical(ch.tvgId);
                if (!byId.isEmpty()) {
                    canonicalTargets.add(byId);
                }
            }
        }

        ExecutorService workers = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "epg-worker");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        CompletionService<SourceLoad> completed = new ExecutorCompletionService<>(workers);
        for (final String source : this.sources) {
            completed.submit(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                return loadOne(source, channels, targetChannels, canonicalTargets, cacheDirectory, start, end, now);
            });
        }
        LoadOutcome result = outcome(channels, merge.snapshot(), now, 0, 0, 0, 0, 0);
        int done = 0;
        try {
            while (done < this.sources.size()) {
                SourceLoad loaded;
                try {
                    // Audit E3: a per-source deadline. One stuck
                    // source used to stall the entire refresh; its
                    // late result is simply consumed by a later poll.
                    Future<SourceLoad> future = completed.poll(3L, TimeUnit.MINUTES);
                    if (future == null) {
                        done++;
                        failed++;
                        Log.w(TAG, "EPG source exceeded the 3 minute deadline");
                        continue;
                    }
                    loaded = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "parallel EPG load interrupted");
                    break;
                } catch (Exception workerFailure) {
                    // Audit E2: one worker's failure (including an
                    // Error wrapped by the executor) must not drop
                    // the remaining sources.
                    done++;
                    failed++;
                    Log.w(TAG, "EPG worker failed: " + safeMessage(workerFailure));
                    continue;
                }
                done++;
                if (loaded.error != null) {
                    failed++;
                    Log.w(TAG, "source=" + sourceLabel(loaded.source) + " failed=" + loaded.error.getClass().getSimpleName() + " message=" + safeMessage(loaded.error));
                } else {
                    SourceOutcome so = loaded.outcome;
                    downloadedBytes += so.downloadedBytes;
                    parsedPrograms += so.programs.size();
                    successful++;
                    if (so.fromCache) {
                        cached++;
                    }
                    merge.add(loaded.source, EpgSnapshot.map(channels, so.programs, defaultAliases()));
                    Log.i(TAG, "source=" + sourceLabel(loaded.source) + " bytes=" + so.downloadedBytes + " programs=" + so.programs.size() + " cache=" + so.fromCache);
                }
                result = outcome(channels, merge.snapshot(), now, downloadedBytes, parsedPrograms, successful, cached, failed);
                Log.i(TAG, result.report.toLogLine());
                progress.onProgress(result);
            }
        } finally {
            workers.shutdownNow();
        }
        if (this.sources.isEmpty()) {
            progress.onProgress(result);
        }
        return result;
    }

    private SourceLoad loadOne(String source, List channels, Set<String> targetChannels, Set<String> canonicalTargets, File cacheDirectory, Instant start, Instant end, Instant now) {
        TitanTvSource.Result titan;
        try {
            if (TitanTvSource.supports(source)) {
                if ("titantv://custom-complete".equals(source) || "titantv://directv-national".equals(source)) {
                    titan = TitanTvSource.load(channels, source, readAsset("titan_custom_channels.json"), this.context);
                } else {
                    titan = TitanTvSource.load(channels, source, null, this.context);
                }
                return new SourceLoad(source, new SourceOutcome(titan.programs, titan.bytes, false), null);
            }
            if ("cdnlive://sports".equals(source)) {
                CdnLiveEpgSource.Result cdn = CdnLiveEpgSource.load(channels);
                return new SourceLoad(source, new SourceOutcome(cdn.programs, cdn.bytes, false), null);
            }
            return new SourceLoad(source, loadSource(cacheDirectory, source, start, end, now, targetChannels, canonicalTargets), null);
        } catch (Throwable failure) {
            // Audit E2: catch Throwable, not just Exception. An Error
            // (OOM while parsing, say) used to escape as an
            // ExecutionException and abort every remaining source.
            Exception error = failure instanceof Exception ? (Exception) failure : new Exception(failure);
            return new SourceLoad(source, null, error);
        }
    }

    private LoadOutcome outcome(List<Channel> channels, EpgSnapshot snapshot, Instant now, long downloadedBytes, int parsedPrograms, int successfulSources, int cachedSources, int failedSources) {
        EpgSnapshot.Diagnostics diagnostics = snapshot.diagnostics(now);
        Report report = new Report(this.sources.size(), successfulSources, cachedSources, failedSources, downloadedBytes, parsedPrograms, diagnostics.mappedChannels, diagnostics.unmatchedChannels, diagnostics.mappedPrograms, diagnostics.currentFuturePrograms);
        return new LoadOutcome(snapshot, report);
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class SourceLoad {
        final Exception error;
        final SourceOutcome outcome;
        final String source;

        SourceLoad(String source, SourceOutcome outcome, Exception error) {
            this.source = source;
            this.outcome = outcome;
            this.error = error;
        }
    }

    private SourceOutcome loadSource(File cacheDirectory, String source, Instant start, Instant end, Instant now, Set<String> targetChannels, Set<String> canonicalTargets) throws Exception {
        File cache = cacheFile(cacheDirectory, source);
        // Audit E8: promote() rotates the old cache to .backup; if a
        // crash landed between the renames, restore it before use.
        File backup = new File(cacheDirectory, cache.getName() + ".backup");
        if (!cache.isFile() && backup.isFile()) {
            backup.renameTo(cache);
        }
        if (cache.isFile() && !isStale(cache.lastModified(), now)) {
            try {
                return new SourceOutcome(parse(cache, source, start, end, targetChannels, canonicalTargets), 0L, true);
            } catch (Exception corruptCache) {
                // Audit E5: a truncated cache with a fresh mtime used
                // to fail this source on every refresh for 6 hours.
                Log.w(TAG, "Cached EPG unreadable, re-downloading: " + safeMessage(corruptCache));
                cache.delete();
            }
        }
        // Audit E1: unique temp name per run; two overlapping loads
        // used to corrupt each other's shared ".download" file.
        File temporary = new File(cacheDirectory, cache.getName() + "." + System.nanoTime() + ".download");
        try {
            long bytes = download(source, temporary);
            List<Program> programs = parse(temporary, source, start, end, targetChannels, canonicalTargets);
            promote(temporary, cache);
            return new SourceOutcome(programs, bytes, false);
        } catch (Exception downloadFailure) {
            if (temporary.exists()) {
                temporary.delete();
            }
            if (cache.isFile()) {
                try {
                    return new SourceOutcome(parse(cache, source, start, end, targetChannels, canonicalTargets), 0L, true);
                } catch (Exception cacheFailure) {
                    downloadFailure.addSuppressed(cacheFailure);
                    throw downloadFailure;
                }
            }
            throw downloadFailure;
        }
    }

    private static long download(String source, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(8000);
        // Explicit 60 s read timeout (audit E3): the old value was an
        // accessibility constant that happened to be in scope.
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, "identity");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            long declared = parseContentLength(connection.getHeaderField(HttpHeaders.CONTENT_LENGTH));
            if (declared > MAX_COMPRESSED_BYTES) {
                throw new IOException("EPG response exceeds 67108864 bytes");
            }
            InputStream input = new BufferedInputStream(connection.getInputStream());
            try {
                OutputStream output = new BufferedOutputStream(new FileOutputStream(destination));
                try {
                    long jCopyBounded = copyBounded(input, output, MAX_COMPRESSED_BYTES);
                    output.close();
                    input.close();
                    connection.disconnect();
                    return jCopyBounded;
                } catch (Throwable th) {
                    try {
                        output.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                try {
                    input.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
                throw th3;
            }
        } catch (Throwable th5) {
            connection.disconnect();
            throw th5;
        }
    }

    private String readAsset(String name) throws IOException {
        InputStream input = this.context.getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                copyBounded(input, output, 262144);
                String string = output.toString(StandardCharsets.UTF_8.name());
                output.close();
                if (input != null) {
                    input.close();
                }
                return string;
            } catch (Throwable th) {
                try {
                    output.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
            }
            throw th3;
        }
    }

    private static List<Program> parse(File file, String source, Instant start, Instant end, Set<String> targetChannels, Set<String> canonicalTargets) throws Exception {
        InputStream raw = new BufferedInputStream(new FileInputStream(file), 65536);
        try {
            // Audit E4: sniff the gzip magic bytes instead of trusting
            // the URL suffix (a ".xml" URL can serve gzip and the
            // reverse), and cap the DECOMPRESSED size: the old cap
            // only bounded the compressed download.
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(raw, 65536) : raw;
            InputStream input = new BoundedInputStream(decoded, MAX_DECOMPRESSED_BYTES);
            try {
                List<Program> list = new XmlTvParser().parse(input, start, end, targetChannels, canonicalTargets);
                input.close();
                raw.close();
                return list;
            } catch (Throwable th) {
                try {
                    input.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable th3) {
            try {
                raw.close();
            } catch (Throwable th4) {
                th3.addSuppressed(th4);
            }
            throw th3;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long seen;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                count(1L);
            }
            return value;
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(long bytes) throws IOException {
            this.seen += bytes;
            if (this.seen > this.limit) {
                throw new IOException("EPG decompressed content exceeds " + this.limit + " bytes");
            }
        }
    }

    private static void promote(File temporary, File cache) throws IOException {
        File backup = new File(cache.getParentFile(), cache.getName() + ".backup");
        if (backup.exists()) {
            backup.delete();
        }
        if (cache.exists() && !cache.renameTo(backup)) {
            throw new IOException("last-known-good XMLTV cache cannot be protected");
        }
        if (!temporary.renameTo(cache)) {
            if (backup.exists()) {
                backup.renameTo(cache);
            }
            throw new IOException("downloaded XMLTV cache cannot be promoted");
        }
        if (backup.exists()) {
            backup.delete();
        }
    }

    private static File cacheFile(File directory, String source) {
        // SHA-256, not the 32-bit String.hashCode (audit R1): two
        // different EPG URLs with colliding hashes used to share one
        // cache file and parse each other's guide.
        String name;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            name = sb.toString();
        } catch (Exception e) {
            name = Integer.toHexString(source.hashCode());
        }
        return new File(directory, name + (source.toLowerCase(Locale.ROOT).endsWith(".gz") ? ".xml.gz" : ".xml"));
    }

    public static boolean isStale(long lastModifiedMillis, Instant now) {
        return now == null || lastModifiedMillis <= 0 || now.toEpochMilli() - lastModifiedMillis >= FRESH_MILLIS;
    }

    public static long copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        if (input == null || output == null || limit < 0) {
            throw new IllegalArgumentException("input, output, and nonnegative limit are required");
        }
        byte[] buffer = new byte[8192];
        long total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read != -1) {
                total += (long) read;
                if (total > limit) {
                    throw new IOException("EPG response exceeds " + limit + " bytes");
                }
                output.write(buffer, 0, read);
            } else {
                output.flush();
                return total;
            }
        }
    }

    public static long parseContentLength(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static Map<String, String> defaultAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("American.Heroes.Channel.HD.us2", "ahcamericanheroeschannel.us");
        aliases.put("American.Heroes.Channel.-.Canada.ca2", "ahcamericanheroeschannel.us");
        aliases.put("Nickelodeon.HD.us2", "nick.us");
        aliases.put("Nick.Jr.HD.us2", "nickjr.us");
        // v4.6.1: playlist ids with no programmes in any source, mapped by
        // hand to the guide id that carries listings (checked 2026-11-10).
        aliases.put("A.and.E.HD.East.us2", "aeusa.us");
        aliases.put("Big.Ten.Network.HD.us2", "bigtennetworkbtnusa.us");
        aliases.put("Fox.Business.HD.us2", "foxbusiness.us");
        aliases.put("MGM+.HD.us2", "mgmusaepix.us");
        aliases.put("Racing.TV.HD.uk", "Racing TV HD.uk");
        aliases.put("HBO.East.us2", "hbousa.us");
        aliases.put("Showtime.2.HD.us2", "showtime2usasho2usa.us");
        aliases.put("Showtime.Next.HD.us2", "showtimenextshonextusa.us");
        aliases.put("Channel.5.HD.uk", "Channel 5 HD.uk");
        aliases.put("S4C.HD.uk", "S4C HD.uk");
        aliases.put("TVO.ca2", "tvoca.us");
        aliases.put("YTV.HD.ca2", "ytvca.us");
        aliases.put("WCBS-DT.us_locals1", "cbsusa.us");
        aliases.put("WABC-DT.us_locals1", "abcusa.us");
        aliases.put("WNBC-DT.us_locals1", "nbcusa.us");
        aliases.put("WNYW-DT.us_locals1", "foxusa.us");
        aliases.put("WNET-DT.us_locals1", "pbsusa.us");
        aliases.put("Galavision.Cable.Network.HD.us2", "galavisinusa.us");
        aliases.put("WE.tv.HD.us2", "wetvusa.us");
        aliases.put("FS1.Fox.Sports.1.HD.us2", "foxsports1usa.us");
        aliases.put("FS2.Fox.Sports.2.HD.us2", "foxsports2usa.us");
        aliases.put("MSG.National.us2", "msgusa.us");
        aliases.put("MASN.-.Mid.Atlantic.Sports.Network.us2", "masnusa.us");
        aliases.put("Turner.Classic.Movies.HD.us2", "tcmusa.us");
        aliases.put("National.Geographic.Wild.HD.us2", "natgeowildusa.us");
        aliases.put("Showtime.Familyzone.HD.us2", "showtimefamilyzoneshofamilyzoneusa.us");
        aliases.put("Crime.and.Investigation.Network.HD.us2", "crimeinvestigationusa.us");
        aliases.put("CBC.Toronto.HD.ca2", "cbcca.us");
        aliases.put("CTV.Two.-.Toronto.ca2", "ctv2canada.us");
        aliases.put("YesTV.ca2", "YES Network HD.ca");
        aliases.put("BBC.One.Lon.HD.uk", "bbconeuk.us");
        aliases.put("SkySp.F1.HD.uk", "Sky Sports F1 HD.uk");
        aliases.put("SkySp.Golf.HD.uk", "Sky Sports Golf HD.uk");
        aliases.put("SkySp.News.HD.uk", "Sky Sports News HD.uk");
        aliases.put("SkySp.Mix.HD.uk", "Sky Sports Mix HD.uk");
        aliases.put("SkySp.Racing.HD.uk", "Sky Sports Racing HD.uk");
        aliases.put("Sky.Premiere.uk", "Sky Cinema Premiere HD.uk");
        aliases.put("Sky.Greats.HD.uk", "Sky Cinema Greats HD.uk");
        aliases.put("Sky.Thriller.HD.uk", "Sky Cinema Thriller HD.uk");
        aliases.put("Sky.Drama.HD.uk", "Sky Cinema Drama HD.uk");
        aliases.put("Sky.Action.HD.uk", "Sky Cinema Action HD.uk");
        aliases.put("Sky.Family.HD.uk", "Sky Cinema Family HD.uk");
        aliases.put("Sky.Sci-Fi.HD.uk", "Sky Cinema Sci Fi and Horror HD.uk");
        return aliases;
    }

    private static String sourceLabel(String source) {
        if (TitanTvSource.supports(source)) {
            return source.replace("titantv://", "TitanTV/");
        }
        if ("cdnlive://sports".equals(source)) {
            return "CDNLive/sports";
        }
        try {
            URL url = new URL(source);
            String path = url.getPath();
            int slash = path.lastIndexOf(47);
            return url.getHost() + "/" + (slash >= 0 ? path.substring(slash + 1) : path);
        } catch (Exception e) {
            return "invalid-source";
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? "none" : message.replaceAll("https?://\\S+", "[url]");
    }

    public static final class Report {
        public final int cachedSources;
        public final int currentFuturePrograms;
        public final int declaredSources;
        public final long downloadedBytes;
        public final int failedSources;
        public final int mappedChannels;
        public final int mappedPrograms;
        public final int parsedPrograms;
        public final int successfulSources;
        public final int unmatchedChannels;

        Report(int declaredSources, int successfulSources, int cachedSources, int failedSources, long downloadedBytes, int parsedPrograms, int mappedChannels, int unmatchedChannels, int mappedPrograms, int currentFuturePrograms) {
            this.declaredSources = declaredSources;
            this.successfulSources = successfulSources;
            this.cachedSources = cachedSources;
            this.failedSources = failedSources;
            this.downloadedBytes = downloadedBytes;
            this.parsedPrograms = parsedPrograms;
            this.mappedChannels = mappedChannels;
            this.unmatchedChannels = unmatchedChannels;
            this.mappedPrograms = mappedPrograms;
            this.currentFuturePrograms = currentFuturePrograms;
        }

        public String toLogLine() {
            return "declaredSources=" + this.declaredSources + " successfulSources=" + this.successfulSources + " cachedSources=" + this.cachedSources + " failedSources=" + this.failedSources + " downloadedBytes=" + this.downloadedBytes + " parsedPrograms=" + this.parsedPrograms + " mappedChannels=" + this.mappedChannels + " unmatchedChannels=" + this.unmatchedChannels + " mappedPrograms=" + this.mappedPrograms + " currentFuturePrograms=" + this.currentFuturePrograms;
        }
    }

    private static final class SourceOutcome {
        final long downloadedBytes;
        final boolean fromCache;
        final List<Program> programs;

        SourceOutcome(List<Program> programs, long downloadedBytes, boolean fromCache) {
            this.programs = programs;
            this.downloadedBytes = downloadedBytes;
            this.fromCache = fromCache;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class LoadOutcome {
        final Report report;
        final EpgSnapshot snapshot;

        LoadOutcome(EpgSnapshot snapshot, Report report) {
            this.snapshot = snapshot;
            this.report = report;
        }
    }
}
