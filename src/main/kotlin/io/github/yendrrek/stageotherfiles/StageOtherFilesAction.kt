package io.github.yendrrek.stageotherfiles

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import git4idea.index.GitStageTracker
import git4idea.index.actions.GitAddOperation
import git4idea.index.actions.performStageOperation
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.GitStageDataKeys
import git4idea.index.ui.NodeKind
import java.util.EnumSet

/**
 * Stages every unstaged file in the staging area except the selected ones.
 *
 * Only the staging tree provides [GitStageDataKeys.GIT_FILE_STATUS_NODES], so the action stays
 * hidden everywhere else, including when the staging area is off.
 */
class StageOtherFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = selection(e)
        if (project == null || !OtherFilesSelector.hasUnstagedOrUntracked(selected)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = others(project, selected).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val others = others(project, selection(e))
        if (others.isEmpty()) return
        // Git4Idea's own Stage path: saves documents, shows progress, refreshes and reports errors.
        performStageOperation(project, others, GitAddOperation)
    }

    private fun selection(e: AnActionEvent): List<GitFileStatusNode> =
        e.getData(GitStageDataKeys.GIT_FILE_STATUS_NODES)?.toList().orEmpty()

    // Read at click time as well as in update(), so staging reflects changes since the menu opened.
    private fun others(project: Project, selected: List<GitFileStatusNode>): List<GitFileStatusNode> =
        OtherFilesSelector.others(
            GitStageTracker.getInstance(project).state,
            OtherFilesSelector.selectedPaths(selected),
            OTHER_KINDS,
        )

    private companion object {
        // Modified and unversioned files, matching the toolbar's Stage All.
        val OTHER_KINDS: Set<NodeKind> = EnumSet.of(NodeKind.UNSTAGED, NodeKind.UNTRACKED)
    }
}
