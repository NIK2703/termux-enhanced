package com.termux.app.terminal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.extrakeys.KeyCombination;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The binding model behind the session shortcuts: how a picked combination is stored, and when a
 * pressed key is considered a match.
 *
 * <p>The matcher has to compare the modifier set <em>exactly</em>. A binding that fired while an
 * extra modifier happened to be held would make ordinary typing trigger session actions — the
 * reason {@code Alt+u} must not fire on {@code Ctrl+Alt+u} either, since the built-in Ctrl+Alt
 * combinations live in the same key space.
 */
public class KeyboardShortcutTest {

    private static final int ACTION = 1;

    private static KeyboardShortcut binding(String... tokens) {
        return new KeyboardShortcut(Arrays.asList(tokens), ACTION);
    }

    // -------------------------------------------------------------------
    //  Storage round-trip
    // -------------------------------------------------------------------

    @Test
    public void tokensSurviveParseAndFormat() {
        assertEquals(Arrays.asList("ALT", "u"), KeyCombination.parse("ALT u"));
        assertEquals("ALT u", KeyCombination.format(Arrays.asList("ALT", "u")));
        assertEquals(Arrays.asList("CTRL", "ALT", "F5"),
            KeyCombination.parse(KeyCombination.format(Arrays.asList("CTRL", "ALT", "F5"))));
    }

    @Test
    public void modifiersAndNamedKeysAreCanonicalised() {
        // Lower case spellings of both kinds of token are accepted; modifiers and the key names the
        // picker knows are stored in their canonical upper-case spelling, so that a binding picked
        // in the UI and one typed by hand compare equal.
        assertEquals(Arrays.asList("CTRL", "ALT", "F5"), KeyCombination.parse("ctrl alt f5"));
        assertEquals(Arrays.asList("SHIFT", "u"), KeyCombination.parse("shift u"));
        // A plain character is not a name, so it keeps the case it was given — the matcher ignores
        // that case anyway.
        assertEquals(Arrays.asList("CTRL", "u"), KeyCombination.parse("ctrl u"));
        assertEquals(Arrays.asList("CTRL", "U"), KeyCombination.parse("ctrl U"));
        assertEquals(Arrays.asList("CTRL", "TAB"), KeyCombination.parse("ctrl tab"));
    }

    @Test
    public void legacyCtrlPlusKeyFormStillParses() {
        assertEquals(Arrays.asList("CTRL", "a"), KeyCombination.parse("ctrl+a"));
        assertEquals(Arrays.asList("CTRL", "a"), KeyCombination.parse("CTRL+a"));
    }

    @Test
    public void emptyValueMeansNoBinding() {
        assertTrue(KeyCombination.parse(null).isEmpty());
        assertTrue(KeyCombination.parse("").isEmpty());
        assertTrue(KeyCombination.parse("   ").isEmpty());
    }

    @Test
    public void keyIsTheLastNonModifierToken() {
        // The picker always appends the key last, but the reader does not rely on it.
        assertEquals("u", KeyCombination.keyOf(Arrays.asList("CTRL", "ALT", "u")));
        assertEquals("F5", KeyCombination.keyOf(Arrays.asList("CTRL", "F5")));
        assertTrue(KeyCombination.hasKey(Arrays.asList("CTRL", "u")));
        assertTrue(KeyCombination.hasLeadingModifier(Arrays.asList("CTRL", "u")));
    }

    @Test
    public void combinationWithoutKeyOrWithoutLeadingModifierIsNotABinding() {
        assertFalse(KeyCombination.hasKey(Collections.singletonList("CTRL")));
        assertFalse(KeyCombination.hasLeadingModifier(Arrays.asList("u", "ALT")));
    }

    @Test
    public void displayStringNamesTheModifiers() {
        assertEquals("Ctrl + Alt + u",
            KeyCombination.toDisplayString(Arrays.asList("CTRL", "ALT", "u")));
        assertEquals("Fn + F5", KeyCombination.toDisplayString(Arrays.asList("FN", "F5")));
    }

    // -------------------------------------------------------------------
    //  Matching
    // -------------------------------------------------------------------

    @Test
    public void altPlusKeyMatchesTheReportedCase() {
        KeyboardShortcut shortcut = binding("ALT", "u");

        assertTrue(shortcut.matches("u", false, true, false, false));
        // The picker stores whatever the custom-text field was given, and a keyboard may report
        // either case depending on Shift, so a one-character key compares case-insensitively.
        assertTrue(shortcut.matches("U", false, true, false, false));
        assertTrue(binding("ALT", "U").matches("u", false, true, false, false));
    }

    @Test
    public void everyModifierMatchesOnItsOwn() {
        assertTrue(binding("CTRL", "u").matches("u", true, false, false, false));
        assertTrue(binding("ALT", "u").matches("u", false, true, false, false));
        assertTrue(binding("SHIFT", "u").matches("u", false, false, true, false));
        assertTrue(binding("FN", "u").matches("u", false, false, false, true));
    }

    @Test
    public void allFourModifiersTogether() {
        KeyboardShortcut shortcut = binding("CTRL", "ALT", "SHIFT", "FN", "u");
        assertTrue(shortcut.matches("u", true, true, true, true));
        assertFalse(shortcut.matches("u", true, true, true, false));
        assertFalse(shortcut.matches("u", true, true, false, true));
        assertFalse(shortcut.matches("u", true, false, true, true));
        assertFalse(shortcut.matches("u", false, true, true, true));
    }

    @Test
    public void extraModifierDoesNotFireTheBinding() {
        KeyboardShortcut shortcut = binding("ALT", "u");

        // Shift is held while typing a capital, Ctrl+Alt belongs to the built-in combinations.
        assertFalse(shortcut.matches("u", false, true, true, false));
        assertFalse(shortcut.matches("u", true, true, false, false));
        assertFalse(shortcut.matches("u", false, true, false, true));
    }

    @Test
    public void missingModifierDoesNotFireTheBinding() {
        assertFalse(binding("CTRL", "ALT", "u").matches("u", true, false, false, false));
        assertFalse(binding("CTRL", "ALT", "u").matches("u", false, true, false, false));
        assertTrue(binding("CTRL", "ALT", "u").matches("u", true, true, false, false));
    }

    @Test
    public void namedKeysCompareAsNames() {
        KeyboardShortcut shortcut = binding("CTRL", "F5");
        assertTrue(shortcut.matches("F5", true, false, false, false));
        assertFalse(shortcut.matches("F6", true, false, false, false));
        // A named key is never equal to the character that happens to be its first letter.
        assertFalse(shortcut.matches("F", true, false, false, false));

        // TAB and ENTER are the reason the named lookup runs before the code point: they would
        // otherwise resolve to their control character and never match.
        assertTrue(binding("CTRL", "TAB").matches("TAB", true, false, false, false));
        assertFalse(binding("CTRL", "TAB").matches("\t", true, false, false, false));
    }

    @Test
    public void otherKeyDoesNotFireTheBinding() {
        assertFalse(binding("ALT", "u").matches("i", false, true, false, false));
        // A multi-character key token (a literal custom binding) is compared as a whole.
        assertFalse(binding("ALT", "u").matches("uu", false, true, false, false));
    }

    @Test
    public void noKeyMeansNoMatch() {
        assertFalse(binding("ALT", "u").matches(null, false, true, false, false));
    }

    @Test
    public void bindingWithoutAKeyNeverMatches() {
        List<String> modifiersOnly = Arrays.asList("CTRL", "ALT");
        assertFalse(new KeyboardShortcut(modifiersOnly, ACTION)
            .matches("u", true, true, false, false));
    }
}
