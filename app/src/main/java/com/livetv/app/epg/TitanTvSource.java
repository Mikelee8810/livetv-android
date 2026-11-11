package com.livetv.app.epg;

import com.livetv.app.Channel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    // Coast tokens removed (audit T7): same fix as EpgSnapshot S3.
    private static final Set<String> NOISE = Collections.unmodifiableSet(new HashSet(Arrays.asList("the", "usa", "us", "tv", "television", "channel", "network", "hd", "fhd")));
    // Shared client (audit T8): one per call leaked pools per refresh.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(8L, TimeUnit.SECONDS).readTimeout(20L, TimeUnit.SECONDS).callTimeout(30L, TimeUnit.SECONDS).build();
    private static final long MAX_BODY_BYTES = 8000000L;

    private TitanTvSource() {
    }

    static boolean supports(String source) {
        return DIRECTV.equals(source) || SPECTRUM.equals(source) || SLING.equals(source) || CUSTOM.equals(source);
    }

    static Result load(List<Channel> channels, String source) throws Exception {
        return load(channels, source, null);
    }

    static Result load(List<Channel> channels, String source, String customChannelsJson) throws Exception {
        return load(channels, source, customChannelsJson, null);
    }

    static Result load(List<Channel> channels, String source, String customChannelsJson, android.content.Context context) throws Exception {
        Provider provider = Provider.forSource(source);
        OkHttpClient client = CLIENT;
        // Audit T1: the guest user and lineup used to be recreated on
        // EVERY refresh (plus one serial POST per custom station, any
        // one of which aborted the whole source). Cache the ids and
        // only recreate when TitanTV rejects them.
        android.content.SharedPreferences cache = context != null ? context.getSharedPreferences("titantv", android.content.Context.MODE_PRIVATE) : null;
        String userId = cache != null ? cache.getString(source + ":user", null) : null;
        String lineupId = cache != null ? cache.getString(source + ":lineup", null) : null;
        String lineup;
        String schedule;
        if (userId == null || lineupId == null) {
            String[] ids = createLineup(client, provider, source, customChannelsJson);
            userId = ids[0];
            lineupId = ids[1];
            if (cache != null) {
                cache.edit().putString(source + ":user", userId).putString(source + ":lineup", lineupId).apply();
            }
            lineup = get(client, "https://www.titantv.com/api/channel/" + userId + "/" + lineupId);
            schedule = fetchSchedule(client, userId, lineupId);
        } else {
            try {
                lineup = get(client, "https://www.titantv.com/api/channel/" + userId + "/" + lineupId);
                schedule = fetchSchedule(client, userId, lineupId);
            } catch (IOException staleIds) {
                String[] ids = createLineup(client, provider, source, customChannelsJson);
                userId = ids[0];
                lineupId = ids[1];
                if (cache != null) {
                    cache.edit().putString(source + ":user", userId).putString(source + ":lineup", lineupId).apply();
                }
                lineup = get(client, "https://www.titantv.com/api/channel/" + userId + "/" + lineupId);
                schedule = fetchSchedule(client, userId, lineupId);
            }
        }
        List<Program> programs = parsePrograms(channels, lineup, schedule);
        return new Result(programs, lineup.length() + schedule.length());
    }

    private static String fetchSchedule(OkHttpClient client, String userId, String lineupId) throws IOException {
        String token = ZonedDateTime.now(EASTERN).withMinute(0).withSecond(0).withNano(0).format(TOKEN);
        return get(client, "https://www.titantv.com/api/schedule/" + userId + "/" + lineupId + "/" + token + "/6");
    }

    private static String[] createLineup(OkHttpClient client, Provider provider, String source, String customChannelsJson) throws Exception {
        String guest = get(client, "https://www.titantv.com/api/user/guest");
        String userId = new JSONObject(guest).getString("userId");
        String lineupBody = "{\"lineupName\":\"" + provider.name + "\",\"utcOffset\":-300,\"observesDST\":true,\"providerId\":" + provider.id + ",\"deviceTypeCode\":\"" + provider.deviceType + "\"}";
        String lineupResult = put(client, "https://www.titantv.com/api/lineup/" + userId, lineupBody);
        String lineupId = new JSONObject(lineupResult).getString("lineupId");
        if ((CUSTOM.equals(source) || DIRECTV.equals(source)) && customChannelsJson != null) {
            JSONArray custom = new JSONArray(customChannelsJson);
            // Overall deadline + per-station failure isolation
            // (audit T1/T3): one bad station no longer aborts the
            // lineup, and the loop cannot run for minutes.
            long deadline = System.currentTimeMillis() + 150000L;
            for (int index = 0; index < custom.length(); index++) {
                if (System.currentTimeMillis() > deadline) {
                    android.util.Log.w("LiveTV-EPG", "TitanTV custom channel POSTs hit the deadline at " + index + "/" + custom.length());
                    break;
                }
                try {
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
                    body.put("lineupAction", "A");
                    body.put("errorCode", 0);
                    body.put("errorMessage", "");
                    post(client, "https://www.titantv.com/api/customization/channel/" + userId + "/" + lineupId, body.toString());
                } catch (Exception stationFailure) {
                    android.util.Log.w("LiveTV-EPG", "TitanTV custom channel " + index + " failed: " + stationFailure.getMessage());
                }
            }
        }
        return new String[]{userId, lineupId};
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
            // Audit T3: opt-accessors; one malformed station entry
            // must not abort the whole lineup parse.
            JSONObject station = lineup2.optJSONObject(index);
            if (station == null) {
                continue;
            }
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
                int channelIndexValue = station.optInt("channelIndex", -1);
                if (channelIndexValue >= 0 && (previous == null || score > previous.score)) {
                    bestByAppId.put(matched.id, new Choice(channelIndexValue, matched, score));
                }
            }
        }
        // Audit T5: several app channels can legitimately map to the
        // same TitanTV station; a plain map silently overwrote all
        // but one of them, leaving the rest with no programmes.
        Map<Integer, List<Channel>> appByTitanIndex = new HashMap<>();
        for (Choice choice : bestByAppId.values()) {
            List<Channel> bucket = appByTitanIndex.get(Integer.valueOf(choice.channelIndex));
            if (bucket == null) {
                bucket = new ArrayList<>();
                appByTitanIndex.put(Integer.valueOf(choice.channelIndex), bucket);
            }
            bucket.add(choice.channel);
        }
        List<Program> programs = new ArrayList<>();
        int eventsOk = 0;
        int eventsFailed = 0;
        Map<String, Instant> lastEndByChannel = new HashMap<>();
        JSONArray schedule2 = new JSONObject(scheduleJson).getJSONArray("channels");
        int channelIndex = 0;
        while (channelIndex < schedule2.length()) {
            JSONObject titanChannel = schedule2.optJSONObject(channelIndex);
            if (titanChannel == null) {
                channelIndex++;
                continue;
            }
            List<Channel> appChannels = appByTitanIndex.get(Integer.valueOf(titanChannel.optInt("channelIndex", -1)));
            if (appChannels != null && !appChannels.isEmpty() && (days = titanChannel.optJSONArray("days")) != null) {
                int dayIndex = 0;
                while (dayIndex < days.length()) {
                    Map<String, Channel> appByName2 = appByName;
                    JSONObject day = days.optJSONObject(dayIndex);
                    JSONArray events2 = day == null ? null : day.optJSONArray("events");
                    if (events2 != null) {
                        int eventIndex = 0;
                        while (eventIndex < events2.length()) {
                            JSONObject event = events2.optJSONObject(eventIndex);
                            events = events2;
                            schedule = schedule2;
                            lineup = lineup2;
                            if (event != null) {
                                // Audit T4/T5/T6: failures are counted
                                // (a mostly-failing parse fails the
                                // source instead of silently returning
                                // an empty guide); every app channel
                                // mapped to this station gets the
                                // programme; times resolve the DST
                                // fall-back hour against the previous
                                // programme instead of always taking
                                // the earlier offset.
                                try {
                                    LocalDateTime startLocal = LocalDateTime.parse(event.getString("startTime"));
                                    LocalDateTime endLocal = LocalDateTime.parse(event.getString("endTime"));
                                    for (Channel targetChannel : appChannels) {
                                        String programId = targetChannel.tvgId.isEmpty() ? targetChannel.name : targetChannel.tvgId;
                                        Instant floor = lastEndByChannel.get(targetChannel.id);
                                        Instant startInstant = easternInstant(startLocal, floor);
                                        Instant endInstant = easternInstant(endLocal, startInstant);
                                        programs.add(new Program(programId, targetChannel.name, event.optString("title"), event.optString("episodeTitle"), event.optString("description"), startInstant, endInstant, event.optString("displayGenre"), event.optString("tvRating")));
                                        lastEndByChannel.put(targetChannel.id, endInstant);
                                    }
                                    eventsOk++;
                                } catch (Exception eventFailure) {
                                    eventsFailed++;
                                }
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
        // Audit T4: surface parse failures. Above 50% the source is
        // broken, not sparse, and must fail loudly.
        if (eventsFailed > 0) {
            android.util.Log.w("LiveTV-EPG", "TitanTV programme parse failures: " + eventsFailed + " of " + (eventsOk + eventsFailed));
            if (eventsOk == 0 || eventsFailed * 2 > eventsOk + eventsFailed) {
                throw new Exception("TitanTV programme parse mostly failing (" + eventsFailed + "/" + (eventsOk + eventsFailed) + ")");
            }
        }
        return programs;
    }

    private static Instant easternInstant(LocalDateTime local, Instant notBefore) {
        // Audit T6: LocalDateTime.atZone(EASTERN) resolves the
        // repeated fall-back hour to the EARLIER offset, so the
        // second 1 AM hour landed an hour early (DST ends the day
        // after this audit). When both offsets are valid, take the
        // first candidate that does not precede the previous
        // programme on the channel.
        List<ZoneOffset> offsets = EASTERN.getRules().getValidOffsets(local);
        if (offsets.size() == 1) {
            return ZonedDateTime.ofLocal(local, EASTERN, offsets.get(0)).toInstant();
        }
        Instant earliest = null;
        for (ZoneOffset offset : offsets) {
            Instant candidate = ZonedDateTime.ofLocal(local, EASTERN, offset).toInstant();
            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
            if (notBefore != null && !candidate.isBefore(notBefore)) {
                return candidate;
            }
        }
        return earliest;
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
            // Audit T2: cap the body before building a JSONObject
            // tree over it (~10x heap for the schedule payload).
            if (response.body().contentLength() > MAX_BODY_BYTES) {
                throw new IOException("TitanTV body too large");
            }
            okio.BufferedSource bodySource = response.body().source();
            if (bodySource.request(MAX_BODY_BYTES + 1L)) {
                throw new IOException("TitanTV body too large");
            }
            String strString = bodySource.readUtf8();
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
                return new Provider("DirecTV National", 10000486, "S");
            }
            if (TitanTvSource.SPECTRUM.equals(source)) {
                return new Provider("Spectrum Manhattan", 92600, "D");
            }
            if (TitanTvSource.SLING.equals(source)) {
                return new Provider("Sling TV National", 10003640, "O");
            }
            if (TitanTvSource.CUSTOM.equals(source)) {
                return new Provider("Live TV Complete", 10000486, "S");
            }
            throw new IllegalArgumentException("Unsupported TitanTV source");
        }
    }
}
