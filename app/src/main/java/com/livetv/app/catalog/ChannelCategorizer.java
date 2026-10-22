package com.livetv.app.catalog;

import com.livetv.app.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/* JADX INFO: loaded from: classes6.dex */
public final class ChannelCategorizer {
    private static final Pattern LOCAL_CALL_SIGN = Pattern.compile("(?i)\\b(?:[kw][a-z]{3}|[kw][a-z]{2}(?:[- ]?(?:tv|dt|fm|am)))\\b");
    private static final Pattern LOCAL_NETWORK_MARKET = Pattern.compile("(?i)\\b(?:abc|cbs|nbc|fox|cw)\\s+(?:ny|new york|la|los angeles|chicago|boston|philadelphia|atlanta|dallas|houston|miami|denver|seattle|detroit|phoenix|san francisco)\\b");
    private static final List<ChannelCategory> CATEGORIES = Collections.unmodifiableList(asCategoryList());
    private static final Comparator<Channel> CHANNEL_NAME_ORDER = new Comparator<Channel>() { // from class: com.livetv.app.catalog.ChannelCategorizer.1
        @Override // java.util.Comparator
        public int compare(Channel first, Channel second) {
            int byName = ChannelCategorizer.compareNames(first.name, second.name);
            if (byName != 0) {
                return byName;
            }
            return ChannelCategorizer.compareNames(first.id, second.id);
        }
    };

    public ChannelCategory categoryFor(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        String group = normalize(channel.group);
        ChannelCategory grouped = categoryFromGroup(group);
        if (grouped != null) {
            return grouped;
        }
        ChannelCategory metadata = categoryFromMetadata(normalize(channel.name + " " + channel.tvgId));
        if (metadata != null) {
            return metadata;
        }
        if (containsAny(group, "canada channels", "uk channels", "international")) {
            return ChannelCategory.INTERNATIONAL;
        }
        return ChannelCategory.ENTERTAINMENT;
    }

    public Projections project(List<Channel> channels, Set<String> favoriteChannelIds) {
        return project(channels, favoriteChannelIds, Collections.emptySet());
    }

    public Projections project(List<Channel> channels, Set<String> favoriteChannelIds, Set<String> hiddenChannelIds) {
        EnumMap<ChannelCategory, List<Channel>> projected = new EnumMap<>(ChannelCategory.class);
        for (ChannelCategory category : ChannelCategory.values()) {
            projected.put(category, new ArrayList<>());
        }
        if (channels != null) {
            for (Channel channel : channels) {
                if (channel != null) {
                    Channel categorized = channel.withCategory(categoryFor(channel));
                    if (hiddenChannelIds != null && hiddenChannelIds.contains(categorized.id)) {
                        projected.get(ChannelCategory.HIDDEN).add(categorized);
                    } else {
                        projected.get(ChannelCategory.ALL).add(categorized);
                        projected.get(categorized.category).add(categorized);
                        if (favoriteChannelIds != null && favoriteChannelIds.contains(categorized.id)) {
                            projected.get(ChannelCategory.FAVORITES).add(categorized);
                        }
                    }
                }
            }
        }
        for (List<Channel> projection : projected.values()) {
            Collections.sort(projection, CHANNEL_NAME_ORDER);
        }
        return new Projections(projected);
    }

