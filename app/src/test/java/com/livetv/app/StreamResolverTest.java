package com.livetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import org.junit.Test;

/**
 * Resolver and live-catalog regression tests. The DaddyLive page shape is
 * the app's single point of failure, so every extraction pattern the
 * resolver supports gets a fixture here. Fixtures mirror the real pages
 * (verified 2026-10-30) with example CDN hosts swapped in.
 */
public class StreamResolverTest {

    @Test
    public void endpointsUseCurrentPrimaryDomain() {
        assertEquals("https://thedaddy.pk/watch.php?id=51", StreamEndpoints.watchUrl(StreamEndpoints.PRIMARY, "51"));
        assertEquals("https://thedaddy.pk/stream/stream-51.php", StreamEndpoints.streamUrl(StreamEndpoints.PRIMARY, "51"));
        assertEquals("https://thedaddy.pk/24-7-channels.php", StreamEndpoints.channelsUrl(StreamEndpoints.PRIMARY));
        assertTrue(StreamEndpoints.BASES.get(0).equals(StreamEndpoints.PRIMARY));
    }

    @Test
    public void extractsSourceAtob() throws Exception {
        String html = "<script>var player = { source: window.atob('aHR0cHM6Ly9jZG4uZXhhbXBsZS5jb20vbGl2ZS9zdHJlYW0ubTN1OA==') };</script>";
        assertEquals("https://cdn.example.com/live/stream.m3u8", OnDeviceStreamServer.extractStreamUrl(html));
    }

    @Test
    public void extractsStreamUrlField() throws Exception {
        String html = "<script>cfg = { streamUrl: 'https://cdn.example.com/live/stream.m3u8?k=1' };</script>";
        assertEquals("https://cdn.example.com/live/stream.m3u8?k=1", OnDeviceStreamServer.extractStreamUrl(html));
    }

    @Test
    public void extractsDirectM3u8Fallback() throws Exception {
        // No source:/streamUrl: fields at all - the dlhd-cli direct scan.
        String html = "<html><body><video data-src=\"https://cdn.example.com/live/stream.m3u8\"></video></body></html>";
        assertEquals("https://cdn.example.com/live/stream.m3u8", OnDeviceStreamServer.extractStreamUrl(html));
    }

    @Test
    public void extractsGenericAtobFallback() throws Exception {
        // atob() not attached to a source: field - the dlhd-cli generic scan.
        String html = "<script>load(atob(\"aHR0cHM6Ly9jZG4uZXhhbXBsZS5jb20veC5tM3U4\"));</script>";
        assertEquals("https://cdn.example.com/x.m3u8", OnDeviceStreamServer.extractStreamUrl(html));
    }

    @Test
    public void extractsFileConfigFallback() throws Exception {
        String html = "<script>jwplayer().setup({ file: \"https://cdn.example.com/live/stream.m3u8\" });</script>";
        assertEquals("https://cdn.example.com/live/stream.m3u8", OnDeviceStreamServer.extractStreamUrl(html));
    }

    @Test
    public void rejectsPageWithoutStream() {
        try {
            OnDeviceStreamServer.extractStreamUrl("<html><body>offline</body></html>");
            fail("expected an exception for a page with no stream");
        } catch (Exception expected) {
        }
    }

    @Test
    public void parsesLiveChannelsPage() {
        String html = "<div class=\"grid\">"
                + "<a class=\"card\" href=\"/watch.php?id=51\" data-title=\"abc usa\" data-first=\"A\">"
                + "<div class=\"card__title\">ABC USA</div><div class=\"\">ID: 51</div></a>"
                + "<a class=\"card\" href=\"/watch.php?id=283\" data-title=\"antenna tv usa\" data-first=\"A\">"
                + "<div class=\"card__title\">Antenna TV USA</div><div class=\"\">ID: 283</div></a>"
                + "</div>";
        Map<String, String> channels = LiveCatalog.parseChannelsPage(html);
        assertEquals(2, channels.size());
        assertEquals("ABC USA", channels.get("51"));
        assertEquals("Antenna TV USA", channels.get("283"));
    }

    @Test
    public void unwrapsRealTiktikPngSegment() throws Exception {
        // A real segment captured from channel 339's CDN on 2026-10-30:
        // a 512x605 PNG whose pixels carry a "TIKTIKPX" header plus a
        // 928,904-byte gzip that decompresses to 983,616 bytes of
        // MPEG-TS (expected values derived by walking the format, not
        // from the implementation). If the unwrap breaks, every channel
        // breaks, so this fixture guards it in CI from now on.
        byte[] png;
        try (java.io.InputStream in = getClass().getResourceAsStream("/tiktikpx-sample.png")) {
            if (in == null) fail("fixture missing from test resources");
            png = in.readAllBytes();
        }
        byte[] ts = OnDeviceStreamServer.unwrapPayload(png);
        org.junit.Assert.assertNotNull(ts);
        assertEquals(983616, ts.length);
        assertEquals(0, ts.length % 188);
        assertEquals(0x47, ts[0] & 0xFF);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ts);
        assertEquals(0x6231ab71L, crc.getValue());
    }

    @Test
    public void unwrapLeavesPlainTsUntouched() {
        byte[] ts = new byte[188 * 3];
        for (int i = 0; i < ts.length; i += 188) ts[i] = 0x47;
        byte[] out = OnDeviceStreamServer.unwrapPayload(ts);
        assertEquals(ts.length, out.length);
        assertEquals(0x47, out[0] & 0xFF);
    }

    @Test
    public void playerPageUrlUsesPrimaryDomain() {
        assertEquals("https://thedaddy.pk/stream/stream-51.php", OnDeviceStreamServer.playerPageUrl("51", 0));
        assertEquals("https://thedaddy.pk/watch/stream-51.php", OnDeviceStreamServer.playerPageUrl("51", 3));
    }
}
