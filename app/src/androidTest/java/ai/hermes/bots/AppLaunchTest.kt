package ai.hermes.bots

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(composeRule)

    @Test
    fun launchesFleetAndNavigatesToSettings() {
        composeRule.onNodeWithContentDescription("Fleet").assertIsSelected()

        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }
}
