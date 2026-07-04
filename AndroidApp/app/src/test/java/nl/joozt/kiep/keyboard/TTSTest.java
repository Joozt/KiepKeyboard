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
    }

    @Test
    public void returnsTextAfterLastPeriod() {
        assertEquals(" how are you", TTS.lastSentence("Hi there. how are you."));
    }

    @Test
    public void returnsTextAfterLastQuestionMark() {
        assertEquals(" Three", TTS.lastSentence("One? Two? Three?"));
    }

    @Test
    public void returnsTextAfterLastExclamationMark() {
        assertEquals(" Go", TTS.lastSentence("Stop! Go!"));
    }

    @Test
    public void picksTheLatestTerminatorOfMixedKinds() {
        assertEquals(" C", TTS.lastSentence("A. B! C?"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals("hello", TTS.lastSentence("  hello  "));
    }
}
