package nl.joozt.kiep.keyboard;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Edits one text file picked via the Storage Access Framework (the File, in
 * app terminology - typically living in Dropbox). See
 * docs/proposals/editable-files.md for the design.
 */
public class FileEditorActivity extends AppCompatActivity {
    private static final String BOM = "\uFEFF";

    /**
     * Decodes raw file content for the editor: strips a UTF-8 BOM and
     * normalizes line endings to \n (EditText works in \n), remembering
     * whether the file used \r\n so {@link #store} can restore it.
     */
    static LoadedText load(String raw) {
        boolean crlf = raw.contains("\r\n");
        String text = raw;
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        return new LoadedText(text.replace("\r\n", "\n"), crlf);
    }

    /**
     * Encodes editor content for the file: restores \r\n when the file used
     * it. A stripped BOM is never written back.
     */
    static String store(String text, boolean crlf) {
        return crlf ? text.replace("\n", "\r\n") : text;
    }

    static class LoadedText {
        final String text;
        final boolean crlf;

        LoadedText(String text, boolean crlf) {
            this.text = text;
            this.crlf = crlf;
        }
    }
}
