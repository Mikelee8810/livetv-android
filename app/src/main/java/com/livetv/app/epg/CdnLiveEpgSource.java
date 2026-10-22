package com.livetv.app.epg;

import androidx.core.app.NotificationCompat;
import com.livetv.app.Channel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes3.dex */
final class CdnLiveEpgSource {
    private static final Set<String> NOISE = Collections.unmodifiableSet(new HashSet(Arrays.asList("the", "usa", "us", "tv", "television", "channel", "network", "hd", "fhd", "east", "west", "ca", "canada", "uk", "gb", "au", "australia")));
    static final String SOURCE = "cdnlive://sports";
    private static final String URL = "https://api.cdnlivetv.is/api/v1/events/sports/?user=cdnlivetv&plan=free";

    private CdnLiveEpgSource() {
    }

    static Result load(List<Channel> channels) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8L, TimeUnit.SECONDS).readTimeout(20L, TimeUnit.SECONDS).build();
        Response response = client.newCall(new Request.Builder().url(URL).build()).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("CDNLive HTTP " + response.code());
            }
            String json = response.body().string();
            if (response != null) {
                response.close();
            }
            return new Result(parsePrograms(channels, json), json.length());
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

    static List<Program> parsePrograms(List<Channel> channels, String json) throws Exception {
        Map<String, Channel> appByName;
        JSONObject groups;
        Iterator<String> sports;
        Instant end;
        Instant start;
        Map<String, Channel> appByName2 = uniqueAppNames(channels);
        Map<String, Program> unique = new HashMap<>();
        JSONObject groups2 = new JSONObject(json).optJSONObject("cdn-live-tv");
        if (groups2 == null) {
            return Collections.emptyList();
        }
        Iterator<String> sports2 = groups2.keys();
        while (sports2.hasNext()) {
            String sport = sports2.next();
            JSONArray events = groups2.optJSONArray(sport);
            if (events != null) {
                int eventIndex = 0;
                while (eventIndex < events.length()) {
                    JSONObject event = events.optJSONObject(eventIndex);
                    if (event == null) {
                        appByName = appByName2;
                        groups = groups2;
                        sports = sports2;
                    } else {
                        Instant start2 = parseUtc(event.optString("start"));
                        Instant end2 = parseUtc(event.optString("end"));
                        if (start2 == null || end2 == null) {
                            appByName = appByName2;
                            groups = groups2;
                            sports = sports2;
                        } else if (end2.isAfter(start2)) {
                            JSONArray stations = event.optJSONArray("channels");
                            if (stations == null) {
                                appByName = appByName2;
                                groups = groups2;
                                sports = sports2;
                            } else {
                                int stationIndex = 0;
                                while (stationIndex < stations.length()) {
                                    JSONObject station = stations.optJSONObject(stationIndex);
                                    if (station == null) {
                                        appByName2 = appByName2;
                                        groups2 = groups2;
                                        end = end2;
                                        start = start2;
                                    } else {
                                        Channel channel = appByName2.get(canonical(station.optString("channel_name")));
                                        if (channel == null) {
                                            appByName2 = appByName2;
                                            groups2 = groups2;
                                            end = end2;
                                            start = start2;
                                        } else {
                                            String title = event.optString(NotificationCompat.CATEGORY_EVENT);
                                            String title2 = title.isEmpty() ? event.optString("homeTeam") + " vs " + event.optString("awayTeam") : title;
                                            String description = event.optString("tournament");
                                            if (!event.optString("country").isEmpty()) {
                                                description = description + (description.isEmpty() ? "" : " • ") + event.optString("country");
                                            }
                                            end = end2;
                                            Program program = new Program(channel.tvgId.isEmpty() ? channel.name : channel.tvgId, channel.name, title2.trim(), "", description, start2, end, sport, "");
                                            start = start2;
                                            unique.put(channel.id + "|" + start + "|" + title2, program);
                                        }
                                    }
                                    stationIndex++;
                                    start2 = start;
                                    appByName2 = appByName2;
                                    groups2 = groups2;
                                    stations = stations;
                                    end2 = end;
                                    sports2 = sports2;
                                    event = event;
                                    eventIndex = eventIndex;
                                    events = events;
                                }
                                appByName = appByName2;
                                groups = groups2;
                                sports = sports2;
                            }
                        } else {
                            appByName = appByName2;
                            groups = groups2;
                            sports = sports2;
                        }
                    }
                    eventIndex++;
                    appByName2 = appByName;
                    groups2 = groups;
                    sports2 = sports;
                    events = events;
                }
            }
        }
        return new ArrayList(unique.values());
    }

    private static Map<String, Channel> uniqueAppNames(List<Channel> channels) {
        Channel old;
        Map<String, Channel> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Channel channel : channels) {
            String[] strArr = {channel.name, channel.name.replaceAll("\\([^)]*\\)", " "), channel.tvgId.replaceFirst("\\.[^.]+$", "")};
            for (int i = 0; i < 3; i++) {
                String source = strArr[i];
                String key = canonical(source);
                if (!key.isEmpty() && !ambiguous.contains(key) && (old = values.putIfAbsent(key, channel)) != null && !old.id.equals(channel.id)) {
                    values.remove(key);
                    ambiguous.add(key);
                }
            }
        }
        return values;
    }

    private static String canonical(String value) {
        String ascii = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace("&", " and ");
        StringBuilder output = new StringBuilder();
        for (String token : ascii.split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !NOISE.contains(token)) {
                output.append(token);
            }
        }
        return output.toString();
    }

    private static Instant parseUtc(String value) {
        try {
            String normalized = value.replace(' ', 'T');
            return normalized.endsWith("Z") ? Instant.parse(normalized) : LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    static final class Result {
        final long bytes;
        final List<Program> programs;

        Result(List<Program> programs, long bytes) {
            this.programs = programs;
            this.bytes = bytes;
        }
    }
}
