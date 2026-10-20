package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import dev.hashsome.client.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/top-bar.tsx: PresenceAvatar and its color and initials.
class PersonControlTest {
    private val rig = Rig()

    private fun person(
        id: String = "dan",
        name: String = "Dan Smith",
        home: Boolean = true,
        location: String = if (home) "home" else "away",
        picture: String? = null,
        availability: Availability = Availability.Ready,
    ) = PersonEntity(ref(id), name, availability, location, home, picture)

    private fun control(id: String = "dan") = PersonControl(rig.handle(ref(id), EntityKind.Person)).also { rig.settle() }

    @Test
    fun `before the person is known the avatar is active and has no name`() {
        val state = control("nobody").state.value
        assertTrue(state.active)
        assertEquals("", state.name)
        assertEquals("", state.label)
    }

    @Test
    fun `someone at home is active and says where`() {
        rig.client.set(ref("dan"), person(picture = "/_hashsome/asset/ha?path=x"))
        val state = control().state.value
        assertTrue(state.active)
        assertEquals("Dan Smith, home", state.label)
        assertEquals("/_hashsome/asset/ha?path=x", state.pictureUrl)
    }

    @Test
    fun `someone away, or unreachable, is dimmed and follows the entity`() {
        rig.client.set(ref("dan"), person(home = false))
        val control = control()
        assertFalse(control.state.value.active)
        assertEquals("Dan Smith, away", control.state.value.label)
        rig.client.set(ref("dan"), person(home = true))
        rig.settle()
        assertTrue(control.state.value.active)
        rig.client.set(ref("dan"), person(home = true, availability = Availability.Unavailable))
        rig.settle()
        assertFalse(control.state.value.active)
        assertNull(control.state.value.pictureUrl)
    }

    @Test
    fun `initials are the first letters of the first two words`() {
        assertEquals("DS", PersonControl.initials("Dan Smith"))
        assertEquals("DS", PersonControl.initials("  dan   smith jr "))
        assertEquals("N", PersonControl.initials("Natali"))
        assertEquals("", PersonControl.initials("  "))
    }

    @Test
    fun `an id gets the same color every time, as on the web`() {
        val n = PersonControl.DEFAULT_COLORS.size
        assertEquals(PersonControl.colorIndex("ha:person.dan", n), PersonControl.colorIndex("ha:person.dan", n))
        // hash = ((('a' * 31) + 'b') * 31) + 'c' = 96354; 96354 % 6 = 0
        assertEquals(0, PersonControl.colorIndex("abc", 6))
        // the hash stays an unsigned 32 bit number, as `>>> 0` keeps it, so a long id does not go negative
        assertTrue(PersonControl.colorIndex("a-much-longer-person-id-than-the-others", n) in 0 until n)
    }
}