    private static ChannelCategory categoryFromGroup(String group) {
        if (!containsAny(group, "international", "world", "latin", "spanish", "espanol", "french", "france", "arabic", "india", "italian", "german", "germany", "portuguese", "brazil", "turkish", "filipino", "vietnamese", "korean", "japanese", "chinese", "russian", "polish", "greek", "africa", "caribbean")) {
            if (!containsAny(group, "local", "regional", "affiliate")) {
                if (!containsAny(group, "sports", "sport", "nfl", "nba", "mlb", "nhl")) {
                    if (!containsAny(group, "news")) {
                        if (!containsAny(group, "movies", "movie", "cinema", "film")) {
                            if (!containsAny(group, "kids", "children", "animation", "cartoon")) {
                                if (!containsAny(group, "comedy")) {
                                    if (!containsAny(group, "true crime", "crime", "investigation")) {
                                        if (!containsAny(group, "documentary", "history", "docs", "nature")) {
                                            if (!containsAny(group, "reality")) {
                                                if (!containsAny(group, "lifestyle", "food", "travel", "home")) {
                                                    if (containsAny(group, "music", "radio")) {
                                                        return ChannelCategory.MUSIC;
                                                    }
                                                    return null;
                                                }
                                                return ChannelCategory.LIFESTYLE;
                                            }
                                            return ChannelCategory.REALITY;
                                        }
                                        return ChannelCategory.DOCUMENTARY_HISTORY;
                                    }
                                    return ChannelCategory.TRUE_CRIME;
                                }
                                return ChannelCategory.COMEDY;
                            }
                            return ChannelCategory.KIDS;
                        }
                        return ChannelCategory.MOVIES;
                    }
                    return ChannelCategory.NEWS;
                }
                return ChannelCategory.SPORTS;
            }
            return ChannelCategory.LOCAL;
        }
        return ChannelCategory.INTERNATIONAL;
    }

