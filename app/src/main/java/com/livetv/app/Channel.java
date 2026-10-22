package com.livetv.app;

import com.livetv.app.catalog.ChannelCategory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes8.dex */
public final class Channel {
    public final ChannelCategory category;
    private final int feedCount;
    public final String group;
    public final String id;
    public final int languageRank;
    public final String logo;
    public final String name;
    private final String playbackPath;
    public final String tvgId;

    public Channel(String id, String tvgId, String name, String group, String logo) {
        this(id, tvgId, name, group, logo, null, 7);
    }

    public Channel(String id, String tvgId, String name, String group, String logo, String playbackPath, int feedCount) {
        this(id, tvgId, name, group, logo, Integer.MAX_VALUE, ChannelCategory.ENTERTAINMENT, playbackPath, feedCount);
    }

    private Channel(String id, String tvgId, String name, String group, String logo, int languageRank, ChannelCategory category, String playbackPath, int feedCount) {
        this.id = id;
        this.tvgId = tvgId == null ? "" : tvgId;
        this.name = name == null ? "" : name;
        this.group = group == null ? "Live TV" : group;
        this.logo = logo != null ? logo : "";
        this.languageRank = languageRank;
        this.category = category == null ? ChannelCategory.ENTERTAINMENT : category;
        this.playbackPath = playbackPath;
        this.feedCount = Math.max(1, feedCount);
    }

    public String playbackUrl() {
        if (this.playbackPath == null || this.playbackPath.isEmpty()) {
            return "http://127.0.0.1:48913/live/manifest.m3u8?id=" + this.id;
        }
        return this.playbackPath;
    }

    public int feedCount() {
        return this.feedCount;
    }

    public List<String> playbackSources() {
        int start;
        if (this.playbackPath != null && (start = this.playbackPath.indexOf("sources=")) >= 0) {
            String packed = this.playbackPath.substring(start + 8).split("&", 2)[0];
            try {
                String decoded = URLDecoder.decode(packed, StandardCharsets.UTF_8.name());
                if (decoded.isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> ordered = new ArrayList<>(Arrays.asList(decoded.split("\\|")));
                Collections.sort(ordered, new Comparator() { // from class: com.livetv.app.Channel$$ExternalSyntheticLambda0
                    @Override // java.util.Comparator
                    public final int compare(Object obj, Object obj2) {
                        return Boolean.compare(!Channel.isYouTubeSource((String) obj), !Channel.isYouTubeSource((String) obj2));
                    }
                });
                return ordered;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    public static boolean isYouTubeSource(String source) {
        String value = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return value.contains("youtube.com/") || value.contains("youtube-nocookie.com/") || value.contains("youtu.be/");
    }

    public boolean isYoutubeVariant() {
        return this.id.endsWith("-youtube");
    }

    public String scheduleChannelId() {
        return isYoutubeVariant() ? this.id.substring(0, this.id.length() - "-youtube".length()) : this.id;
    }

    public String sourceLabel() {
        return isYoutubeVariant() ? "YouTube Live" : this.category.label;
    }

    public Channel withRank(int rank) {
        return new Channel(this.id, this.tvgId, this.name, this.group, this.logo, rank, this.category, this.playbackPath, this.feedCount);
    }

    public Channel withCategory(ChannelCategory category) {
        return new Channel(this.id, this.tvgId, this.name, this.group, this.logo, this.languageRank, category, this.playbackPath, this.feedCount);
    }

    public String normalizedIdentity() {
        String identity = this.tvgId.isEmpty() ? this.name : this.tvgId;
        return identity.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    public boolean hasOfficialLogo() {
        return !this.logo.isEmpty();
    }
}
