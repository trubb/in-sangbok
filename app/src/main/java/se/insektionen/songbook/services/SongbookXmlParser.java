package se.insektionen.songbook.services;

import android.text.TextUtils;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import se.insektionen.songbook.model.Song;
import se.insektionen.songbook.model.SongPart;
import se.insektionen.songbook.model.Songbook;

/**
 * Parses XML into model objects.
 */
public final class SongbookXmlParser {
    private static final String TAG = SongbookXmlParser.class.getSimpleName();
    private XmlPullParser mXml;

    private SongbookXmlParser(XmlPullParser xml) {
        mXml = xml;
    }

    public static Songbook parseSongbook(Reader reader) throws XmlPullParserException, IOException {
        XmlPullParserFactory xmlParserFactory = XmlPullParserFactory.newInstance();
        XmlPullParser xmlParser = xmlParserFactory.newPullParser();
        xmlParser.setInput(reader);

        SongbookXmlParser instance = new SongbookXmlParser(xmlParser);
        return instance.doParse();
    }

    static String readAttribute(XmlPullParser xmlParser, String name) {
        for (int i = 0; i < xmlParser.getAttributeCount(); i++) {
            if (name.equals(xmlParser.getAttributeName(i))) {
                return xmlParser.getAttributeValue(i).trim();
            }
        }
        return null;
    }

    private Songbook doParse() throws XmlPullParserException, IOException {
        // Read the top-level <songs> element and make sure it's nothing else
        mXml.next();
        mXml.require(XmlPullParser.START_TAG, null, Elements.SONGS);

        String updated = readAttribute(mXml, Attributes.UPDATED);
        String description = readAttribute(mXml, Attributes.DESCRIPTION);

        List<Song> songs = new ArrayList<>();
        do {
            mXml.next();

            // Skip any text inside <songs>
            while (XmlPullParser.TEXT == mXml.getEventType()) {
                mXml.next();
            }

            // Check if we skipped all the way to the end
            if (isAtEndOfDocument()) {
                break;
            }

            Song song = tryParseSong();
            if (null != song) {
                songs.add(song);
                Log.d(TAG, "Read a song \"" + song.getName() + "\".");
            }

            mXml.next();

            // Continue reading until we reach the end
        } while (!isAtEndOfDocument());

        Log.i(TAG, "Finished parsing songbook, got " + songs.size() + " song(s).");
        return new Songbook(description, updated, songs);
    }

    private List<SongPart> doParseSongParts() throws XmlPullParserException, IOException {
        List<SongPart> songParts = new ArrayList<>();

        // Continue reading until we reach the end tag </song>
        int parsingEvent = mXml.next();
        while (XmlPullParser.END_TAG != parsingEvent || !Elements.SONG.equals(mXml.getName())) {

            SongPart songPart = null;
            if (XmlPullParser.TEXT == parsingEvent) {
                songPart = tryParseText(SongPart.TYPE_PARAGRAPH);
            } else {
                mXml.require(XmlPullParser.START_TAG, null, null);
                switch (mXml.getName()) {
                    case Elements.P:
                        songPart = tryParseSongPart(SongPart.TYPE_PARAGRAPH);
                        break;
                    case Elements.COMMENT:
                        songPart = tryParseSongPart(SongPart.TYPE_COMMENT);
                        break;
                    case Elements.HEADER:
                        songPart = tryParseSongPart(SongPart.TYPE_HEADER);
                        break;
                    default:
                        Log.w(TAG, "Song contains unrecognized part \"" + mXml.getName() + "\", reading a format that is newer than the app supports?");
                        skip();
                        break;
                }
            }

            if (null != songPart) {
                songParts.add(songPart);
            }

            parsingEvent = mXml.next();
        }

        return songParts;
    }

    private boolean isAtEndOfDocument() throws XmlPullParserException {
        int parsingEvent = mXml.getEventType();
        return XmlPullParser.END_DOCUMENT == parsingEvent || (XmlPullParser.END_TAG == parsingEvent && Elements.SONGS.equals(mXml.getName()));
    }

    private void skip() throws XmlPullParserException, IOException {
        if (XmlPullParser.START_TAG != mXml.getEventType()) {
            return;
        }

        int depth = 1;
        while (depth != 0) {
            switch (mXml.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private Song tryParseSong() {
        try {
            // Ensure we are now at the start of a song
            mXml.require(XmlPullParser.START_TAG, null, Elements.SONG);

            // Read and validate attributes
            String author = readAttribute(mXml, Attributes.AUTHOR);
            String category = readAttribute(mXml, Attributes.CATEGORY);
            String composer = readAttribute(mXml, Attributes.COMPOSER);
            String melody = readAttribute(mXml, Attributes.MELODY);
            String name = readAttribute(mXml, Attributes.NAME);

            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(category)) {
                throw new XmlPullParserException("Missing required attribute " + Attributes.NAME + " and/or " + Attributes.CATEGORY + ".");
            }

            List<SongPart> parts = doParseSongParts();
            return new Song(author, category, composer, melody, name, parts);
        } catch (Exception ex) {
            Log.e(TAG, "Exception while parsing song: " + ex);
            return null;
        }
    }

    private SongPart tryParseSongPart(int type) {
        String name = mXml.getName();
        try {
            mXml.next();
            if (XmlPullParser.END_TAG == mXml.getEventType()) {
                // This song part was empty for some reason, ignore it
                return null;
            }

            // Content of song part must be text only, no nested tags
            mXml.require(XmlPullParser.TEXT, null, null);
            SongPart songPart = tryParseText(type);
            mXml.next();
            mXml.require(XmlPullParser.END_TAG, null, name);

            return songPart;
        } catch (Exception ex) {
            Log.e(TAG, "Exception while parsing song part: " + ex);
            return null;
        }
    }

    private SongPart tryParseText(int type) {
        String text = mXml.getText().trim();
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        // Eliminate whitespace caused by indentation but keep the line breaks internal to the text
        text = text.replaceAll("\\s*\\r?\\n\\s*", "\n");
        return new SongPart(type, text);
    }

    static class Attributes {
        public static final String AUTHOR = "author";
        public static final String CATEGORY = "category";
        public static final String COMPOSER = "composer";
        public static final String DESCRIPTION = "description";
        public static final String MELODY = "melody";
        public static final String NAME = "name";
        public static final String UPDATED = "updated";
    }

    static class Elements {
        public static final String COMMENT = "comment";
        public static final String HEADER = "header";
        public static final String P = "p";
        public static final String SONG = "song";
        public static final String SONGS = "songs";
    }
}
