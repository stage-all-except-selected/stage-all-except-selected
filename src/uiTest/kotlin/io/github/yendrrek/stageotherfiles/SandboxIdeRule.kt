package io.github.yendrrek.stageotherfiles

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.Keyboard
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Connects to the `runIdeForUiTests` IDE and gives every test the same starting point: the staging
 * area enabled and shown, no editor open, and both repositories back at their empty initial commit.
 *
 * On failure it saves a screenshot to `build/reports/uiTest/<test>.png`. A test that passes its own
 * assertions still fails if the plugin logged an error to `idea.log` while it ran.
 */
class SandboxIdeRule : TestRule {

    val robot = RemoteRobot(System.getProperty("uiTest.robotUrl") ?: "http://127.0.0.1:8082")
    val ide = SandboxIde(robot)
    val panel = StagingAreaPanel(robot)

    private val projectDir = File(
        System.getProperty("uiTest.projectDir") ?: error("uiTest.projectDir is not set; run through ./gradlew uiTest"),
    )
    val root = FixtureRepository(projectDir)
    val nested = FixtureRepository(projectDir.resolve("shared-config"))

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            resetIde()
            val logOffset = ide.ideaLogLength()
            try {
                base.evaluate()
            } catch (failure: Throwable) {
                saveScreenshot(description)
                throw failure
            }
            val errors = pluginErrors(ide.ideaLogFrom(logOffset))
            if (errors.isNotEmpty()) {
                throw AssertionError("the plugin logged ${errors.size} error(s):\n\n" + errors.joinToString("\n\n"))
            }
        }
    }

    /** Refreshes the IDE from disk and waits until the staging area shows what `git status` says. */
    fun awaitPanelMatchesGit() {
        ide.refreshFromDisk()
        panel.waitUntilShows(expectedPanel(root, nested))
    }

    private fun resetIde() {
        // Whatever a failed test left open would swallow this test's clicks.
        repeat(3) { Keyboard(robot).escape() }
        ide.raiseFrame()
        ide.closeAllEditors()
        ide.setStagingAreaEnabled(true)
        root.reset()
        nested.reset()
        ide.showCommitToolWindow()
        awaitPanelMatchesGit()
    }

    private fun saveScreenshot(description: Description) {
        runCatching {
            File("build/reports/uiTest").apply { mkdirs() }
                .resolve("${description.methodName}.png")
                .writeBytes(ide.screenshot())
        }
    }

    private companion object {
        /**
         * The staging area's sections as `git status` describes them, by file name. Mirrors how
         * git4idea sorts a status: conflicts first, then the index and work tree columns. Untracked
         * files are listed under "Unstaged".
         */
        fun expectedPanel(vararg repositories: FixtureRepository): Map<String, Set<String>> {
            val sections = mutableMapOf<String, MutableSet<String>>()
            fun add(section: String, path: String) {
                sections.getOrPut(section) { mutableSetOf() } += path.substringAfterLast('/')
            }
            for ((path, code) in repositories.flatMap { it.status().entries }) {
                when {
                    code in CONFLICT_CODES -> add("Unmerged", path)
                    code == "??" -> add("Unstaged", path)
                    else -> {
                        if (code[0] != ' ') add("Staged", path)
                        if (code[1] != ' ') add("Unstaged", path)
                    }
                }
            }
            return sections
        }

        val CONFLICT_CODES = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

        /**
         * Error entries in [log] caused by this plugin: a `SEVERE` or `ERROR` entry whose trace runs
         * through the plugin's package or that the platform blames on it. The "Last Action:" line
         * names whatever was clicked last, so it does not count.
         */
        fun pluginErrors(log: String): List<String> =
            log.split(Regex("""(?m)^(?=\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})"""))
                .filter { entry -> entry.contains(" SEVERE - ") || entry.contains(" ERROR - ") }
                .filter { entry ->
                    entry.lines().filterNot { "Last Action:" in it }.any {
                        "io.github.yendrrek.stageotherfiles" in it || "Plugin to blame: Stage Other Files" in it
                    }
                }
    }
}
