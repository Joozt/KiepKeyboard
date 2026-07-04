package nl.joozt.kiep.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

public class AbbreviationsTest {
    private final Map<String, String> defaults = Abbreviations.parse(SettingsActivity.ABBREVIATIONS_DEFAULT);

    @Test
    public void parsesTheDefaultSetting() {
        assertEquals(1, defaults.size());
        assertEquals("KiepKeyboard", defaults.get("kkb"));
    }

    @Test
    public void parsesMultipleLinesAndTrimsExpansions() {
        Map<String, String> parsed = Abbreviations.parse("::a::Alpha \n::b::Beta beta");
        assertEquals(2, parsed.size());
        assertEquals("Alpha", parsed.get("a"));
        assertEquals("Beta beta", parsed.get("b"));
    }

    @Test
    public void abbreviationKeysAreLowercased() {
        Map<String, String> parsed = Abbreviations.parse("::KKB::KiepKeyboard");
        assertEquals("KiepKeyboard", parsed.get("kkb"));
    }

    @Test
    public void garbageParsesToNothing() {
        assertEquals(0, Abbreviations.parse("").size());
        assertEquals(0, Abbreviations.parse("no abbreviations here").size());
    }

    @Test
    public void expandsWordAtStartOfText() {
        Abbreviations.Expansion expansion = Abbreviations.findExpansion("kkb ", defaults);
        assertNotNull(expansion);
        assertEquals(0, expansion.start);
        assertEquals(3, expansion.end);
        assertEquals("KiepKeyboard", expansion.replacement);
        assertEquals("kkb", expansion.originalWord);
    }

    @Test
    public void expandsWordInTheMiddleOfText() {
        Abbreviations.Expansion expansion = Abbreviations.findExpansion("hello kkb.", defaults);
        assertNotNull(expansion);
        assertEquals(6, expansion.start);
        assertEquals(9, expansion.end);
    }

    @Test
    public void expandsAfterNewline() {
        Abbreviations.Expansion expansion = Abbreviations.findExpansion("foo\nkkb;", defaults);
        assertNotNull(expansion);
        assertEquals(4, expansion.start);
        assertEquals(7, expansion.end);
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertNotNull(Abbreviations.findExpansion("KKB!", defaults));
    }

    @Test
    public void allEndCharsTrigger() {
        for (char endChar : " .,\n?!:;".toCharArray()) {
            assertNotNull("end char '" + endChar + "'", Abbreviations.findExpansion("kkb" + endChar, defaults));
        }
    }

    @Test
    public void noExpansionWithoutEndChar() {
        assertNull(Abbreviations.findExpansion("kkb", defaults));
    }

    @Test
    public void noExpansionForUnknownWord() {
        assertNull(Abbreviations.findExpansion("xyz ", defaults));
    }

    @Test
    public void noExpansionForEmptyText() {
        assertNull(Abbreviations.findExpansion("", defaults));
        assertNull(Abbreviations.findExpansion(" ", defaults));
    }
}
