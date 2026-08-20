package li.songe.gkd.bypass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BypassUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun switchRow_has_one_event_source() {
        var checked by mutableStateOf(false)
        var calls = 0
        composeRule.setContent {
            BypassSwitchRow("主开关", "", checked, onCheckedChange = {
                calls++
                checked = it
            })
        }

        composeRule.onNodeWithTag("bypass-switch-主开关").performClick()

        composeRule.runOnIdle {
            assertEquals(1, calls)
            assertEquals(true, checked)
        }
    }

    @Test
    fun appRow_body_opens_detail_and_switch_only_toggles() {
        var opens = 0
        val toggles = mutableListOf<Boolean>()
        composeRule.setContent {
            BypassAppRow(
                appName = "测试应用",
                packageName = "fixture.app",
                icon = null,
                enabled = true,
                subtitle = "1 组",
                onClick = { opens++ },
                onToggle = { toggles += it },
            )
        }

        composeRule.onNodeWithTag("bypass-app-body-fixture.app").performClick()
        composeRule.onNodeWithTag("bypass-app-switch-fixture.app").performClick()

        composeRule.runOnIdle {
            assertEquals(1, opens)
            assertEquals(listOf(false), toggles)
        }
    }
}
