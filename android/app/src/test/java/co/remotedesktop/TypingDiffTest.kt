package co.remotedesktop

import co.remotedesktop.TypingDiff.Backspace
import co.remotedesktop.TypingDiff.Enter
import co.remotedesktop.TypingDiff.Text
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a viewer sends as this phone's keyboard edits its hidden field. Plain JVM. */
class TypingDiffTest {
    @Test
    fun typedTextGoesOutAsIs() {
        assertEquals(listOf(Text("hi")), TypingDiff.edits("", "hi"))
        assertEquals(listOf(Text("!")), TypingDiff.edits("hi", "hi!"))
        assertEquals(emptyList<TypingDiff.Edit>(), TypingDiff.edits("same", "same"))
    }

    @Test
    fun aCorrectionBackspacesToWhereTheTextsPartThenTypesTheRest() {
        // The keyboard swapped "helo" for "hello ".
        assertEquals(listOf(Backspace, Text("lo ")), TypingDiff.edits("helo", "hello "))
        assertEquals(listOf(Backspace, Backspace, Text("p")), TypingDiff.edits("hello", "help"))
        assertEquals(listOf(Backspace, Backspace, Backspace), TypingDiff.edits("abc", ""))
    }

    @Test
    fun lineBreaksAreEnterBetweenRunsOfText() {
        assertEquals(listOf(Enter, Text("b"), Enter), TypingDiff.edits("a", "a\nb\n"))
        assertEquals(listOf(Text("x"), Enter, Enter, Text("y")), TypingDiff.edits("", "x\n\ny"))
    }
}
