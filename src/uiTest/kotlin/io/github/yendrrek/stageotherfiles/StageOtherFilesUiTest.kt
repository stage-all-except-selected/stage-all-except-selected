package io.github.yendrrek.stageotherfiles

import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration

/**
 * The plan's manual checks, end to end: files are changed with the `git` CLI, the staging area is
 * driven with real clicks, and the result is read back from `git status` and from the panel.
 */
class StageOtherFilesUiTest {

    @get:Rule
    val sandbox = SandboxIdeRule()

    private val root get() = sandbox.root
    private val second get() = sandbox.second
    private val panel get() = sandbox.panel

    @Test
    fun `the item sits directly below Stage`() {
        modify(root, "a.txt", "b.txt")

        val menu = panel.rightClick(UNSTAGED, "a.txt")
        val texts = menu.texts()
        menu.close()

        assertEquals(texts.indexOf("Stage") + 1, texts.indexOf(ITEM))
    }

    @Test
    fun `right-clicking one unstaged file stages every other one`() {
        modify(root, "a.txt", "b.txt", "c.txt")

        panel.select(UNSTAGED, "a.txt")
        panel.rightClick(UNSTAGED, "a.txt").click(ITEM)

        awaitStatus(root, "a.txt" to " M", "b.txt" to "M ", "c.txt" to "M ")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `every file of a multiple selection stays unstaged`() {
        modify(root, "a.txt", "b.txt", "c.txt", "d.txt", "e.txt")

        panel.select(UNSTAGED, "a.txt", "c.txt", "e.txt")
        panel.rightClick(UNSTAGED, "e.txt").click(ITEM)

        awaitStatus(root, "a.txt" to " M", "b.txt" to "M ", "c.txt" to " M", "d.txt" to "M ", "e.txt" to " M")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `no file under a selected folder is staged`() {
        modify(root, "folder/in-folder-1.txt", "folder/in-folder-2.txt", "outside.txt")

        panel.select(UNSTAGED, "folder")
        panel.rightClick(UNSTAGED, "folder").click(ITEM)

        awaitStatus(root, "folder/in-folder-1.txt" to " M", "folder/in-folder-2.txt" to " M", "outside.txt" to "M ")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `a selected partially staged file keeps its unstaged changes`() {
        root.commit("partial.txt" to "first\nsecond\n", "other.txt" to "other\n")
        root.write("partial.txt", "first staged\nsecond\n")
        root.stage("partial.txt")
        root.write("partial.txt", "first staged\nsecond unstaged\n")
        root.write("other.txt", "other changed\n")
        sandbox.awaitPanelMatchesGit()
        val stagedBefore = root.git("diff", "--cached", "--", "partial.txt")

        panel.select(UNSTAGED, "partial.txt")
        panel.rightClick(UNSTAGED, "partial.txt").click(ITEM)

        awaitStatus(root, "partial.txt" to "MM", "other.txt" to "M ")
        assertEquals(stagedBefore, root.git("diff", "--cached", "--", "partial.txt"))
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `a conflicted file is not staged and stays conflicted`() {
        root.commit("conflict.txt" to "base\n", "a.txt" to "a\n", "b.txt" to "b\n")
        root.git("checkout", "--quiet", "-b", "theirs")
        root.commit("conflict.txt" to "theirs\n")
        root.git("checkout", "--quiet", "main")
        root.commit("conflict.txt" to "ours\n")
        root.gitAllowingFailure("merge", "theirs")
        root.write("a.txt", "a changed\n")
        root.write("b.txt", "b changed\n")
        sandbox.awaitPanelMatchesGit()

        panel.select(UNSTAGED, "a.txt")
        panel.rightClick(UNSTAGED, "a.txt").click(ITEM)

        awaitStatus(root, "conflict.txt" to "UU", "a.txt" to " M", "b.txt" to "M ")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `unversioned files are left alone`() {
        modify(root, "a.txt", "b.txt")
        root.write("new.txt", "new\n")
        sandbox.awaitPanelMatchesGit()

        panel.select(UNSTAGED, "a.txt")
        panel.rightClick(UNSTAGED, "a.txt").click(ITEM)

        awaitStatus(root, "a.txt" to " M", "b.txt" to "M ", "new.txt" to "??")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `files in both repositories are staged`() {
        modify(root, "a.txt", "b.txt")
        modify(second, "in-second-1.txt", "in-second-2.txt")

        panel.select(UNSTAGED, "a.txt")
        panel.rightClick(UNSTAGED, "a.txt").click(ITEM)

        awaitStatus(root, "a.txt" to " M", "b.txt" to "M ")
        awaitStatus(second, "in-second-1.txt" to "M ", "in-second-2.txt" to "M ")
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `an unsaved editor change in another file is saved, then staged`() {
        modify(root, "a.txt")
        root.commit("edited.txt" to "on disk\n")
        sandbox.ide.editWithoutSaving("edited.txt", "typed in the editor\n")
        // Not compared with git: the staging area may already list the unsaved edit.
        assertTrue(sandbox.ide.hasUnsavedDocuments())

        panel.select(UNSTAGED, "a.txt")
        panel.rightClick(UNSTAGED, "a.txt").click(ITEM)

        awaitStatus(root, "a.txt" to " M", "edited.txt" to "M ")
        assertEquals("typed in the editor\n", root.read("edited.txt"))
        assertFalse(sandbox.ide.hasUnsavedDocuments())
        sandbox.awaitPanelMatchesGit()
    }

    @Test
    fun `the item is hidden for a staged file`() {
        modify(root, "a.txt", "b.txt")
        root.stage("a.txt")
        sandbox.awaitPanelMatchesGit()

        panel.select(STAGED, "a.txt")
        val menu = panel.rightClick(STAGED, "a.txt")
        val texts = menu.texts()
        menu.close()

        assertFalse("menu: $texts", ITEM in texts)
    }

    @Test
    fun `the item is disabled when the selected file is the only unstaged one`() {
        modify(root, "a.txt")

        panel.select(UNSTAGED, "a.txt")
        val menu = panel.rightClick(UNSTAGED, "a.txt")
        val enabled = menu.isEnabled(ITEM)
        menu.close()

        assertFalse(enabled)
    }

    @Test
    fun `the item never appears with the staging area off`() {
        modify(root, "a.txt", "b.txt")
        sandbox.ide.setStagingAreaEnabled(false)
        try {
            sandbox.ide.showCommitToolWindow()
            val changes = sandbox.robot.find(
                JTreeFixture::class.java,
                byXpath("//div[@class='LocalChangesListView' or @class='ChangesListView']"),
                Duration.ofSeconds(30),
            )
            waitFor(Duration.ofSeconds(30)) { changes.collectRows().any { it.startsWith("a.txt") } }

            changes.rightClickRowWithText("a.txt", false)
            val menu = ContextMenu(sandbox.robot)
            val texts = menu.texts()
            menu.close()

            assertTrue("menu: $texts", texts.isNotEmpty())
            assertFalse("menu: $texts", ITEM in texts)
        } finally {
            sandbox.ide.setStagingAreaEnabled(true)
        }
    }

    /** Commits [paths] and then changes each one, leaving them all unstaged, and waits for the panel. */
    private fun modify(repository: FixtureRepository, vararg paths: String) {
        repository.commit(*paths.map { it to "original\n" }.toTypedArray())
        paths.forEach { repository.write(it, "changed\n") }
        sandbox.awaitPanelMatchesGit()
    }

    private fun awaitStatus(repository: FixtureRepository, vararg expected: Pair<String, String>) {
        waitFor(Duration.ofSeconds(30), Duration.ofMillis(500), errorMessageSupplier = { "git status is ${repository.status()}, expected ${expected.toMap()}" }) {
            repository.status() == expected.toMap()
        }
    }

    private companion object {
        const val ITEM = "Stage Other Files"
        const val STAGED = "Staged"
        const val UNSTAGED = "Unstaged"
    }
}
