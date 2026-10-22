package com.livetv.app.epg;

import com.livetv.app.Channel;
import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/* JADX INFO: loaded from: classes3.dex */
public final class EpgSnapshot {
    private static final Set<String> NOISE = Collections.unmodifiableSet(new HashSet(Arrays.asList("the", "usa", "us", "tv", "television", "channel", "network", "hd", "fhd", "east", "eastern", "west", "western", "feed")));
    private static final Comparator<Program> PROGRAM_ORDER = new Comparator() { // from class: com.livetv.app.epg.EpgSnapshot$$ExternalSyntheticLambda0
        @Override // java.util.Comparator
        public final int compare(Object obj, Object obj2) {
            return EpgSnapshot.lambda$static$0((Program) obj, (Program) obj2);
        }
    };
    private final List<Channel> channels;
    private final Map<String, List<Program>> programsByChannelId;

    /* JADX INFO: Access modifiers changed from: private */
    interface ChannelKey {
        String get(Channel channel);
    }

    static /* synthetic */ int lambda$static$0(Program first, Program second) {
        int startOrder = Long.compare(first.start.toEpochMilli(), second.start.toEpochMilli());
        return startOrder != 0 ? startOrder : Long.compare(first.end.toEpochMilli(), second.end.toEpochMilli());
    }

