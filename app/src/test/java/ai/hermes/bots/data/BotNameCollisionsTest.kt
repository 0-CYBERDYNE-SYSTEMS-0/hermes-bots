package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BotNameCollisionsTest {

    private fun row(connectionId: String, name: String) = BotRow(
        connectionId = connectionId,
        name = name,
        displayName = null,
        description = null,
        model = null,
        provider = null,
        skillCount = 0,
        isDefault = false,
        hasAvatar = false,
        sectionId = null,
        hidden = false,
        lastPreview = null,
        lastActiveMs = null,
        workerActiveMs = null,
        canonicalSessionId = null,
        canonicalRootTitle = null,
        uiMetaRevisions = emptyMap(),
    )

    @Test
    fun `names on more than one connection collide`() {
        val rows = listOf(
            row("gw1", "scout"),
            row("gw1", "default"),
            row("gw2", "default"),
            row("gw3", "default"),
            row("gw2", " helper "),
        )
        assertEquals(setOf("default"), BotNameCollisions.compute(rows))
    }

    @Test
    fun `same name twice on one connection does not collide`() {
        val rows = listOf(row("gw1", "echo"), row("gw1", "echo"))
        assertEquals(emptySet<String>(), BotNameCollisions.compute(rows))
    }

    @Test
    fun `empty roster has no collisions`() {
        assertEquals(emptySet<String>(), BotNameCollisions.compute(emptyList()))
    }
}
