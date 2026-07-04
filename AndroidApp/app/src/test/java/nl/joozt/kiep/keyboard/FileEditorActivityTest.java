package nl.joozt.kiep.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileEditorActivityTest {

    @Test
    public void loadStripsUtf8Bom() {
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("\uFEFFhello");
        assertEquals("hello", loaded.text);
    }

    @Test
    public void loadWithoutBomIsUnchanged() {
        assertEquals("hello", FileEditorActivity.load("hello").text);
    }

    @Test
    public void loadNormalizesCrlfAndRemembersIt() {
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("one\r\ntwo\r\n");
        assertEquals("one\ntwo\n", loaded.text);
        assertTrue(loaded.crlf);
    }

    @Test
    public void loadKeepsLfAndRemembersIt() {
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("one\ntwo\n");
        assertEquals("one\ntwo\n", loaded.text);
        assertFalse(loaded.crlf);
    }

    @Test
    public void storeRestoresCrlf() {
        assertEquals("one\r\ntwo", FileEditorActivity.store("one\ntwo", true));
    }

    @Test
    public void storeKeepsLf() {
        assertEquals("one\ntwo", FileEditorActivity.store("one\ntwo", false));
    }

    @Test
    public void crlfFileRoundTripsUnchanged() {
        String raw = "one\r\ntwo\r\n";
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load(raw);
        assertEquals(raw, FileEditorActivity.store(loaded.text, loaded.crlf));
    }

    @Test
    public void lfFileRoundTripsUnchanged() {
        String raw = "one\ntwo\n";
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load(raw);
        assertEquals(raw, FileEditorActivity.store(loaded.text, loaded.crlf));
    }

    @Test
    public void bomIsNotWrittenBack() {
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("\uFEFFhello");
        assertEquals("hello", FileEditorActivity.store(loaded.text, loaded.crlf));
    }

    @Test
    public void mixedEndingsNormalizeToCrlf() {
        // A file containing any \r\n is treated as a CRLF file; lone \n
        // lines get normalized to \r\n on the next save
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("one\r\ntwo\nthree");
        assertTrue(loaded.crlf);
        assertEquals("one\r\ntwo\r\nthree", FileEditorActivity.store(loaded.text, loaded.crlf));
    }

    @Test
    public void emptyFileRoundTrips() {
        FileEditorActivity.LoadedText loaded = FileEditorActivity.load("");
        assertEquals("", loaded.text);
        assertEquals("", FileEditorActivity.store(loaded.text, loaded.crlf));
        assertFalse(loaded.crlf);
    }
}
