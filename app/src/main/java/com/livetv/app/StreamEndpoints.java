package com.livetv.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single home for every DaddyLive / DLHD upstream domain.
 *
 * These services hop domains constantly: on 2026-10-30 dlhd.st 301-redirected
 * to thedaddy.pk and dlstreams.st redirected through dlive.sx to the same
 * place, while the app still hardcoded dlhd.st and dlstreams.st in the
 * resolver and broke. OkHttp follows redirects, so pointing the resolver at
 * the current primary host (and keeping the mirrors as fallbacks) survives
 * the next hop: only this file needs to change.
 *
 * Mirrors are taken from the operator's own public profile (@daddylive5):
 * daddylive.at (Live TV), daddylive.app (channel pages).
 */
public final class StreamEndpoints {

    /** Current primary host. dlhd.st and dlstreams.st both redirect here. */
    public static final String PRIMARY = "https://thedaddy.pk";

    /** Operator mirrors, tried when the primary host is unreachable. */
    public static final List<String> MIRRORS = Collections.unmodifiableList(Arrays.asList(
            "https://dlhd.pk",
            "https://daddylive.at",
            "https://daddylive.app",
            "https://dlhd.st",
            "https://dlstreams.st"
    ));

    /** Every base URL worth trying, primary first. */
    public static final List<String> BASES = buildBases();

    private static List<String> buildBases() {
        java.util.ArrayList<String> all = new java.util.ArrayList<>();
        all.add(PRIMARY);
        all.addAll(MIRRORS);
        return Collections.unmodifiableList(all);
    }

    private StreamEndpoints() {
    }

    public static String watchUrl(String base, String id) {
        return base + "/watch.php?id=" + id;
    }

    public static String streamUrl(String base, String id) {
        return base + "/stream/stream-" + id + ".php";
    }

    public static String streamUrl(String base, String route, String id) {
        return base + "/" + route + "/stream-" + id + ".php";
    }

    /** The live 24/7 channel list page (parsed by LiveCatalog). */
    public static String channelsUrl(String base) {
        return base + "/24-7-channels.php";
    }

    /** Legacy non-DaddyLive player host kept as route 0's watch referer. */
    public static final String NTVS_WATCH = "https://ntvs.cx/channel/phoenix/";
}
