package io.stageallexceptselected

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.Keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.Point
import java.awt.event.KeyEvent
import java.time.Duration

/**
 * The staging area's tree in the Commit tool window, read and driven the way a user sees it.
 *
 * Rows are addressed by section ("Staged", "Unstaged", "Unmerged") and the node's name. File names
 * are unique across both repositories in every test, so a name identifies a file. Untracked files
 * are listed in red under "Unstaged", not in a section of their own.
 */
class StagingAreaPanel(private val robot: RemoteRobot) {

    private val tree: JTreeFixture
        get() = robot.find(JTreeFixture::class.java, byXpath("//div[@class='MyChangesTree']"), TIMEOUT)

    /** Section name to the file names shown under it, with every node expanded. */
    fun contents(): Map<String, Set<String>> {
        val paths = tree.expandAll().collectExpandedPaths().map { it.path }
        return paths
            .filter { path -> path.size > 1 && paths.none { it.size > path.size && it.subList(0, path.size) == path } }
            .groupBy({ sectionOf(it) }, { nodeName(it.last()) })
            .mapValues { it.value.toSet() }
    }

    /** Waits until the panel shows [expected], for example after a refresh or a stage operation. */
    fun waitUntilShows(expected: Map<String, Set<String>>) {
        waitFor(TIMEOUT, Duration.ofMillis(500), errorMessageSupplier = { "staging area shows ${contents()}, expected $expected" }) {
            contents() == expected
        }
    }

    /** Selects the [names] in [section], holding Ctrl from the second one on, as a user would. */
    fun select(section: String, vararg names: String) {
        tree.clickRow(row(section, names.first()))
        if (names.size > 1) {
            Keyboard(robot).pressing(KeyEvent.VK_CONTROL) {
                names.drop(1).forEach { tree.clickRow(row(section, it)) }
            }
        }
    }

    /** Right-clicks [name] in [section] without changing a selection that already includes it. */
    fun rightClick(section: String, name: String): ContextMenu {
        tree.rightClickRow(row(section, name))
        return ContextMenu(robot)
    }

    /**
     * Right-clicks the far end of [name]'s row in [section], which selects it like a click on its
     * text, so the menu opens beside the tree instead of over its other rows.
     */
    fun rightClickRowEnd(section: String, name: String): ContextMenu {
        val tree = tree
        val point = tree.callJs<Point>(
            """
            var bounds = component.getRowBounds(${row(section, name)})
            var visible = component.getVisibleRect()
            new java.awt.Point(visible.x + visible.width - 24, bounds.y + Math.floor(bounds.height / 2))
            """,
            true,
        )
        tree.rightClick(point)
        return ContextMenu(robot)
    }

    /** Parks the pointer in the empty space under the tree's rows, where it highlights nothing. */
    fun movePointerAway() {
        val tree = tree
        val point = tree.callJs<Point>(
            """
            var visible = component.getVisibleRect()
            new java.awt.Point(visible.x + 24, visible.y + visible.height - 24)
            """,
            true,
        )
        tree.moveMouse(point)
    }

    private fun row(section: String, name: String): Int {
        val paths = tree.expandAll().collectExpandedPaths()
        return paths.firstOrNull { sectionOf(it.path) == section && nodeName(it.path.last()) == name }?.row
            ?: error("no '$name' under '$section' in ${paths.map { it.path }}")
    }

    private fun sectionOf(path: List<String>): String = nodeName(path.first())

    /**
     * A node's name without what the tree renders after it: a file count ("dir 2 files") or a
     * conflict state ("conflict.txt both modified"). Names in the tests contain no spaces.
     */
    private fun nodeName(text: String): String = text.substringBefore(" ").trim()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/** An open context menu. */
class ContextMenu(private val robot: RemoteRobot) {

    init {
        waitFor(Duration.ofSeconds(10)) { items().isNotEmpty() }
    }

    /** The menu's item texts, top to bottom. */
    fun texts(): List<String> = items().map { it.callJs("component.getText()") }

    fun item(text: String): ComponentFixture =
        items().firstOrNull { it.callJs<String>("component.getText()") == text }
            ?: error("no '$text' in context menu ${texts()}")

    fun isEnabled(text: String): Boolean = item(text).callJs("component.isEnabled()")

    fun click(text: String) {
        item(text).click()
    }

    fun close() {
        Keyboard(robot).escape()
        waitFor(Duration.ofSeconds(10)) { items().isEmpty() }
    }

    private fun items(): List<ComponentFixture> =
        robot.findAll(ComponentFixture::class.java, byXpath("//div[@class='ActionMenuItem']"))
}
