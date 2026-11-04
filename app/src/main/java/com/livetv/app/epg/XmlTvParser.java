package com.livetv.app.epg;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/* JADX INFO: loaded from: classes3.dex */
public final class XmlTvParser {
    private static final DateTimeFormatter XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z");
    private static final DateTimeFormatter XMLTV_TIME_COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ");
    private static final DateTimeFormatter XMLTV_TIME_LOCAL = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    // Minute-precision shapes are valid XMLTV in the wild (audit R7):
    // without these, every programme in such a feed vanished silently.
    private static final DateTimeFormatter XMLTV_TIME_MIN = DateTimeFormatter.ofPattern("yyyyMMddHHmm Z");
    private static final DateTimeFormatter XMLTV_TIME_MIN_COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmZ");
    private static final DateTimeFormatter XMLTV_TIME_MIN_LOCAL = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int MAX_TEXT_CHARS = 8192;

    public List<Program> parse(InputStream xml, Instant windowStart, Instant windowEnd) throws Exception {
        return parse(xml, windowStart, windowEnd, null);
    }

    public List<Program> parse(InputStream xml, Instant windowStart, Instant windowEnd, Set<String> targetChannels) throws Exception {
        return parse(xml, windowStart, windowEnd, targetChannels, null);
    }

    public List<Program> parse(InputStream xml, Instant windowStart, Instant windowEnd, Set<String> targetChannels, Set<String> canonicalChannelNames) throws Exception {
        if (xml == null || windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("xml and window bounds are required");
        }
        if (windowEnd.toEpochMilli() <= windowStart.toEpochMilli()) {
            throw new IllegalArgumentException("window end must be after start");
        }
        ProgramHandler handler = new ProgramHandler(windowStart, windowEnd, targetChannels, canonicalChannelNames);
        SAXParserFactory factory = SAXParserFactory.newInstance();
        // Audit X4: harden the parser against external entities and
        // XInclude. DOCTYPE itself stays allowed: real XMLTV feeds
        // carry one, and rejecting it would break every source.
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        factory.setXIncludeAware(false);
        factory.newSAXParser().parse(xml, handler);
        return handler.programs;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Instant timestamp(String value) {
        // Audit X1: the strict pattern demands "yyyyMMddHHmmss +0000"
        // exactly, and DateTimeParseException is NOT an
        // IllegalArgumentException, so one odd timestamp escaped
        // addProgram's catch and killed the whole source. Accept the
        // shapes real feeds emit; fail as IllegalArgumentException.
        String trimmed = value == null ? "" : value.trim();
        try {
            return OffsetDateTime.parse(trimmed, XMLTV_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed, XMLTV_TIME_COMPACT).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, XMLTV_TIME_LOCAL).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed, XMLTV_TIME_MIN).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed, XMLTV_TIME_MIN_COMPACT).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, XMLTV_TIME_MIN_LOCAL).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("Unparseable XMLTV timestamp: " + value);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean intersects(Program program, Instant windowStart, Instant windowEnd) {
        return program.end.toEpochMilli() > windowStart.toEpochMilli() && program.start.toEpochMilli() < windowEnd.toEpochMilli();
    }

    private static final class PendingProgram {
        final String channelId;
        final Instant start;
        final String title;
        final String subtitle;
        final String description;
        final String category;
        final String rating;

        PendingProgram(String channelId, Instant start, String title, String subtitle, String description, String category, String rating) {
            this.channelId = channelId;
            this.start = start;
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
            this.category = category;
            this.rating = rating;
        }
    }

    private static final class ProgramHandler extends DefaultHandler {
        private String category;
        private String channelId;
        private String description;
        private boolean insideRating;
        private String rating;
        private String sourceChannelId;
        private String start;
        private String stop;
        private String subtitle;
        private StringBuilder text;
        private String textElement;
        private String title;
        private final Instant windowEnd;
        private final Instant windowStart;
        private final Set<String> targetChannels;
        private final Set<String> canonicalChannelNames;
        private final List<Program> programs = new ArrayList();
        private final Map<String, String> channelDisplayNames = new HashMap();
        // Audit X2: programmes with no stop attribute wait here; the
        // next programme on the same channel supplies their stop.
        private final Map<String, PendingProgram> pendingByChannel = new HashMap();

        ProgramHandler(Instant windowStart, Instant windowEnd, Set<String> targetChannels, Set<String> canonicalChannelNames) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.targetChannels = targetChannels;
            this.canonicalChannelNames = canonicalChannelNames;
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void startElement(String uri, String localName, String qualifiedName, Attributes attributes) {
            if ("programme".equals(qualifiedName)) {
                String ch = attributes.getValue("channel");
                if (this.targetChannels != null && !this.targetChannels.isEmpty()) {
                    if (ch == null || (!this.targetChannels.contains(ch) && !this.targetChannels.contains(ch.toLowerCase(Locale.ROOT)) && !displayNameAccepted(ch))) {
                        this.channelId = null;
                        return;
                    }
                }
                this.channelId = ch;
                this.start = attributes.getValue("start");
                this.stop = attributes.getValue("stop");
                this.title = null;
                this.subtitle = null;
                this.description = null;
                this.category = null;
                this.rating = null;
                this.insideRating = false;
                this.textElement = null;
                this.text = null;
                return;
            }
            if ("channel".equals(qualifiedName)) {
                this.sourceChannelId = attributes.getValue("id");
                return;
            }
            if (this.sourceChannelId != null && this.channelId == null && "display-name".equals(qualifiedName)) {
                this.textElement = "display-name";
                this.text = new StringBuilder();
                return;
            }
            if (this.channelId != null) {
                if ("rating".equals(qualifiedName)) {
                    this.insideRating = true;
                    return;
                }
                if ("title".equals(qualifiedName) || "sub-title".equals(qualifiedName) || "desc".equals(qualifiedName) || "category".equals(qualifiedName) || (this.insideRating && "value".equals(qualifiedName))) {
                    this.textElement = qualifiedName;
                    this.text = new StringBuilder();
                }
            }
        }

        private boolean displayNameAccepted(String channelId) {
            // Audit E6: the repository filter used to run on the XMLTV
            // channel id alone, so a feed whose ids differ from its
            // display names lost every programme before the snapshot's
            // display-name fallback could ever see them.
            if (this.canonicalChannelNames == null || this.canonicalChannelNames.isEmpty()) {
                return false;
            }
            String displayName = this.channelDisplayNames.get(channelId);
            return displayName != null && this.canonicalChannelNames.contains(EpgSnapshot.canonical(displayName));
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void characters(char[] characters, int start, int length) {
            if (this.text != null && (this.channelId != null || this.sourceChannelId != null)) {
                // Audit X4: cap element text so a hostile or broken
                // feed cannot grow an unbounded buffer per element.
                int room = MAX_TEXT_CHARS - this.text.length();
                if (room > 0) {
                    this.text.append(characters, start, Math.min(length, room));
                }
            }
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void endElement(String uri, String localName, String qualifiedName) {
            if ("programme".equals(qualifiedName)) {
                if (this.channelId != null) {
                    addProgram();
                    this.channelId = null;
                }
                return;
            }
            if ("channel".equals(qualifiedName)) {
                this.sourceChannelId = null;
                return;
            }
            if ("rating".equals(qualifiedName)) {
                this.insideRating = false;
            }
            if (qualifiedName.equals(this.textElement)) {
                String value = this.text.toString();
                // Audit X3: multi-language feeds repeat title/desc;
                // the FIRST entry wins instead of the last.
                if ("title".equals(qualifiedName)) {
                    if (this.title == null) {
                        this.title = value;
                    }
                } else if ("sub-title".equals(qualifiedName)) {
                    if (this.subtitle == null) {
                        this.subtitle = value;
                    }
                } else if ("desc".equals(qualifiedName)) {
                    if (this.description == null) {
                        this.description = value;
                    }
                } else if ("category".equals(qualifiedName)) {
                    if (this.category == null) {
                        this.category = value;
                    }
                } else if ("value".equals(qualifiedName)) {
                    this.rating = value;
                } else if ("display-name".equals(qualifiedName) && this.sourceChannelId != null && !value.trim().isEmpty() && !this.channelDisplayNames.containsKey(this.sourceChannelId)) {
                    this.channelDisplayNames.put(this.sourceChannelId, value.trim());
                }
                this.textElement = null;
                this.text = null;
            }
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void endDocument() {
            // Flush stop-less programmes still pending at EOF
            // (audit X2), but cap the fabricated stop at start + 2h
            // (audit R6): the window end made one stop-less entry
            // pose as the current programme for up to a day and, via
            // the merge's "owns now" lock, block better data.
            for (PendingProgram pending : new ArrayList<>(this.pendingByChannel.values())) {
                Instant cap = pending.start.plus(2L, java.time.temporal.ChronoUnit.HOURS);
                emit(pending, cap.isBefore(this.windowEnd) ? cap : this.windowEnd);
            }
            this.pendingByChannel.clear();
        }

        private void emit(PendingProgram pending, Instant stop) {
            if (stop == null || !stop.isAfter(pending.start)) {
                return;
            }
            Program program = new Program(pending.channelId, this.channelDisplayNames.get(pending.channelId), pending.title, pending.subtitle, pending.description, pending.start, stop, pending.category, pending.rating);
            if (XmlTvParser.intersects(program, this.windowStart, this.windowEnd)) {
                this.programs.add(program);
            }
        }

        private void flushPending(String channelId, Instant stop) {
            PendingProgram pending = this.pendingByChannel.remove(channelId);
            if (pending != null) {
                emit(pending, stop);
            }
        }

        private void addProgram() {
            if (this.channelId == null || this.start == null) {
                return;
            }
            try {
                Instant startInstant = XmlTvParser.timestamp(this.start);
                // This programme's start is the previous stop-less
                // programme's stop on the same channel (audit X2).
                flushPending(this.channelId, startInstant);
                if (this.stop == null) {
                    this.pendingByChannel.put(this.channelId, new PendingProgram(this.channelId, startInstant, this.title, this.subtitle, this.description, this.category, this.rating));
                    return;
                }
                Program program = new Program(this.channelId, this.channelDisplayNames.get(this.channelId), this.title, this.subtitle, this.description, startInstant, XmlTvParser.timestamp(this.stop), this.category, this.rating);
                if (XmlTvParser.intersects(program, this.windowStart, this.windowEnd)) {
                    this.programs.add(program);
                }
            } catch (Exception e) {
                // Audit X1: one bad programme must never kill the
                // whole source; timestamp failures land here too.
            }
        }
    }
}
