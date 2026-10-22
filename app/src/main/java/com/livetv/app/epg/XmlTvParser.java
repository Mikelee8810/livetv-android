package com.livetv.app.epg;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/* JADX INFO: loaded from: classes3.dex */
public final class XmlTvParser {
    private static final DateTimeFormatter XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z");

    public List<Program> parse(InputStream xml, Instant windowStart, Instant windowEnd) throws Exception {
        if (xml == null || windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("xml and window bounds are required");
        }
        if (windowEnd.toEpochMilli() <= windowStart.toEpochMilli()) {
            throw new IllegalArgumentException("window end must be after start");
        }
        ProgramHandler handler = new ProgramHandler(windowStart, windowEnd);
        SAXParserFactory.newInstance().newSAXParser().parse(xml, handler);
        return handler.programs;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Instant timestamp(String value) {
        return OffsetDateTime.parse(value.trim(), XMLTV_TIME).toInstant();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean intersects(Program program, Instant windowStart, Instant windowEnd) {
        return program.end.toEpochMilli() > windowStart.toEpochMilli() && program.start.toEpochMilli() < windowEnd.toEpochMilli();
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
        private final List<Program> programs = new ArrayList();
        private final Map<String, String> channelDisplayNames = new HashMap();

        ProgramHandler(Instant windowStart, Instant windowEnd) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void startElement(String uri, String localName, String qualifiedName, Attributes attributes) {
            if ("programme".equals(qualifiedName)) {
                this.channelId = attributes.getValue("channel");
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

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void characters(char[] characters, int start, int length) {
            if (this.text != null) {
                this.text.append(characters, start, length);
            }
        }

        @Override // org.xml.sax.helpers.DefaultHandler, org.xml.sax.ContentHandler
        public void endElement(String uri, String localName, String qualifiedName) {
            if ("programme".equals(qualifiedName)) {
                addProgram();
                this.channelId = null;
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
                if ("title".equals(qualifiedName)) {
                    this.title = value;
                } else if ("sub-title".equals(qualifiedName)) {
                    this.subtitle = value;
                } else if ("desc".equals(qualifiedName)) {
                    this.description = value;
                } else if ("category".equals(qualifiedName)) {
                    this.category = value;
                } else if ("value".equals(qualifiedName)) {
                    this.rating = value;
                } else if ("display-name".equals(qualifiedName) && this.sourceChannelId != null && !value.trim().isEmpty() && !this.channelDisplayNames.containsKey(this.sourceChannelId)) {
                    this.channelDisplayNames.put(this.sourceChannelId, value.trim());
                }
                this.textElement = null;
                this.text = null;
            }
        }

        private void addProgram() {
            if (this.channelId == null || this.start == null || this.stop == null) {
                return;
            }
            try {
                Program program = new Program(this.channelId, this.channelDisplayNames.get(this.channelId), this.title, this.subtitle, this.description, XmlTvParser.timestamp(this.start), XmlTvParser.timestamp(this.stop), this.category, this.rating);
                if (XmlTvParser.intersects(program, this.windowStart, this.windowEnd)) {
                    this.programs.add(program);
                }
            } catch (IllegalArgumentException e) {
            }
        }
    }
}
