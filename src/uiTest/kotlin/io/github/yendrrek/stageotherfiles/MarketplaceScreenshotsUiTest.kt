package io.github.yendrrek.stageotherfiles

import com.intellij.remoterobot.utils.waitFor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Duration

/**
 * Captures the JetBrains Marketplace screenshots in the Islands Light and Islands Dark themes: the
 * context menu with **Stage Other Files** under the pointer, and the staging area after it ran.
 *
 * Runs only with `-PuiTestScreenshotDir=<dir>`, against `runIdeForUiTestsLatest -PuiTestUiScale=2`
 * on a 2880x1800 Xvfb screen. Each image is the frame below its main toolbar, 1280 x 800 logical
 * pixels, the size Marketplace recommends.
 */
class MarketplaceScreenshotsUiTest {

    @get:Rule
    val sandbox = SandboxIdeRule()

    private val outputDir: File? = System.getProperty("uiTest.screenshotDir")?.let(::File)

    @Test
    fun `capture the Marketplace screenshots`() {
        assumeTrue("set -PuiTestScreenshotDir to capture screenshots", outputDir != null)
        val output = outputDir!!.apply { mkdirs() }
        val originalTheme = sandbox.ide.setTheme("Islands Light")
        try {
            for ((theme, suffix) in listOf("Islands Light" to "light", "Islands Dark" to "dark")) {
                sandbox.ide.setTheme(theme)
                capture(output, suffix)
            }
        } finally {
            sandbox.ide.setTheme(originalTheme)
        }
    }

    private fun capture(output: File, suffix: String) {
        val root = sandbox.root
        root.reset()
        sandbox.ide.closeAllEditors()
        root.commit(*COMMITTED.toList().toTypedArray())
        CHANGED.forEach { (path, content) -> root.write(path, content) }
        sandbox.awaitPanelMatchesGit()
        sandbox.ide.sizeFrameBelowToolbar(WIDTH, HEIGHT)
        sandbox.ide.openFile(KEPT)
        sandbox.ide.showCommitToolWindow()
        sandbox.awaitPanelMatchesGit()

        val name = KEPT.substringAfterLast('/')
        sandbox.panel.select("Unstaged", name)
        val menu = sandbox.panel.rightClickRowEnd("Unstaged", name)
        menu.item(ITEM).moveMouse()
        settle()
        output.resolve("context-menu-$suffix.png").writeBytes(sandbox.ide.captureBelowToolbar(WIDTH, HEIGHT))

        menu.click(ITEM)
        val staged = CHANGED.keys - KEPT
        waitFor(Duration.ofSeconds(30), Duration.ofMillis(500), errorMessageSupplier = { "git status is ${root.status()}" }) {
            root.status() == staged.associateWith { "M " } + (KEPT to " M")
        }
        sandbox.awaitPanelMatchesGit()
        sandbox.panel.movePointerAway()
        settle()
        output.resolve("result-$suffix.png").writeBytes(sandbox.ide.captureBelowToolbar(WIDTH, HEIGHT))
    }

    /** Lets hover highlighting and the tree's repaint finish before a capture. */
    private fun settle() {
        Thread.sleep(1_000)
    }

    private companion object {
        const val ITEM = "Stage Other Files"
        const val WIDTH = 1280
        const val HEIGHT = 800

        /** The file the user keeps out of the commit: a local logging tweak. */
        const val KEPT = "config/application.yml"

        val COMMITTED = mapOf(
            "README.md" to "# Inventory Service\n\nTracks stock levels across warehouses.\n",
            "CHANGELOG.md" to "# Changelog\n\n## [Unreleased]\n",
            KEPT to """
                |server:
                |  port: 8080
                |
                |database:
                |  url: jdbc:postgresql://localhost:5432/inventory
                |  pool-size: 10
                |
                |logging:
                |  level: INFO
                |""".trimMargin(),
            "src/main/kotlin/inventory/StockLevel.kt" to "package inventory\n\nclass StockLevel\n",
            "src/main/kotlin/inventory/Warehouse.kt" to "package inventory\n\nclass Warehouse\n",
            "src/test/kotlin/inventory/WarehouseTest.kt" to "package inventory\n\nclass WarehouseTest\n",
        )

        val CHANGED = mapOf(
            "README.md" to "# Inventory Service\n\nTracks stock levels and reorder points across warehouses.\n",
            "CHANGELOG.md" to "# Changelog\n\n## [Unreleased]\n\n- Reorder points per warehouse.\n",
            KEPT to COMMITTED.getValue(KEPT).replace("level: INFO", "level: DEBUG"),
            "src/main/kotlin/inventory/StockLevel.kt" to "package inventory\n\nclass StockLevel(val reorderPoint: Int)\n",
            "src/main/kotlin/inventory/Warehouse.kt" to "package inventory\n\nclass Warehouse(val id: String)\n",
            "src/test/kotlin/inventory/WarehouseTest.kt" to "package inventory\n\nclass WarehouseTest {\n}\n",
        )
    }
}
