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
    // Coast tokens (east/west/feed) removed (audit S3): stripping
    // them mapped "HBO West" onto the East channel, putting its
    // programmes three hours off.
    private static final Set<String> NOISE = Collections.unmodifiableSet(new HashSet(Arrays.asList("the", "usa", "us", "tv", "television", "channel", "network", "hd", "fhd")));
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
                // An aliased guide id can also feed a second channel: the
                // NY local feed (exact id) and the national network channel
                // (alias) share one schedule.
                String alias = aliases == null ? null : aliases.get(program.channelId);
                Channel extra = alias == null ? null : index.exactIds.get(alias.toLowerCase(Locale.ROOT));
                if (extra != null && !extra.id.equals(channel.id)) {
                    mapped.computeIfAbsent(extra.id, k -> new ArrayList<>()).add(program);
                }
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
        // Audit S5: once a source covers "now" for a channel, that
        // source owns the channel. Splicing fragments from several
        // sources produced guides that matched no real schedule.
        Instant now = Instant.now();
        Set<String> lockedChannels = new HashSet<>();
        if (snapshots != null) {
            for (EpgSnapshot snapshot : snapshots) {
                if (snapshot != null) {
                    for (Channel channel : resultChannels) {
                        if (lockedChannels.contains(channel.id)) {
                            continue;
                        }
                        List<Program> programs = snapshot.programsFor(channel);
                        if (!programs.isEmpty()) {
                            List<Program> accepted = merged.computeIfAbsent(channel.id, new Function() { // from class: com.livetv.app.epg.EpgSnapshot$$ExternalSyntheticLambda1
                                @Override // java.util.function.Function
                                public final Object apply(Object obj) {
                                    return EpgSnapshot.lambda$mergePreferExisting$2((String) obj);
                                }
                            });
                            for (Program candidate : programs) {
                                insertIfNoOverlap(candidate, accepted);
                            }
                            for (Program acceptedProgram : accepted) {
                                if (!now.isBefore(acceptedProgram.start) && now.isBefore(acceptedProgram.end)) {
                                    lockedChannels.add(channel.id);
                                    break;
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

    private static boolean insertIfNoOverlap(Program candidate, List<Program> accepted) {
        // Audit S1: `accepted` is kept sorted by PROGRAM_ORDER via
        // insertion here, so only the two neighbours of the insertion
        // point can overlap the candidate. The old linear scan over
        // an ever-growing list made snapshot() quadratic per channel.
        int idx = Collections.binarySearch(accepted, candidate, PROGRAM_ORDER);
        int ins = idx >= 0 ? idx : -idx - 1;
        if (ins > 0) {
            Program prev = accepted.get(ins - 1);
            if (candidate.start.isBefore(prev.end) && candidate.end.isAfter(prev.start)) {
                return false;
            }
        }
        if (ins < accepted.size()) {
            Program next = accepted.get(ins);
            if (candidate.start.isBefore(next.end) && candidate.end.isAfter(next.start)) {
                return false;
            }
        }
        accepted.add(ins, candidate);
        return true;
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
        // One line per 40 unmatched channels (group | name | tvg-id), so
        // guide gaps can be fixed from what the device actually sees.
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Channel channel : this.channels) {
            if (programsFor(channel).isEmpty()) {
                sb.append(channel.group).append('|').append(channel.name).append('|').append(channel.tvgId).append(" ;; ");
                if (++n % 40 == 0) { android.util.Log.i("LiveTV-EPG-Gaps", sb.toString()); sb.setLength(0); }
            }
        }
        if (sb.length() > 0) android.util.Log.i("LiveTV-EPG-Gaps", sb.toString());
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
                    // Audit S2: index keys are lowercased so id
                    // matching is case-insensitive end to end (the
                    // repository filter already lowercased its side).
                    return channel.tvgId == null ? null : channel.tvgId.toLowerCase(Locale.ROOT);
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
            Channel channel = this.exactIds.get(program.channelId.toLowerCase(Locale.ROOT));
            String displayName = program.channelDisplayName.isEmpty() ? program.channelId : program.channelDisplayName;
            if (channel == null) {
                channel = this.exactNames.get(displayName);
            }
            if (channel == null && aliases != null && (alias = aliases.get(program.channelId)) != null && (channel = this.exactIds.get(alias.toLowerCase(Locale.ROOT))) == null) {
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
        for (Channel channel : channels) {
            if (channel != null && (value = key.get(channel)) != null && !value.isEmpty()) {
                existing = values.putIfAbsent(value, channel);
                if (existing != null && existing.isYoutubeVariant() && !channel.isYoutubeVariant()) {
                    // The one replacement rule kept: a playable
                    // channel supersedes a YouTube variant.
                    values.put(value, channel);
                }
                // Audit S4: on any other duplicate the FIRST channel
                // wins. The old code removed BOTH channels from the
                // index, so a shared tvgId left every channel with
                // that id without any EPG.
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
