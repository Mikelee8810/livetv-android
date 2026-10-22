package com.livetv.app.epg;

import androidx.exifinterface.media.ExifInterface;
import com.livetv.app.Channel;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes3.dex */
final class TitanTvSource {
    private static final String API = "https://www.titantv.com/api/";
    static final String CUSTOM = "titantv://custom-complete";
    static final String DIRECTV = "titantv://directv-national";
    static final String SLING = "titantv://sling-national";
    static final String SPECTRUM = "titantv://spectrum-manhattan";
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TOKEN = DateTimeFormatter.ofPattern("yyyyMMddHH'00'");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\(([^)]+)\\)");
    private static final Set<String> NOISE = Collections.unmodifiableSet(new HashSet(Arrays.asList("the", "usa", "us", "tv", "television", "channel", "network", "hd", "fhd", "east", "eastern", "west", "western", "feed")));

    private TitanTvSource() {
    }

    static boolean supports(String source) {
        return DIRECTV.equals(source) || SPECTRUM.equals(source) || SLING.equals(source) || CUSTOM.equals(source);
    }

    static Result load(List<Channel> channels, String source) throws Exception {
        return load(channels, source, null);
    }

    static Result load(List<Channel> channels, String source, String customChannelsJson) throws Exception {
        Provider provider = Provider.forSource(source);
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8L, TimeUnit.SECONDS).readTimeout(20L, TimeUnit.SECONDS).build();
        String guest = get(client, "https://www.titantv.com/api/user/guest");
        String userId = new JSONObject(guest).getString("userId");
        String lineupBody = "{\"lineupName\":\"" + provider.name + "\",\"utcOffset\":-300,\"observesDST\":true,\"providerId\":" + provider.id + ",\"deviceTypeCode\":\"" + provider.deviceType + "\"}";
        String lineupResult = put(client, "https://www.titantv.com/api/lineup/" + userId, lineupBody);
        String lineupId = new JSONObject(lineupResult).getString("lineupId");
        if ((CUSTOM.equals(source) || DIRECTV.equals(source)) && customChannelsJson != null) {
            JSONArray custom = new JSONArray(customChannelsJson);
            for (int index = 0; index < custom.length(); index++) {
                JSONObject station = custom.getJSONObject(index);
                JSONObject body = new JSONObject();
                body.put("channelId", station.getInt("channelId"));
                body.put("originalRfChannel", station.optInt("rfChannel", 1));
                body.put("rfChannel", station.optInt("rfChannel", 1));
                body.put("majorChannel", station.optInt("majorChannel", 1));
                body.put("minorChannel", station.optInt("minorChannel", 1));
                body.put("callSign", station.optString("callSign"));
                body.put("description", station.optString("description"));
                body.put("network", station.optString("network"));
                body.put("sortOrder", station.optDouble("sortOrder", index + 1));
                body.put("providerTypeId", station.optInt("providerTypeId", 3));
                body.put("lineupAction", ExifInterface.GPS_MEASUREMENT_IN_PROGRESS);
                body.put("errorCode", 0);
                body.put("errorMessage", "");
                post(client, "https://www.titantv.com/api/customization/channel/" + userId + "/" + lineupId, body.toString());
            }
        }
        String lineup = get(client, "https://www.titantv.com/api/channel/" + userId + "/" + lineupId);
        String token = ZonedDateTime.now(EASTERN).withMinute(0).withSecond(0).withNano(0).format(TOKEN);
        String schedule = get(client, "https://www.titantv.com/api/schedule/" + userId + "/" + lineupId + "/" + token + "/6");
        List<Program> programs = parsePrograms(channels, lineup, schedule);
        return new Result(programs, lineup.length() + schedule.length());
    }

    static List<Program> parsePrograms(List<Channel> channels, String lineupJson, String scheduleJson) throws Exception {
        JSONArray days;
        JSONArray events;
        JSONArray schedule;
        JSONArray lineup;
        String str = "";
        Map<String, Channel> appByName = uniqueAppNames(channels);
        Map<String, Choice> bestByAppId = new HashMap<>();
        JSONArray lineup2 = new JSONObject(lineupJson).getJSONArray("channels");
        for (int index = 0; index < lineup2.length(); index++) {
            JSONObject station = lineup2.getJSONObject(index);
            Channel matched = null;
            int score = 0;
            String[] values = {station.optString("description"), station.optString("network"), station.optString("callSign")};
            int[] weights = {30, 25, 20};
            for (int valueIndex = 0; valueIndex < values.length; valueIndex++) {
                Channel candidate = appByName.get(canonical(values[valueIndex]));
                if (candidate != null && weights[valueIndex] > score) {
                    matched = candidate;
                    score = weights[valueIndex];
                }
            }
            if (matched != null) {
                if (station.optBoolean("hdCapable")) {
                    score += 2;
                }
                Choice previous = bestByAppId.get(matched.id);
                if (previous == null || score > previous.score) {
                    bestByAppId.put(matched.id, new Choice(station.getInt("channelIndex"), matched, score));
                }
            }
        }
        Map<Integer, Channel> appByTitanIndex = new HashMap<>();
        for (Choice choice : bestByAppId.values()) {
            appByTitanIndex.put(Integer.valueOf(choice.channelIndex), choice.channel);
        }
        List<Program> programs = new ArrayList<>();
        JSONArray schedule2 = new JSONObject(scheduleJson).getJSONArray("channels");
        int channelIndex = 0;
        while (channelIndex < schedule2.length()) {
            JSONObject titanChannel = schedule2.getJSONObject(channelIndex);
            Channel appChannel = appByTitanIndex.get(Integer.valueOf(titanChannel.getInt("channelIndex")));
            if (appChannel != null && (days = titanChannel.optJSONArray("days")) != null) {
                int dayIndex = 0;
                while (dayIndex < days.length()) {
                    Map<String, Channel> appByName2 = appByName;
                    JSONArray events2 = days.getJSONObject(dayIndex).optJSONArray("events");
                    if (events2 != null) {
                        int eventIndex = 0;
                        while (eventIndex < events2.length()) {
                            JSONObject event = events2.getJSONObject(eventIndex);
                            try {
                                events = events2;
                                try {
                                    if (appChannel.tvgId.isEmpty()) {
                                        try {
                                            str = appChannel.name;
                                        } catch (Exception e) {
                                            schedule = schedule2;
                                            lineup = lineup2;
                                        }
                                    } else {
                                        str = appChannel.tvgId;
                                    }
                                    schedule = schedule2;
                                    try {
                                        lineup = lineup2;
                                        try {
                                            programs.add(new Program(str, appChannel.name, event.optString("title"), event.optString("episodeTitle"), event.optString("description"), LocalDateTime.parse(event.getString("startTime")).atZone(EASTERN).toInstant(), LocalDateTime.parse(event.getString("endTime")).atZone(EASTERN).toInstant(), event.optString("displayGenre"), event.optString("tvRating")));
                                        } catch (Exception e2) {
                                        }
                                    } catch (Exception e3) {
                                        lineup = lineup2;
                                    }
                                } catch (Exception e4) {
                                    schedule = schedule2;
                                    lineup = lineup2;
                                }
                            } catch (Exception e5) {
                                events = events2;
                                schedule = schedule2;
                                lineup = lineup2;
                            }
                            eventIndex++;
                            events2 = events;
                            schedule2 = schedule;
                            lineup2 = lineup;
                        }
                    }
                    dayIndex++;
                    appByName = appByName2;
                    bestByAppId = bestByAppId;
                    schedule2 = schedule2;
                    lineup2 = lineup2;
                }
            }
            channelIndex++;
            appByName = appByName;
            bestByAppId = bestByAppId;
            schedule2 = schedule2;
            lineup2 = lineup2;
        }
        return programs;
    }

    private static Map<String, Channel> uniqueAppNames(List<Channel> channels) {
        Channel previous;
        Map<String, Channel> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Channel channel : channels) {
            Set<String> aliases = new HashSet<>();
            aliases.add(channel.name);
            aliases.add(channel.name.replaceAll("\\([^)]*\\)", " "));
            Matcher parenthetical = PARENTHETICAL.matcher(channel.name);
            while (parenthetical.find()) {
                aliases.add(parenthetical.group(1));
            }
            aliases.add(channel.tvgId.replaceFirst("\\.[^.]+$", ""));
            for (String source : aliases) {
                String key = canonical(source);
                if (!key.isEmpty() && !ambiguous.contains(key) && (previous = values.putIfAbsent(key, channel)) != null && !previous.id.equals(channel.id)) {
                    values.remove(key);
                    ambiguous.add(key);
                }
            }
        }
        return values;
    }

    private static String canonical(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace("&", " and ");
        StringBuilder output = new StringBuilder();
        for (String token : ascii.split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !NOISE.contains(token)) {
                output.append(token);
            }
        }
        return output.toString();
    }

    private static String get(OkHttpClient client, String url) throws IOException {
        return execute(client, new Request.Builder().url(url).build());
    }

    private static String put(OkHttpClient client, String url, String json) throws IOException {
        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
        return execute(client, new Request.Builder().url(url).put(body).build());
    }

    private static String post(OkHttpClient client, String url, String json) throws IOException {
        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
        return execute(client, new Request.Builder().url(url).post(body).build());
    }

    private static String execute(OkHttpClient client, Request request) throws IOException {
        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("TitanTV HTTP " + response.code());
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

    static final class Result {
        final long bytes;
        final List<Program> programs;

        Result(List<Program> programs, long bytes) {
            this.programs = programs;
            this.bytes = bytes;
        }
    }

    private static final class Choice {
        final Channel channel;
        final int channelIndex;
        final int score;

        Choice(int channelIndex, Channel channel, int score) {
            this.channelIndex = channelIndex;
            this.channel = channel;
            this.score = score;
        }
    }

    private static final class Provider {
        final String deviceType;
        final int id;
        final String name;

        Provider(String name, int id, String deviceType) {
            this.name = name;
            this.id = id;
            this.deviceType = deviceType;
        }

        static Provider forSource(String source) {
            if (TitanTvSource.DIRECTV.equals(source)) {
                return new Provider("DirecTV National", 10000486, ExifInterface.LATITUDE_SOUTH);
            }
            if (TitanTvSource.SPECTRUM.equals(source)) {
                return new Provider("Spectrum Manhattan", 92600, "D");
            }
            if (TitanTvSource.SLING.equals(source)) {
                return new Provider("Sling TV National", 10003640, "O");
            }
            if (TitanTvSource.CUSTOM.equals(source)) {
                return new Provider("Live TV Complete", 10000486, ExifInterface.LATITUDE_SOUTH);
            }
            throw new IllegalArgumentException("Unsupported TitanTV source");
        }
    }
}
