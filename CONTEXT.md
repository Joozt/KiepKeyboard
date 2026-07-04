# KiepKeyboard

Fullscreen typing app for a hardware USB keyboard, used as a portable communication aid for disabled people. Physical keys double as commands; the phone is locked to one giant text field.

## Language

**Notepad**:
The home screen's persistent text buffer. Lives only on the device and is restored when the app reopens; never a file.
_Avoid_: scratch pad, home text

**File**:
A text file the user edits, picked once via the system file picker and typically living in Dropbox.
_Avoid_: document, note

**File editor**:
The mode that edits the File, opened with the open-file hotkey from the Notepad. Saves automatically; there is no save button.
_Avoid_: viewer, editable mode

**Display**:
A nearby paired receiver (Ulanzi pixel clock, later a phone/tablet) that shows typed text over BLE. Never reachable via the internet.
_Avoid_: screen (ambiguous with the phone's own), monitor
