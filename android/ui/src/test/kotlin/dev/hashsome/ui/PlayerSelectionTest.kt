package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSelectionTest {
    private val kitchen = PlayerChoice("ma:kitchen", "Kitchen")
    private val bathroom = PlayerChoice("ma:bathroom", "Bathroom")

    @Test
    fun `a card starts on its first player and goes back to the one picked last`() {
        val choices = listOf(PlayerChoice("ma:a-first", "A"), PlayerChoice("ma:a-second", "B"))
        assertEquals(choices[0], PlayerSelection.of(choices))
        PlayerSelection.choose(choices, choices[1])
        assertEquals(choices[1], PlayerSelection.of(choices))
        PlayerSelection.choose(choices, choices[0])
        assertEquals(choices[0], PlayerSelection.of(choices))
    }

    @Test
    fun `a card is known by its first choice, and a pick that is no longer offered is forgotten`() {
        PlayerSelection.choose(listOf(kitchen, bathroom), bathroom)
        assertEquals(bathroom, PlayerSelection.of(listOf(kitchen, bathroom)))
        // The same card with another list of players does not offer the one picked: it starts on its first.
        assertEquals(kitchen, PlayerSelection.of(listOf(kitchen, PlayerChoice("ma:porch", "Porch"))))
    }
}