    private EpgSnapshot(List<Channel> channels, Map<String, List<Program>> programsByChannelId) {
        this.channels = Collections.unmodifiableList(new ArrayList(channels));
        Map<String, List<Program>> immutablePrograms = new HashMap<>();
        for (Map.Entry<String, List<Program>> entry : programsByChannelId.entrySet()) {
            List<Program> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted, PROGRAM_ORDER);
            immutablePrograms.put(entry.getKey(), Collections.unmodifiableList(sorted));
        }
        this.programsByChannelId = Collections.unmodifiableMap(immutablePrograms);
    }

    public static EpgSnapshot map(List<Channel> channels, List<Program> programs, Map<String, String> aliases) {
        Channel channel;
        List<Channel> snapshotChannels = channels == null ? Collections.emptyList() : new ArrayList<>(channels);
        List<Program> sourcePrograms = programs == null ? Collections.emptyList() : programs;
        MappingIndex index = new MappingIndex(snapshotChannels, sourcePrograms);
        Map<String, List<Program>> mapped = new HashMap<>();
        for (Program program : sourcePrograms) {
            if (program != null && (channel = index.match(program, aliases)) != null) {
                mapped.computeIfAbsent(channel.id, new Function() { // from class: com.livetv.app.epg.EpgSnapshot$$ExternalSyntheticLambda2
                    @Override // java.util.function.Function
                    public final Object apply(Object obj) {
                        return EpgSnapshot.lambda$map$1((String) obj);
                    }
                }).add(program);
            }
        }
        return new EpgSnapshot(snapshotChannels, mapped);
    }

    static /* synthetic */ List lambda$map$1(String ignored) {
        return new ArrayList();
    }

    public static EpgSnapshot mergePreferExisting(List<Channel> channels, List<EpgSnapshot> snapshots) {
        List<Channel> resultChannels = channels == null ? Collections.emptyList() : new ArrayList<>(channels);
        Map<String, List<Program>> merged = new HashMap<>();
        if (snapshots != null) {
            for (EpgSnapshot snapshot : snapshots) {
                if (snapshot != null) {
                    for (Channel channel : resultChannels) {
                        List<Program> programs = snapshot.programsFor(channel);
                        if (!programs.isEmpty()) {
                            List<Program> accepted = merged.computeIfAbsent(channel.id, new Function() { // from class: com.livetv.app.epg.EpgSnapshot$$ExternalSyntheticLambda1
                                @Override // java.util.function.Function
                                public final Object apply(Object obj) {
                                    return EpgSnapshot.lambda$mergePreferExisting$2((String) obj);
                                }
                            });
                            for (Program candidate : programs) {
                                if (!overlapsAny(candidate, accepted)) {
                                    accepted.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
        }
        return new EpgSnapshot(resultChannels, merged);
    }

    static /* synthetic */ List lambda$mergePreferExisting$2(String ignored) {
        return new ArrayList();
    }

    private static boolean overlapsAny(Program candidate, List<Program> accepted) {
        for (Program existing : accepted) {
            if (candidate.start.isBefore(existing.end) && candidate.end.isAfter(existing.start)) {
                return true;
            }
        }
        return false;
    }

    public List<Channel> channels() {
        return this.channels;
    }

    public List<Program> programsFor(Channel channel) {
        List<Program> programs;
        if (channel != null && (programs = this.programsByChannelId.get(channel.scheduleChannelId())) != null) {
            return programs;
        }
        return Collections.emptyList();
    }

    public Program currentProgram(Channel channel, Instant now) {
        if (now == null) {
            return null;
        }
        long nowMillis = now.toEpochMilli();
        for (Program program : programsFor(channel)) {
            if (nowMillis >= program.start.toEpochMilli() && nowMillis < program.end.toEpochMilli()) {
                return program;
            }
        }
        return null;
    }

    public Diagnostics diagnostics(Instant now) {
        long nowMillis = now == null ? Long.MIN_VALUE : now.toEpochMilli();
        int mappedChannels = 0;
        int mappedPrograms = 0;
        int currentFuturePrograms = 0;
        for (Channel channel : this.channels) {
            List<Program> programs = programsFor(channel);
            if (!programs.isEmpty()) {
                mappedChannels++;
            }
            mappedPrograms += programs.size();
            for (Program program : programs) {
                if (program.end.toEpochMilli() > nowMillis) {
                    currentFuturePrograms++;
                }
            }
        }
        return new Diagnostics(mappedChannels, this.channels.size() - mappedChannels, mappedPrograms, currentFuturePrograms);
    }

    public static final class Diagnostics {
        public final int currentFuturePrograms;
        public final int mappedChannels;
        public final int mappedPrograms;
        public final int unmatchedChannels;

        Diagnostics(int mappedChannels, int unmatchedChannels, int mappedPrograms, int currentFuturePrograms) {
            this.mappedChannels = mappedChannels;
            this.unmatchedChannels = unmatchedChannels;
            this.mappedPrograms = mappedPrograms;
            this.currentFuturePrograms = currentFuturePrograms;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class MappingIndex {
        private final Map<String, Channel> exactIds;
        private final Map<String, Channel> exactNames;
        private final Map<String, Channel> normalizedNames;
        private final Set<String> uniqueSourceNames;

        MappingIndex(List<Channel> channels, List<Program> programs) {
            this.exactIds = EpgSnapshot.uniqueIndex(channels, new ChannelKey() { // from class: com.livetv.app.epg.EpgSnapshot$MappingIndex$$ExternalSyntheticLambda0
                @Override // com.livetv.app.epg.EpgSnapshot.ChannelKey
                public final String get(Channel channel) {
                    return channel.tvgId;
                }
            });
            this.exactNames = EpgSnapshot.uniqueIndex(channels, new ChannelKey() { // from class: com.livetv.app.epg.EpgSnapshot$MappingIndex$$ExternalSyntheticLambda1
                @Override // com.livetv.app.epg.EpgSnapshot.ChannelKey
                public final String get(Channel channel) {
                    return channel.name;
                }
            });
            this.normalizedNames = EpgSnapshot.uniqueIndex(channels, new ChannelKey() { // from class: com.livetv.app.epg.EpgSnapshot$MappingIndex$$ExternalSyntheticLambda2
                @Override // com.livetv.app.epg.EpgSnapshot.ChannelKey
                public final String get(Channel channel) {
                    return EpgSnapshot.canonical(channel.name);
                }
            });
            Map<String, Set<String>> sourceIds = new HashMap<>();
            for (Program program : programs) {
                if (program != null && !program.channelDisplayName.isEmpty()) {
                    String key = EpgSnapshot.canonical(program.channelDisplayName);
                    if (!key.isEmpty()) {
                        sourceIds.computeIfAbsent(key, new Function() { // from class: com.livetv.app.epg.EpgSnapshot$MappingIndex$$ExternalSyntheticLambda3
                            @Override // java.util.function.Function
                            public final Object apply(Object obj) {
                                return EpgSnapshot.MappingIndex.lambda$new$3((String) obj);
                            }
                        }).add(program.channelId);
                    }
                }
            }
            Set<String> unique = new HashSet<>();
            for (Map.Entry<String, Set<String>> entry : sourceIds.entrySet()) {
                if (entry.getValue().size() == 1) {
                    unique.add(entry.getKey());
                }
            }
            this.uniqueSourceNames = Collections.unmodifiableSet(unique);
        }

        static /* synthetic */ Set lambda$new$3(String ignored) {
            return new HashSet();
        }

        Channel match(Program program, Map<String, String> aliases) {
            String alias;
            Channel channel = this.exactIds.get(program.channelId);
            String displayName = program.channelDisplayName.isEmpty() ? program.channelId : program.channelDisplayName;
            if (channel == null) {
                channel = this.exactNames.get(displayName);
            }
            if (channel == null && aliases != null && (alias = aliases.get(program.channelId)) != null && (channel = this.exactIds.get(alias)) == null) {
                channel = this.exactNames.get(alias);
            }
            if (channel == null) {
                String normalized = EpgSnapshot.canonical(displayName);
                return this.uniqueSourceNames.contains(normalized) ? this.normalizedNames.get(normalized) : channel;
            }
            return channel;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Map<String, Channel> uniqueIndex(List<Channel> channels, ChannelKey key) {
        String value;
        Channel existing;
        Map<String, Channel> values = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Channel channel : channels) {
            if (channel != null && (value = key.get(channel)) != null && !value.isEmpty() && !ambiguous.contains(value) && (existing = values.putIfAbsent(value, channel)) != null && (existing.isYoutubeVariant() || !channel.isYoutubeVariant())) {
                if (existing.isYoutubeVariant() && !channel.isYoutubeVariant()) {
                    values.put(value, channel);
                } else {
                    values.remove(value);
                    ambiguous.add(value);
                }
            }
        }
        return values;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String canonical(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace("&", " and ");
        StringBuilder result = new StringBuilder();
        for (String token : ascii.split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !NOISE.contains(token)) {
                result.append(token);
            }
        }
        return result.toString();
    }
}
