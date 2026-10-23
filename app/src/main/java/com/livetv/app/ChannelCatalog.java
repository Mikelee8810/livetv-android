package com.livetv.app;

import android.content.Context;
import com.livetv.app.catalog.CatalogReport;
import com.livetv.app.catalog.ChannelClassifier;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* JADX INFO: loaded from: classes8.dex */
public final class ChannelCatalog {
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern ID = Pattern.compile("[?&]id=([^&\\s]+)");

    private ChannelCatalog() {
    }

    public static List<Channel> load(Context context) throws Exception {
        return loadWithReport(context).channels;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code duplicated, block: B:15:0x0068  */
    public static LoadResult loadWithReport(Context context) throws Exception {
        List<Channel> parsed = new ArrayList<>();
        List<String> epgSources = Collections.emptyList();
        BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("channels.m3u")));
        String name = null;
        String group = "Live TV";
        String logo = "";
        String tvgId = "";
        int feedCount = 7;
        while (true) {
            try {
                String line = reader.readLine();
                if (line != null) {
                    String line2 = line.trim();
                    if (line2.startsWith("#EXTM3U")) {
                        epgSources = parseEpgSources(line2);
                    } else if (line2.startsWith("#EXTINF:")) {
                        Matcher attributes = ATTR.matcher(line2);
                        while (attributes.find()) {
                            byte b = 1;
                            String strGroup = attributes.group(1);
                            switch (strGroup.hashCode()) {
                                case -864412640:
                                    b = !strGroup.equals("feed-count") ? (byte) -1 : (byte) 3;
                                    break;
                                case -861895229:
                                    if (strGroup.equals("tvg-id")) {
                                        b = 2;
                                    }
                                    break;
                                case -328855158:
                                    if (!strGroup.equals("group-title")) {
                                    }
                                    break;
                                case 647476307:
                                    if (strGroup.equals("tvg-logo")) {
                                        b = 0;
                                    }
                                    break;
                                default:
                                    break;
                            }
                            switch (b) {
                                case 0:
                                    logo = attributes.group(2);
                                    break;
                                case 1:
                                    group = attributes.group(2);
                                    break;
                                case 2:
                                    tvgId = attributes.group(2);
                                    break;
                                case 3:
                                    feedCount = parsePositiveInt(attributes.group(2), 7);
                                    break;
                            }
                        }
                        int comma = line2.lastIndexOf(44);
                        name = comma >= 0 ? line2.substring(comma + 1).trim() : "Channel";
                    } else if (name == null || line2.isEmpty() || line2.startsWith("#")) {
                        name = name;
                    } else {
                        String id = parseChannelId(line2);
                        if (id != null) {
                            String playbackPath = line2.contains("sources=") ? line2 : null;
                            parsed.add(new Channel(id, tvgId, name, group, logo, playbackPath, feedCount));
                        }
                        name = null;
                        group = "Live TV";
                        logo = "";
                        tvgId = "";
                        feedCount = 7;
                    }
                } else {
                    reader.close();
                    ChannelClassifier.Result classified = new ChannelClassifier().classify(parsed);
                    return new LoadResult(classified.channels, classified.report, epgSources);
                }
            } catch (Throwable th) {
                try {
                    reader.close();
                    throw th;
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                    throw th;
                }
            }
        }
    }

    static List<Channel> expandYoutubeVariants(List<Channel> channels) {
        List<Channel> expanded = new ArrayList<>();
        if (channels == null) {
            return expanded;
        }
        for (Channel channel : channels) {
            List<String> youtube = new ArrayList<>();
            List<String> standard = new ArrayList<>();
            for (String source : channel.playbackSources()) {
                (Channel.isYouTubeSource(source) ? youtube : standard).add(source);
            }
            if (youtube.isEmpty() || standard.isEmpty()) {
                expanded.add(channel);
            } else {
                expanded.add(new Channel(channel.id, channel.tvgId, channel.name, channel.group, channel.logo, playbackPath(channel.id, standard), standard.size()));
                String youtubeId = channel.id + "-youtube";
                expanded.add(new Channel(youtubeId, channel.tvgId, channel.name + "  ▶  YouTube", channel.group, channel.logo, playbackPath(youtubeId, Collections.singletonList(youtube.get(0))), 1));
            }
        }
        return expanded;
    }

    private static String playbackPath(String id, List<String> sources) {
        try {
            return "http://127.0.0.1:48913/live/manifest.m3u8?id=" + id + "&sources=" + URLEncoder.encode(String.join("|", sources), StandardCharsets.UTF_8.name());
        } catch (Exception impossible) {
            throw new IllegalStateException("UTF-8 encoding unavailable", impossible);
        }
    }

    static String parseChannelId(String line) {
        if (line == null) {
            return null;
        }
        Matcher id = ID.matcher(line);
        if (id.find()) {
            return id.group(1);
        }
        return null;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static List<String> parseEpgSources(String header) {
        if (header == null) {
            return Collections.emptyList();
        }
        Matcher attributes = ATTR.matcher(header);
        while (attributes.find()) {
            String key = attributes.group(1);
            if ("url-tvg".equals(key) || "x-tvg-url".equals(key)) {
                List<String> sources = new ArrayList<>();
                for (String raw : attributes.group(2).split(",")) {
                    String source = raw.trim();
                    if (!source.isEmpty() && isRelevantEpgSource(source)) {
                        sources.add(source);
                    }
                }
                return Collections.unmodifiableList(sources);
            }
        }
        return Collections.emptyList();
    }

    private static boolean isRelevantEpgSource(String source) {
        String value = source.toLowerCase(Locale.ROOT);
        if (value.contains("epgshare01.online")) {
            return value.contains("_us") || value.contains("_ca") || value.contains("_uk") || value.contains("_au") || value.contains("_ie") || value.contains("_fanduel") || value.contains("_audacy");
        }
        if (value.contains("raw.githubusercontent.com/globetvapp/epg/")) {
            return value.contains("/canada/") || value.contains("/ireland/") || value.contains("/unitedkingdom/");
        }
        return true;
    }

    public static final class LoadResult {
        public final List<Channel> channels;
        public final List<String> epgSources;
        public final CatalogReport report;

        public LoadResult(List<Channel> channels, CatalogReport report, List<String> epgSources) {
            this.channels = channels;
            this.report = report;
            this.epgSources = epgSources == null ? Collections.emptyList() : epgSources;
        }
    }
}