    private static ChannelCategory categoryFromMetadata(String metadata) {
        if (!LOCAL_CALL_SIGN.matcher(metadata).find() && !LOCAL_NETWORK_MARKET.matcher(metadata).find() && !containsAny(metadata, "local station")) {
            if (!containsAny(metadata, "espn", "sports", "sport", "nfl", "nba", "mlb", "nhl", "soccer", "football", "basketball", "baseball", "hockey", "golf", "tennis", "ufc", "wrestling", "racing", "fight", "formula 1", "big ten")) {
                if (!containsAny(metadata, "cnn", "msnbc", "news", "weather", "cspan")) {
                    if (!containsAny(metadata, "movie", "cinema", "film", "hbo", "showtime", "starz")) {
                        if (!containsAny(metadata, "kids", "kid", "cartoon", "disney junior", "nickelodeon", "boomerang", "pbs kids")) {
                            if (!containsAny(metadata, "comedy", "comedy central", "laugh", "stand up")) {
                                if (!containsAny(metadata, "true crime", "crime", "investigation discovery", "court tv", "oxygen")) {
                                    if (!containsAny(metadata, "history", "discovery", "american heroes", "smithsonian", "animal planet", "national geographic", "documentary")) {
                                        if (!containsAny(metadata, "reality", "bravo", "tlc", "e entertainment")) {
                                            if (!containsAny(metadata, "food", "travel", "home", "hgtv", "lifestyle")) {
                                                if (!containsAny(metadata, "music", "mtv", "vh1", "radio")) {
                                                    if (containsAny(metadata, "international", "latin", "spanish", "espanol", "french", "arabic", "hindi", "italian", "german", "portuguese", "korean", "japanese", "chinese", "russian", "turkish")) {
                                                        return ChannelCategory.INTERNATIONAL;
                                                    }
                                                    return null;
                                                }
                                                return ChannelCategory.MUSIC;
                                            }
                                            return ChannelCategory.LIFESTYLE;
                                        }
                                        return ChannelCategory.REALITY;
                                    }
                                    return ChannelCategory.DOCUMENTARY_HISTORY;
                                }
                                return ChannelCategory.TRUE_CRIME;
                            }
                            return ChannelCategory.COMEDY;
                        }
                        return ChannelCategory.KIDS;
                    }
                    return ChannelCategory.MOVIES;
                }
                return ChannelCategory.NEWS;
            }
            return ChannelCategory.SPORTS;
        }
        return ChannelCategory.LOCAL;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(" " + value + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return " " + source.replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static List<ChannelCategory> asCategoryList() {
        List<ChannelCategory> categories = new ArrayList<>();
        Collections.addAll(categories, ChannelCategory.values());
        return categories;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int compareNames(String first, String second) {
        String left = first == null ? "" : first;
        String right = second != null ? second : "";
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < leftIndex && rightIndex < right.length()) {
            char leftCharacter = left.charAt(leftIndex);
            char rightCharacter = right.charAt(rightIndex);
            if (Character.isDigit(leftCharacter) && Character.isDigit(rightCharacter)) {
                int leftEnd = numberEnd(left, leftIndex);
                int rightEnd = numberEnd(right, rightIndex);
                int numberComparison = compareNumbers(left, leftIndex, leftEnd, right, rightIndex, rightEnd);
                if (numberComparison != 0) {
                    return numberComparison;
                }
                leftIndex = leftEnd;
                rightIndex = rightEnd;
            } else {
                char leftLower = Character.toLowerCase(leftCharacter);
                char rightLower = Character.toLowerCase(rightCharacter);
                if (leftLower != rightLower) {
                    return leftLower < rightLower ? -1 : 1;
                }
                leftIndex++;
                rightIndex++;
            }
        }
        if (leftIndex == left.length() && rightIndex == right.length()) {
            return left.compareTo(right);
        }
        return leftIndex == left.length() ? -1 : 1;
    }

    private static int numberEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int compareNumbers(String left, int leftStart, int leftEnd, String right, int rightStart, int rightEnd) {
        int leftSignificant = skipLeadingZeros(left, leftStart, leftEnd);
        int rightSignificant = skipLeadingZeros(right, rightStart, rightEnd);
        int leftLength = leftEnd - leftSignificant;
        int rightLength = rightEnd - rightSignificant;
        if (leftLength != rightLength) {
            return leftLength < rightLength ? -1 : 1;
        }
        for (int index = 0; index < leftLength; index++) {
            char leftDigit = left.charAt(leftSignificant + index);
            char rightDigit = right.charAt(rightSignificant + index);
            if (leftDigit != rightDigit) {
                return leftDigit < rightDigit ? -1 : 1;
            }
        }
        int index2 = leftEnd - leftStart;
        int rightOriginalLength = rightEnd - rightStart;
        if (index2 == rightOriginalLength) {
            return 0;
        }
        return index2 < rightOriginalLength ? -1 : 1;
    }

    private static int skipLeadingZeros(String value, int start, int end) {
        int index = start;
        while (index < end - 1 && value.charAt(index) == '0') {
            index++;
        }
        return index;
    }

    public static final class Projections {
        private final Map<ChannelCategory, List<Channel>> channels;

        private Projections(Map<ChannelCategory, List<Channel>> channels) {
            EnumMap<ChannelCategory, List<Channel>> copy = new EnumMap<>(ChannelCategory.class);
            for (ChannelCategory category : ChannelCategory.values()) {
                copy.put(category, Collections.unmodifiableList(new ArrayList(channels.get(category))));
            }
            this.channels = Collections.unmodifiableMap(copy);
        }

        public List<ChannelCategory> categories() {
            return ChannelCategorizer.CATEGORIES;
        }

        public List<Channel> channelsFor(ChannelCategory category) {
            if (category == null) {
                throw new IllegalArgumentException("category is required");
            }
            return this.channels.get(category);
        }

        public List<ChannelCategory> railCategories() {
            List<ChannelCategory> rail = new ArrayList<>();
            if (!channelsFor(ChannelCategory.FAVORITES).isEmpty()) {
                rail.add(ChannelCategory.FAVORITES);
            }
            rail.add(ChannelCategory.ALL);
            for (ChannelCategory category : ChannelCategorizer.CATEGORIES) {
                if (category != ChannelCategory.ALL && category != ChannelCategory.FAVORITES && category != ChannelCategory.HIDDEN && !channelsFor(category).isEmpty()) {
                    rail.add(category);
                }
            }
            if (!channelsFor(ChannelCategory.HIDDEN).isEmpty()) {
                rail.add(ChannelCategory.HIDDEN);
            }
            return Collections.unmodifiableList(rail);
        }
    }
}
