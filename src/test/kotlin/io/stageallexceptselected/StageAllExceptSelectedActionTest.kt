package io.stageallexceptselected

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import git4idea.index.GitFileStatus
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.GitStageDataKeys
import git4idea.index.ui.NodeKind

/**
 * Registration and presentation of [StageAllExceptSelectedAction]. The light fixture has no Git
 * repository, so the stage tracker's state is empty and nothing is ever staged here.
 */
class StageAllExceptSelectedActionTest : BasePlatformTestCase() {

    private val root = LightVirtualFile("repo")

    fun `test the action is registered with its bundle text`() {
        val action = ActionManager.getInstance().getAction(ACTION_ID)

        assertInstanceOf(action, StageAllExceptSelectedAction::class.java)
        assertEquals("Stage All Except Selected", action.templatePresentation.text)
        assertEquals("Stage every modified or unversioned file except the selected ones", action.templatePresentation.description)
    }

    fun `test the action follows Stage in the staging tree's context menu`() {
        val actionManager = ActionManager.getInstance()
        val menu = actionManager.getAction("Git.Stage.Tree.Menu") as DefaultActionGroup
        val ids = menu.childActionsOrStubs.map { actionManager.getId(it) }

        assertEquals(ids.indexOf("Git.Stage.Add") + 1, ids.indexOf(ACTION_ID))
    }

    fun `test the action is hidden outside the staging tree`() {
        val event = update(SimpleDataContext.getProjectContext(project))

        assertFalse(event.presentation.isVisible)
    }

    fun `test the action is hidden for a selection with only staged files`() {
        val event = update(selection(node(status(index = 'M', workTree = ' ', "staged.txt"), NodeKind.STAGED)))

        assertFalse(event.presentation.isVisible)
    }

    fun `test the action is hidden for a selection with only conflicted files`() {
        val event = update(selection(node(status(index = 'U', workTree = 'U', "conflicted.txt"), NodeKind.CONFLICTED)))

        assertFalse(event.presentation.isVisible)
    }

    fun `test the action is shown but disabled when no other file would be staged`() {
        val event = update(selection(node(status(index = ' ', workTree = 'M', "selected.txt"), NodeKind.UNSTAGED)))

        assertTrue(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
    }

    fun `test an unversioned selection shows the action`() {
        val event = update(selection(node(status(index = '?', workTree = '?', "new.txt"), NodeKind.UNTRACKED)))

        assertTrue(event.presentation.isVisible)
    }

    private fun update(context: DataContext): AnActionEvent {
        val action = StageAllExceptSelectedAction()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event
    }

    private fun selection(vararg nodes: GitFileStatusNode) = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(GitStageDataKeys.GIT_FILE_STATUS_NODES, nodes.asIterable())
        .build()

    // origPath passed explicitly: omitting it calls git4idea's synthetic default-argument constructor.
    private fun status(index: Char, workTree: Char, name: String) =
        GitFileStatus(index, workTree, LocalFilePath("/${root.name}/$name", false), null)

    private fun node(status: GitFileStatus, kind: NodeKind) = GitFileStatusNode(root, status, kind)

    private companion object {
        const val ACTION_ID = "StageAllExceptSelected.Stage"
    }
}
