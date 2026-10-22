package com.livetv.app.catalog;

import com.livetv.app.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/* JADX INFO: loaded from: classes6.dex */
public final class ChannelClassifier {
    private static final Pattern PRIMARY_ENGLISH = Pattern.compile("\\b(english|usa|united states|uk|united kingdom|canada|australia|new zealand|ireland|bbc|cnn|fox news|discovery|history|animal planet|national geographic|espn|nba tv|nfl network|nhl network|mlb network|sky news)\\b");
    private static final Pattern EXPLICIT_ADULT = Pattern.compile("(?:\\b18\\+|\\bporn\\b|\\bxxx\\b|\\bsex\\b|\\bplayer-\\d+\\b)");
    private static final Pattern STANDALONE_ADULT = Pattern.compile("\\badult\\b");
    private static final Pattern ADULT_SWIM = Pattern.compile("\\badult\\s+swim\\b");

    public Result classify(List<Channel> parsed) {
        LinkedHashMap<String, Channel> unique = new LinkedHashMap<>();
        int adult = 0;
        int duplicates = 0;
        for (Channel channel : parsed) {
            if (isAdult(channel)) {
                adult++;
            } else {
                String key = channel.normalizedIdentity() + "|" + channel.id;
                Channel ranked = channel.withRank(rank(channel));
                if (unique.containsKey(key)) {
                    duplicates++;
                } else {
                    unique.put(key, ranked);
                }
            }
        }
        List<Channel> visible = new ArrayList<>(unique.values());
        Collections.sort(visible, new Comparator<Channel>() { // from class: com.livetv.app.catalog.ChannelClassifier.1
            @Override // java.util.Comparator
            public int compare(Channel first, Channel second) {
                if (first.languageRank != second.languageRank) {
                    return first.languageRank < second.languageRank ? -1 : 1;
                }
                int groupComparison = String.CASE_INSENSITIVE_ORDER.compare(first.group, second.group);
                if (groupComparison != 0) {
                    return groupComparison;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name);
            }
        });
        return new Result(visible, new CatalogReport(parsed.size(), adult, duplicates, visible.size(), countMissingLogos(visible), countMissingEpgIds(visible)));
    }

    private static boolean isAdult(Channel channel) {
        String metadata = metadata(channel);
        if (EXPLICIT_ADULT.matcher(metadata).find()) {
            return true;
        }
        return STANDALONE_ADULT.matcher(metadata).find() && !ADULT_SWIM.matcher(metadata).find();
    }

    private static int rank(Channel channel) {
        return !PRIMARY_ENGLISH.matcher(metadata(channel)).find() ? 1 : 0;
    }

    private static String metadata(Channel channel) {
        return (channel.name + " " + channel.group).toLowerCase(Locale.ROOT);
    }

    private static int countMissingLogos(List<Channel> channels) {
        int missing = 0;
        for (Channel channel : channels) {
            if (!channel.hasOfficialLogo()) {
                missing++;
            }
        }
        return missing;
    }

    private static int countMissingEpgIds(List<Channel> channels) {
        int missing = 0;
        for (Channel channel : channels) {
            if (channel.tvgId.isEmpty()) {
                missing++;
            }
        }
        return missing;
    }

    public static final class Result {
        public final List<Channel> channels;
        public final CatalogReport report;

        public Result(List<Channel> channels, CatalogReport report) {
            this.channels = channels;
            this.report = report;
        }
    }
}
