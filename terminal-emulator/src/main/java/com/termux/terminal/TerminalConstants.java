package com.termux.terminal;

/**
 * Protocol and encoding constants shared by the emulator, the renderer and the view.
 */
public final class TerminalConstants {

    private TerminalConstants() {}

    /** Control codes and ranges with a fixed meaning in the terminal protocol. */
    public static final class Ascii {
        public static final int ESC = 27;
        public static final int DEL = 127;
        public static final int C0_MAX = 0x1F;
        public static final int C1_MIN = 0x80;
        public static final int C1_MAX = 0x9F;

        private Ascii() {}
    }

    /** Unicode boundaries used by the width tables and the glyph caches. */
    public static final class Unicode {
        public static final int ASCII_LIMIT = 0x80;
        public static final int BMP_LIMIT = 0x10000;

        private Unicode() {}
    }

    /** Alpha mask that marks a packed TextStyle foreground as a truecolor value. */
    public static final int TRUECOLOR_ALPHA_MASK = 0xFF000000;
}
