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

    public static void syncChannels(final Context context, final String inputId, final EpgSnapshot epg, final SyncCallback callback) {
        new Thread(new Runnable() { // from class: com.livetv.app.tvinput.ChannelSyncHelper$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                ChannelSyncHelper.lambda$syncChannels$0(context, inputId, epg, callback);
            }
        }).start();
    }

    static /* synthetic */ void lambda$syncChannels$0(Context context, String inputId, EpgSnapshot epg, SyncCallback callback) {
        try {
            List<Channel> catalog = ChannelCatalog.load(context);
            List<ContentValues> channelList = parseBundledChannels(catalog, inputId);
            ContentResolver resolver = context.getContentResolver();
            int deletedPrograms = deleteProgramsForInput(resolver, inputId);
            resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null);
            int i = 0;
            int inserted = resolver.bulkInsert(TvContract.Channels.CONTENT_URI, (ContentValues[]) channelList.toArray(new ContentValues[0]));
            int programs = epg == null ? 0 : syncPrograms(resolver, inputId, catalog, epg);
            int logos = syncChannelLogos(resolver, inputId);
            EpgSnapshot.Diagnostics diagnostics = epg != null ? epg.diagnostics(Instant.now()) : null;
            StringBuilder sbAppend = new StringBuilder().append("syncComplete channels=").append(inserted).append(" logos=").append(logos).append(" mappedChannels=");
            if (diagnostics != null) {
                i = diagnostics.mappedChannels;
            }
            Log.i(TAG, sbAppend.append(i).append(" programs=").append(programs).append(" deletedPrograms=").append(deletedPrograms).toString());
            if (callback != null) {
                callback.onComplete(inserted);
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onError(e);
            }
        }
    }

    public static void syncChannels(Context context, String inputId, String serverUrl, SyncCallback callback) {
        syncChannels(context, inputId, callback);
    }

    private static List<ContentValues> parseBundledChannels(List<Channel> catalog, String inputId) throws Exception {
        List<ContentValues> values = new ArrayList<>();
        int number = 1;
        for (Channel channel : catalog) {
            ContentValues row = new ContentValues();
            row.put("input_id", inputId);
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
        Map<String, Channel> byName = new HashMap<>();
        for (Channel channel : catalog) {
            byName.put(channel.name, channel);
        }
        try (Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(inputId), new String[]{"_id", "display_name"}, null, null, null)) {
            if (cursor == null) {
                return 0;
            }
            int idCol = cursor.getColumnIndex("_id");
            int nameCol = cursor.getColumnIndex("display_name");
            while (cursor.moveToNext()) {
                Channel match = byName.get(cursor.getString(nameCol));
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

    private static List<ContentValues> parseChannelsFromApi(String serverUrl, String inputId) throws Exception {
        Throwable th;
        List<ContentValues> channels = new ArrayList<>();
        URL url = new URL(serverUrl.replaceAll("/$", "") + "/api/proxy/m3u/1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        StringBuilder body = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        while (true) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                try {
                    body.append(line);
                } catch (Throwable th2) {
                    th = th2;
                    try {
                        reader.close();
                        throw th;
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                        throw th;
                    }
                }
            } catch (Throwable th4) {
                th = th4;
            }
        }
        reader.close();
        JSONArray channelArray = new JSONObject(body.toString()).optJSONArray("channels");
        if (channelArray == null) {
            return channels;
        }
        int i = 0;
        while (i < channelArray.length()) {
            JSONObject channel = channelArray.getJSONObject(i);
            String name = channel.optString("name", channel.optString("tvgName", "Channel " + (i + 1)));
            String group = channel.optString("groupTitle", "Live TV");
            String stream = channel.optString("url", channel.optString("stream_url", ""));
            if (!stream.isEmpty()) {
                String logo = channel.optString("tvgLogo", channel.optString("stream_icon", ""));
                ContentValues values = new ContentValues();
                values.put("input_id", inputId);
                values.put("display_number", String.valueOf(i + 1));
                values.put("display_name", name);
                values.put("network_affiliation", group);
                values.put("type", "TYPE_OTHER");
                JSONObject providerData = new JSONObject();
                providerData.put("streamUrl", stream);
                if (!logo.isEmpty()) {
                    providerData.put("logoUrl", logo);
                }
                values.put("internal_provider_data", providerData.toString());
                values.put("searchable", (Integer) 1);
                channels.add(values);
            }
            i++;
            channelArray = channelArray;
            url = url;
        }
        return channels;
    }

    private static boolean isEnglishChannel(String name, String group) {
        String text = ((name == null ? "" : name) + " " + (group != null ? group : "")).toLowerCase();
        if (text.matches(".*(english|usa|\\bus\\b|uk|united kingdom|canada|australia|new zealand|ireland|bbc|cnn|fox news|discovery|history|animal planet|national geographic|espn|nba tv|nfl network|nhl network|mlb network|sky news).*")) {
            return true;
        }
        return !text.matches(".*(arabic|\\barab\\b|france|french|spain|spanish|espa[nñ]ol|mexico|\\bmx\\b|portugal|portuguese|brasil|brazil|turkey|turkish|serbia|serbian|croatia|croatian|poland|polish|bulgaria|bulgarian|germany|german|\\bde\\b|italy|italian|romania|romanian|russia|russian|ukraine|ukrainian|greece|greek|denmark|danish|sweden|swedish|norway|norwegian|netherlands|dutch|israel|hebrew|japan|japanese|korea|korean|china|chinese|india|hindi|pakistan|malaysia|indonesia|qatar|uae|mena|afrique|africa).*");
    }

    private static List<ContentValues> parseM3uFromUrl(String m3uUrl, String inputId) throws Exception {
        List<ContentValues> channels = new ArrayList<>();
        URL url = new URL(m3uUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String currentName = null;
        String currentLogo = null;
        String currentGroup = null;
        int channelNum = 1;
        while (true) {
            String line = reader.readLine();
            if (line != null) {
                String line2 = line.trim();
                if (line2.startsWith("#EXTINF:")) {
                    currentName = parseAttribute(line2, ",");
                    currentLogo = parseAttribute(line2, "tvg-logo=\"");
                    currentGroup = parseAttribute(line2, "group-title=\"");
                } else if (!line2.startsWith("#") && !line2.isEmpty() && currentName != null) {
                    ContentValues values = new ContentValues();
                    values.put("input_id", inputId);
                    int channelNum2 = channelNum + 1;
                    values.put("display_number", String.valueOf(channelNum));
                    values.put("display_name", currentName);
                    values.put("network_affiliation", currentGroup);
                    values.put("type", "TYPE_OTHER");
                    JSONObject providerData = new JSONObject();
                    providerData.put("streamUrl", line2);
                    if (currentLogo != null && !currentLogo.isEmpty()) {
                        providerData.put("logoUrl", currentLogo);
                    }
                    values.put("internal_provider_data", providerData.toString());
                    values.put("searchable", (Integer) 1);
                    channels.add(values);
                    currentName = null;
                    channelNum = channelNum2;
                }
            } else {
                reader.close();
                return channels;
            }
        }
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
                    for (Future<Boolean> future : futures) {
                        try {
                            if (future.get(7L, TimeUnit.SECONDS).booleanValue()) {
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
            try (java.io.InputStream input = connection.getInputStream();
                 java.io.OutputStream output = resolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))) {
                if (output == null) {
                    return false;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
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

    private static String parseAttribute(String line, String key) {
        int start;
        int end;
        try {
            if (key.equals(",")) {
                int comma = line.lastIndexOf(44);
                if (comma != -1) {
                    return line.substring(comma + 1).trim();
                }
                return null;
            }
            int start2 = line.indexOf(key);
            if (start2 != -1 && (end = line.indexOf("\"", (start = start2 + key.length()))) != -1) {
                return line.substring(start, end);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
