package io.stageallexceptselected

import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration

/**
 * Disabling the plugin in Settings | Plugins must not ask for a restart: the platform unloads it,
 * its action disappears, and enabling it again brings the action back.
 */
class PluginUnloadUiTest {

    @get:Rule
    val sandbox = SandboxIdeRule()

    @Test
    fun `the plugin unloads without a restart and loads back`() {
        stageAllExceptSelected()

        var unloaded = false
        try {
            val outcome = sandbox.ide.unloadPlugin()
            unloaded = outcome.startsWith("unloaded")

            // Before JDK 24, every AWT component pins the loader on the stack when it was created
            // through its AccessControlContext, which no plugin can avoid.
            val expected = if (sandbox.ide.jdkFeature() >= 24) "unloaded:collected:actionGone" else "unloaded"
            assertTrue("unload outcome: $outcome", outcome.startsWith(expected))
            assertTrue("unload outcome: $outcome", outcome.endsWith(":actionGone"))
        } finally {
            if (unloaded) assertTrue("the plugin did not load back", sandbox.ide.reloadPlugin())
        }

        stageAllExceptSelected()
    }

    /** Uses the action end to end, so the unload runs after the plugin's code has run. */
    private fun stageAllExceptSelected() {
        sandbox.root.reset()
        sandbox.root.commit("a.txt" to "original\n", "b.txt" to "original\n")
        sandbox.root.write("a.txt", "changed\n")
        sandbox.root.write("b.txt", "changed\n")
        sandbox.awaitPanelMatchesGit()

        sandbox.panel.select("Unstaged", "a.txt")
        sandbox.panel.rightClick("Unstaged", "a.txt").click("Stage All Except Selected")

        val expected = mapOf("a.txt" to " M", "b.txt" to "M ")
        waitFor(Duration.ofSeconds(30), Duration.ofMillis(500), errorMessageSupplier = { "git status is ${sandbox.root.status()}" }) {
            sandbox.root.status() == expected
        }
    }
}
