package com.livetv.app.tvinput;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import com.livetv.app.Channel;
import com.livetv.app.ChannelCatalog;
import com.livetv.app.epg.EpgSnapshot;
import com.livetv.app.epg.Program;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes5.dex */
public class ChannelSyncHelper {
    private static final String TAG = "ChannelSyncHelper";

    public interface SyncCallback {
        void onComplete(int i);

        void onError(Exception exc);
    }

    public static void syncChannels(Context context, String inputId, SyncCallback callback) {
        syncChannels(context, inputId, (EpgSnapshot) null, callback);
    }

    // One sync at a time (audit H2): two overlapping syncs used to
    // interleave their deletes and inserts destructively.
    private static final java.util.concurrent.atomic.AtomicBoolean SYNCING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void syncChannels(final Context context, final String inputId, final EpgSnapshot epg, final SyncCallback callback) {
        if (!SYNCING.compareAndSet(false, true)) {
            if (callback != null) {
                callback.onError(new IllegalStateException("Channel sync already running"));
            }
            return;
        }
        new Thread(new Runnable() { // from class: com.livetv.app.tvinput.ChannelSyncHelper$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    ChannelSyncHelper.lambda$syncChannels$0(context, inputId, epg, callback);
                } finally {
                    SYNCING.set(false);
                }
            }
        }).start();
    }

    static /* synthetic */ void lambda$syncChannels$0(Context context, String inputId, EpgSnapshot epg, SyncCallback callback) {
        try {
            List<Channel> catalog = ChannelCatalog.load(context);
            List<ContentValues> channelList = parseBundledChannels(catalog, inputId);
            // An empty catalog aborts BEFORE any delete (audit H3):
            // the old flow wiped the system guide and then reported
            // "Synced 0 channels" as a success.
            if (channelList.isEmpty()) {
                throw new Exception("Catalog is empty; keeping the existing system guide");
            }
            ContentResolver resolver = context.getContentResolver();
            // Upsert by internal_provider_id (audit H1): the old
            // delete-all + bulkInsert regenerated every channel _id
            // per sync, and a kill mid-sync left the guide empty.
            Map<String, Long> existing = new HashMap<>();
            try (Cursor c = resolver.query(TvContract.buildChannelsUriForInput(inputId), new String[]{"_id", "internal_provider_id"}, null, null, null)) {
                if (c != null) {
                    int idCol = c.getColumnIndex("_id");
                    int pidCol = c.getColumnIndex("internal_provider_id");
                    while (c.moveToNext()) {
                        String pid = pidCol >= 0 ? c.getString(pidCol) : null;
                        if (pid != null) {
                            existing.put(pid, Long.valueOf(c.getLong(idCol)));
                        }
                    }
                }
            }
            java.util.Set<String> wanted = new java.util.HashSet<>();
            List<ContentValues> toInsert = new ArrayList<>();
            int updated = 0;
            for (ContentValues row : channelList) {
                String pid = row.getAsString("internal_provider_id");
                wanted.add(pid);
                Long existingId = existing.get(pid);
                if (existingId != null) {
                    resolver.update(TvContract.buildChannelUri(existingId.longValue()), row, null, null);
                    updated++;
                } else {
                    toInsert.add(row);
                }
            }
            int inserted = toInsert.isEmpty() ? 0 : resolver.bulkInsert(TvContract.Channels.CONTENT_URI, (ContentValues[]) toInsert.toArray(new ContentValues[0]));
            int staleDeleted = 0;
            for (Map.Entry<String, Long> e : existing.entrySet()) {
                if (!wanted.contains(e.getKey())) {
                    staleDeleted += resolver.delete(TvContract.buildChannelUri(e.getValue().longValue()), null, null);
                }
            }
            // Programs are only touched when an EPG snapshot was
            // actually supplied (audit H4): the old flow deleted all
            // programs and inserted none when epg was null.
            int deletedPrograms = 0;
            int programs = 0;
            if (epg != null) {
                deletedPrograms = deleteProgramsForInput(resolver, inputId);
                programs = syncPrograms(resolver, inputId, catalog, epg);
            }
            int synced = inserted + updated;
            EpgSnapshot.Diagnostics diagnostics = epg != null ? epg.diagnostics(Instant.now()) : null;
            int mapped = diagnostics != null ? diagnostics.mappedChannels : 0;
            Log.i(TAG, "syncComplete channels=" + synced + " (new=" + inserted + ", updated=" + updated + ", staleDeleted=" + staleDeleted + ") mappedChannels=" + mapped + " programs=" + programs + " deletedPrograms=" + deletedPrograms);
            if (callback != null) {
                callback.onComplete(synced);
            }
            // Logos are decoration (audit H5): they no longer gate
            // completion - the old sequential waits had a ~105 minute
            // worst case holding the setup screen hostage.
            final ContentResolver logoResolver = resolver;
            new Thread(new Runnable() {
                @Override public void run() {
                    syncChannelLogos(logoResolver, inputId);
                }
            }, "logo-sync").start();
        } catch (Throwable e) {
            // Throwable, not Exception (audit H9): an Error used to
            // leave the callback uncalled and the setup screen hung.
            if (callback != null) {
                callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
            }
        }
    }

    private static List<ContentValues> parseBundledChannels(List<Channel> catalog, String inputId) throws Exception {
        List<ContentValues> values = new ArrayList<>();
        int number = 1;
        for (Channel channel : catalog) {
            ContentValues row = new ContentValues();
            row.put("input_id", inputId);
            // The stable identity the upsert keys on (audit H1, H8).
            row.put("internal_provider_id", channel.id);
            int number2 = number + 1;
            row.put("display_number", String.valueOf(number));
            row.put("display_name", channel.name);
            row.put("network_affiliation", channel.group);
            row.put("type", "TYPE_OTHER");
            JSONObject providerData = new JSONObject();
            providerData.put("streamUrl", channel.playbackUrl());
            providerData.put("tvgId", channel.tvgId);
            if (!channel.logo.isEmpty()) {
                providerData.put("logoUrl", channel.logo);
            }
            row.put("internal_provider_data", providerData.toString());
            row.put("searchable", (Integer) 1);
            values.add(row);
            number = number2;
        }
        return values;
    }

    private static int syncPrograms(ContentResolver resolver, String inputId, List<Channel> catalog, EpgSnapshot epg) throws Exception {
        List<ContentValues> rows = new ArrayList<>();
        // Match by the stable provider id (audit H7): display names
        // collide, and every row keyed by name could inherit another
        // channel's programmes. Name matching survives only as a
        // fallback for rows synced before provider ids existed.
        Map<String, Channel> byId = new HashMap<>();
        Map<String, Channel> byName = new HashMap<>();
        for (Channel channel : catalog) {
            byId.put(channel.id, channel);
            byName.put(channel.name, channel);
        }
        try (Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(inputId), new String[]{"_id", "display_name", "internal_provider_id"}, null, null, null)) {
            if (cursor == null) {
                return 0;
            }
            int idCol = cursor.getColumnIndex("_id");
            int nameCol = cursor.getColumnIndex("display_name");
            int pidCol = cursor.getColumnIndex("internal_provider_id");
            while (cursor.moveToNext()) {
                String pid = pidCol >= 0 ? cursor.getString(pidCol) : null;
                Channel match = pid != null ? byId.get(pid) : null;
                if (match == null) {
                    match = byName.get(cursor.getString(nameCol));
                }
                if (match == null) continue;
                for (Program program : epg.programsFor(match)) {
                    ContentValues row = new ContentValues();
                    row.put("channel_id", Long.valueOf(cursor.getLong(idCol)));
                    row.put("title", program.title);
                    if (program.description != null) {
                        row.put("short_description", program.description);
                    }
                    row.put("start_time_utc_millis", Long.valueOf(program.start.toEpochMilli()));
                    row.put("end_time_utc_millis", Long.valueOf(program.end.toEpochMilli()));
                    rows.add(row);
                }
            }
        }
        int inserted = 0;
        int offset = 0;
        for (int size : batchSizes(rows.size(), 300)) {
            ContentValues[] batch = rows.subList(offset, offset + size).toArray(new ContentValues[0]);
            inserted += resolver.bulkInsert(TvContract.Programs.CONTENT_URI, batch);
            offset += size;
        }
        return inserted;
    }

    private static int deleteProgramsForInput(ContentResolver resolver, String inputId) {
        List<Long> ids = new ArrayList<>();
        try {
            Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(inputId), new String[]{"_id"}, null, null, null);
            if (cursor != null) {
                try {
                    int idColumn = cursor.getColumnIndex("_id");
                    while (cursor.moveToNext()) {
                        ids.add(Long.valueOf(cursor.getLong(idColumn)));
                    }
                } catch (Throwable th) {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            }
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception error) {
            Log.w(TAG, "Old program inventory warning: " + error.getMessage());
        }
        int deleted = 0;
        Iterator<Long> it = ids.iterator();
        while (it.hasNext()) {
            long channelId = it.next().longValue();
            deleted += resolver.delete(TvContract.buildProgramsUriForChannel(channelId), null, null);
        }
        return deleted;
    }

    public static int[] batchSizes(int total, int maximum) {
        if (total < 0 || maximum <= 0) {
            throw new IllegalArgumentException("nonnegative total and positive maximum are required");
        }
        int[] sizes = new int[((total + maximum) - 1) / maximum];
        int remaining = total;
        for (int index = 0; index < sizes.length; index++) {
            sizes[index] = Math.min(maximum, remaining);
            remaining -= sizes[index];
        }
        return sizes;
    }

    private static int syncChannelLogos(final ContentResolver resolver, String inputId) {
        List<LogoJob> jobs = new ArrayList<>();
        try {
            Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(inputId), new String[]{"_id", "internal_provider_data"}, null, null, "_id ASC");
            if (cursor != null) {
                try {
                    int idCol = cursor.getColumnIndex("_id");
                    int dataCol = cursor.getColumnIndex("internal_provider_data");
                    while (cursor.moveToNext()) {
                        String data = cursor.getString(dataCol);
                        if (data != null && !data.isEmpty()) {
                            JSONObject providerData = new JSONObject(data);
                            String logoUrl = providerData.optString("logoUrl", "");
                            if (!logoUrl.isEmpty()) {
                                long channelId = cursor.getLong(idCol);
                                jobs.add(new LogoJob(channelId, logoUrl));
                            }
                        }
                    }
                    if (cursor != null) {
                        cursor.close();
                    }
                    ExecutorService executor = Executors.newFixedThreadPool(8);
                    List<Future<Boolean>> futures = new ArrayList<>();
                    for (final LogoJob job : jobs) {
                        futures.add(executor.submit(new Callable() { // from class: com.livetv.app.tvinput.ChannelSyncHelper$$ExternalSyntheticLambda0
                            @Override // java.util.concurrent.Callable
                            public final Object call() {
                                ContentResolver contentResolver = resolver;
                                ChannelSyncHelper.LogoJob logoJob = job;
                                return Boolean.valueOf(ChannelSyncHelper.downloadLogo(contentResolver, logoJob.channelId, logoJob.logoUrl));
                            }
                        }));
                    }
                    int count = 0;
                    // One overall deadline (audit H5): the old loop
                    // waited up to 7 s PER LOGO in sequence.
                    long deadline = System.currentTimeMillis() + 60_000L;
                    for (Future<Boolean> future : futures) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0L) {
                            future.cancel(true);
                            continue;
                        }
                        try {
                            if (future.get(remaining, TimeUnit.MILLISECONDS).booleanValue()) {
                                count++;
                            }
                        } catch (Exception e) {
                            future.cancel(true);
                        }
                    }
                    executor.shutdownNow();
                    Log.i(TAG, "Synced " + count + " official channel logos to Google TV");
                    return count;
                } catch (Throwable th) {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            }
            if (cursor != null) {
                cursor.close();
            }
            return 0;
        } catch (Exception e2) {
            Log.w(TAG, "Logo sync completed with warnings: " + e2.getMessage());
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class LogoJob {
        final long channelId;
        final String logoUrl;

        LogoJob(long channelId, String logoUrl) {
            this.channelId = channelId;
            this.logoUrl = logoUrl;
        }
    }

    /* JADX WARN: Code duplicated, block: B:59:0x007f A[EXC_TOP_SPLITTER, SYNTHETIC] */
    private static boolean downloadLogo(ContentResolver resolver, long channelId, String logoUrl) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(logoUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            // Any 200 body used to be written as the logo (audit
            // H6): require an image content type and cap the size.
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return false;
            }
            try (java.io.InputStream input = connection.getInputStream();
                 java.io.OutputStream output = resolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))) {
                if (output == null) {
                    return false;
                }
                byte[] buffer = new byte[8192];
                int read;
                long total = 0L;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 512L * 1024L) {
                        return false;
                    }
                    output.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Logo download failed: " + logoUrl);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

}
