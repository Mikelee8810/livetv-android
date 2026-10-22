package com.livetv.app.epg;

import com.livetv.app.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* JADX INFO: loaded from: classes3.dex */
final class EpgProgressiveMerge {
    private final List<Channel> channels;
    private final Map<String, EpgSnapshot> snapshots = new HashMap();
    private final List<String> sourceOrder;

    EpgProgressiveMerge(List<Channel> channels, List<String> sourceOrder) {
        this.channels = new ArrayList(channels);
        this.sourceOrder = new ArrayList(sourceOrder);
    }

    synchronized void add(String source, EpgSnapshot snapshot) {
        if (source != null && snapshot != null) {
            this.snapshots.put(source, snapshot);
        }
    }

    synchronized EpgSnapshot snapshot() {
        List<EpgSnapshot> ordered;
        ordered = new ArrayList<>();
        for (String source : this.sourceOrder) {
            EpgSnapshot snapshot = this.snapshots.get(source);
            if (snapshot != null) {
                ordered.add(snapshot);
            }
        }
        return EpgSnapshot.mergePreferExisting(this.channels, ordered);
    }
}
