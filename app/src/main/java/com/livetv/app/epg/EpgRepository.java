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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

/* JADX INFO: loaded from: classes3.dex */
public final class EpgRepository {
    private static final long FRESH_MILLIS = 21600000;
    private static final long MAX_COMPRESSED_BYTES = 67108864;
    private static final String TAG = "LiveTV-EPG";
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
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(6, Math.max(1, this.sources.size())));
        CompletionService<SourceLoad> completed = new ExecutorCompletionService<>(workers);
        for (final String source : this.sources) {
            completed.submit(() -> loadOne(source, channels, cacheDirectory, start, end, now));
        }
        LoadOutcome result = outcome(channels, merge.snapshot(), now, 0, 0, 0, 0, 0);
        int done = 0;
        try {
            while (done < this.sources.size()) {
                SourceLoad loaded;
                try {
                    loaded = completed.take().get();
                } catch (Exception interrupted) {
                    Log.w(TAG, "parallel EPG load interrupted: " + safeMessage(interrupted));
                    break;
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

    private SourceLoad loadOne(String source, List channels, File cacheDirectory, Instant start, Instant end, Instant now) throws Exception {
        TitanTvSource.Result titan;
        try {
            if (TitanTvSource.supports(source)) {
                if ("titantv://custom-complete".equals(source) || "titantv://directv-national".equals(source)) {
                    titan = TitanTvSource.load(channels, source, readAsset("titan_custom_channels.json"));
                } else {
                    titan = TitanTvSource.load(channels, source);
                }
                return new SourceLoad(source, new SourceOutcome(titan.programs, titan.bytes, false), null);
            }
            if ("cdnlive://sports".equals(source)) {
                CdnLiveEpgSource.Result cdn = CdnLiveEpgSource.load(channels);
                return new SourceLoad(source, new SourceOutcome(cdn.programs, cdn.bytes, false), null);
            }
            return new SourceLoad(source, loadSource(cacheDirectory, source, start, end, now), null);
        } catch (Exception error) {
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

    private SourceOutcome loadSource(File cacheDirectory, String source, Instant start, Instant end, Instant now) throws Exception {
        File cache = cacheFile(cacheDirectory, source);
        if (cache.isFile() && !isStale(cache.lastModified(), now)) {
            return new SourceOutcome(parse(cache, source, start, end), 0L, true);
        }
        File temporary = new File(cacheDirectory, cache.getName() + ".download");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("stale XMLTV temporary file cannot be replaced");
        }
        try {
            long bytes = download(source, temporary);
            List<Program> programs = parse(temporary, source, start, end);
            promote(temporary, cache);
            return new SourceOutcome(programs, bytes, false);
        } catch (Exception downloadFailure) {
            if (temporary.exists()) {
                temporary.delete();
            }
            if (cache.isFile()) {
                try {
                    return new SourceOutcome(parse(cache, source, start, end), 0L, true);
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
        connection.setReadTimeout(AccessibilityNodeInfoCompat.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH);
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

    private static List<Program> parse(File file, String source, Instant start, Instant end) throws Exception {
        InputStream raw = new BufferedInputStream(new FileInputStream(file), 65536);
        try {
            InputStream input = source.toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(raw, 65536) : raw;
            try {
                List<Program> list = new XmlTvParser().parse(input, start, end);
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
        return new File(directory, Integer.toHexString(source.hashCode()) + (source.toLowerCase(Locale.ROOT).endsWith(".gz") ? ".xml.gz" : ".xml"));
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
