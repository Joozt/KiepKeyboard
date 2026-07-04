package nl.joozt.kiep.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TTSTest {

    @Test
    public void emptyBufferReturnsEmpty() {
        assertEquals("", TTS.lastSentence(""));
    }

    @Test
    public void whitespaceOnlyBufferReturnsEmpty() {
        // Used to crash with StringIndexOutOfBoundsException
        assertEquals("", TTS.lastSentence("   "));
        assertEquals("", TTS.lastSentence("\n\n"));
    }

    @Test
    public void singleSentenceWithoutTerminatorReturnsWhole() {
        assertEquals("hello world", TTS.lastSentence("hello world"));
    }

    @Test
    public void trailingTerminatorIsIgnored() {
        assertEquals("hello world", TTS.lastSentence("hello world."));
        assertEquals("hello world", TTS.lastSentence("hello world!"));
        assertEquals("hello world", TTS.lastSentence("hello world?"));
        assertEquals("hello world", TTS.lastSentence("hello world\n"));
    }

    @Test
    public void returnsTextAfterLastPeriod() {
        assertEquals("how are you", TTS.lastSentence("Hi there. how are you."));
    }

    @Test
    public void returnsTextAfterLastQuestionMark() {
        assertEquals("Three", TTS.lastSentence("One? Two? Three?"));
    }

    @Test
    public void returnsTextAfterLastExclamationMark() {
        assertEquals("Go", TTS.lastSentence("Stop! Go!"));
    }

    @Test
    public void picksTheLatestTerminatorOfMixedKinds() {
        assertEquals("C", TTS.lastSentence("A. B! C?"));
    }

    @Test
    public void newlineBoundsSentences() {
        assertEquals("second line", TTS.lastSentence("first line\nsecond line"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals("hello", TTS.lastSentence("  hello  "));
    }

    @Test
    public void cursorInMiddleSentenceSpeaksThatSentence() {
        assertEquals("Two three", TTS.sentenceAt("One. Two three. Four.", 7));
    }

    @Test
    public void cursorInFirstSentenceSpeaksFirstSentence() {
        assertEquals("One", TTS.sentenceAt("One. Two.", 1));
    }

    @Test
    public void cursorAtStartSpeaksFirstSentence() {
        assertEquals("One", TTS.sentenceAt("One. Two.", 0));
    }

    @Test
    public void cursorJustAfterTerminatorSpeaksTheFollowingSentence() {
        assertEquals("Two", TTS.sentenceAt("One. Two.", 4));
    }

    @Test
    public void cursorOnMiddleLineSpeaksThatLine() {
        assertEquals("bb", TTS.sentenceAt("aa\nbb\ncc", 4));
    }

    @Test
    public void cursorAtStartOfLineSpeaksThatLine() {
        // Regression: the whitespace back-off used to drag the cursor across
        // the line break into the previous sentence
        assertEquals("bb", TTS.sentenceAt("aa\nbb", 3));
    }

    @Test
    public void cursorAtStartOfLineWithLeadingSpaceSpeaksThatLine() {
        assertEquals("bb", TTS.sentenceAt("aa \n bb", 4));
    }

    @Test
    public void cursorOnEmptyLineFallsBackToPreviousSentence() {
        assertEquals("aa", TTS.sentenceAt("aa\n\nbb", 3));
    }

    @Test
    public void invalidCursorSpeaksLastSentence() {
        assertEquals("Two", TTS.sentenceAt("One. Two.", -1));
        assertEquals("Two", TTS.sentenceAt("One. Two.", 999));
    }
}
