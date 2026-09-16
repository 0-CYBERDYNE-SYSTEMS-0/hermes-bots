package ai.hermes.bots.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDestinationTest {
    @Test
    fun `maps shell routes to stable destinations`() {
        assertEquals(RootDestination.FLEET, RootDestination.fromRoute("fleet"))
        assertEquals(RootDestination.PULSE, RootDestination.fromRoute("pulse"))
        assertEquals(RootDestination.CHATS, RootDestination.fromRoute("chats"))
        assertEquals(RootDestination.SETTINGS, RootDestination.fromRoute("settings"))
        assertNull(RootDestination.fromRoute("chat/conn/bot"))
    }

    @Test
    fun `only exact shell routes show the root bar`() {
        assertTrue(RootDestination.isRootRoute("pulse"))
        assertFalse(RootDestination.isRootRoute("notifications"))
        assertTrue(RootDestination.isDetailRoute("chat/conn/bot"))
        assertFalse(RootDestination.isDetailRoute("settings"))
    }
}
