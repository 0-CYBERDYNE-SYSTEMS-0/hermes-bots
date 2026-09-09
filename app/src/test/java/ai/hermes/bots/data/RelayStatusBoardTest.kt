package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5: relay observability state transitions (never latches; drain stamps freshness). */
class RelayStatusBoardTest {

    @Test
    fun `unknown connection has no entry until marked`() {
        val board = RelayStatusBoard()
        assertNull(board.states.value["c1"])
    }

    @Test
    fun `unsupported then supported clears the unsupported state`() {
        val board = RelayStatusBoard()
        board.markUnsupported("c1")
        assertEquals(RelaySupport.Unsupported, board.states.value["c1"]!!.support)
        board.markSupported("c1")
        assertEquals(RelaySupport.Supported, board.states.value["c1"]!!.support)
    }

    @Test
    fun `supported then unsupported downgrades`() {
        val board = RelayStatusBoard()
        board.markSupported("c1")
        board.markUnsupported("c1")
        assertEquals(RelaySupport.Unsupported, board.states.value["c1"]!!.support)
    }

    @Test
    fun `drain stamps timestamp and implies supported`() {
        val board = RelayStatusBoard()
        board.markDrained("c1", atMs = 1_000L)
        val status = board.states.value["c1"]!!
        assertEquals(RelaySupport.Supported, status.support)
        assertEquals(1_000L, status.lastDrainMs)
    }

    @Test
    fun `unsupported does not clear a previous drain timestamp`() {
        val board = RelayStatusBoard()
        board.markDrained("c1", atMs = 1_000L)
        board.markUnsupported("c1")
        val status = board.states.value["c1"]!!
        assertEquals(RelaySupport.Unsupported, status.support)
        assertEquals(1_000L, status.lastDrainMs)
    }

    @Test
    fun `reset after reconnect keeps drain freshness but returns to unknown`() {
        val board = RelayStatusBoard()
        board.markDrained("c1", atMs = 5_000L)
        board.reset("c1")
        val status = board.states.value["c1"]!!
        assertEquals(RelaySupport.Unknown, status.support)
        assertEquals(5_000L, status.lastDrainMs)
    }

    @Test
    fun `remove drops the connection row`() {
        val board = RelayStatusBoard()
        board.markSupported("c1")
        board.remove("c1")
        assertFalse(board.states.value.containsKey("c1"))
    }

    @Test
    fun `independent connections track independently`() {
        val board = RelayStatusBoard()
        board.markSupported("c1")
        board.markUnsupported("c2")
        assertTrue(board.states.value["c1"]!!.support == RelaySupport.Supported)
        assertTrue(board.states.value["c2"]!!.support == RelaySupport.Unsupported)
    }
}
