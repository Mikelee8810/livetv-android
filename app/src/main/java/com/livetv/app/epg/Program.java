package com.livetv.app.epg;

import java.time.Instant;

/* JADX INFO: loaded from: classes3.dex */
public final class Program {
    public final String category;
    public final String channelDisplayName;
    public final String channelId;
    public final String description;
    public final Instant end;
    public final String rating;
    public final Instant start;
    public final String subtitle;
    public final String title;

    public Program(String channelId, String title, String subtitle, String description, Instant start, Instant end, String category, String rating) {
        this(channelId, null, title, subtitle, description, start, end, category, rating);
    }

    public Program(String channelId, String channelDisplayName, String title, String subtitle, String description, Instant start, Instant end, String category, String rating) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("program start and end are required");
        }
        if (end.toEpochMilli() <= start.toEpochMilli()) {
            throw new IllegalArgumentException("program end must be after start");
        }
        this.channelId = channelId == null ? "" : channelId;
        this.channelDisplayName = channelDisplayName == null ? "" : channelDisplayName;
        this.title = title != null ? title : "";
        this.subtitle = subtitle;
        this.description = description;
        this.start = start;
        this.end = end;
        this.category = category;
        this.rating = rating;
    }
}
