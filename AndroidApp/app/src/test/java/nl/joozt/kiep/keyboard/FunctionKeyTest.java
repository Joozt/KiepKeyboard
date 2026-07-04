package nl.joozt.kiep.keyboard;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class FunctionKeyTest {

    /**
     * Every multi-character option the settings UI offers must resolve to a
     * FunctionKey; single characters take the char-listener path. Catches
     * someone adding e.g. "Page Up" to the array without an enum entry.
     */
    @Test
    public void everySettingsKeyOptionResolves() {
        for (String key : SettingsActivity.functionKeys) {
            if (key.length() > 1) {
                assertNotNull(key + " must resolve", GlobalKeyPressListener.FunctionKey.fromString(key));
            }
        }
    }
}
