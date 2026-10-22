package com.livetv.app.catalog;

/* JADX INFO: loaded from: classes6.dex */
public enum ChannelCategory {
    FAVORITES("Favorites"),
    HIDDEN("Hidden Channels"),
    ALL("All Channels"),
    ENTERTAINMENT("Entertainment"),
    SPORTS("Sports"),
    NEWS("News & Opinion"),
    MOVIES("Movies"),
    COMEDY("Comedy"),
    LIFESTYLE("Lifestyle"),
    REALITY("Reality"),
    TRUE_CRIME("True Crime"),
    DOCUMENTARY_HISTORY("Docs & History"),
    KIDS("Kids & Family"),
    MUSIC("Music"),
    LOCAL("Local"),
    INTERNATIONAL("International");

    public final String label;

    ChannelCategory(String label) {
        this.label = label;
    }
}
