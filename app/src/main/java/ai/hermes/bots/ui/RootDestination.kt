package ai.hermes.bots.ui

/** The four destinations that form the app shell. Detail routes are deliberately excluded. */
enum class RootDestination(val route: String, val title: String) {
    FLEET("fleet", "Fleet"),
    PULSE("pulse", "Pulse"),
    CHATS("chats", "Chats"),
    SETTINGS("settings", "Settings"),
    ;

    companion object {
        val values: List<RootDestination> = listOf(FLEET, PULSE, CHATS, SETTINGS)

        fun fromRoute(route: String?): RootDestination? =
            values.firstOrNull { it.route == route }

        fun isRootRoute(route: String?): Boolean = fromRoute(route) != null

        /** A route may contain arguments; only exact shell routes are top-level destinations. */
        fun isDetailRoute(route: String?): Boolean = route != null && !isRootRoute(route)
    }
}
