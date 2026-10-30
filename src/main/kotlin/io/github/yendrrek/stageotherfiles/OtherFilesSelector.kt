package io.github.yendrrek.stageotherfiles

import com.intellij.openapi.vcs.FilePath
import git4idea.index.GitStageTracker
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.NodeKind

/** Decides which files are "the others", with no IDE calls so it can be tested directly. */
internal object OtherFilesSelector {

    /** True when [selected] holds a file the action can leave out. */
    fun hasUnstagedOrUntracked(selected: Collection<GitFileStatusNode>): Boolean =
        selected.any { it.kind == NodeKind.UNSTAGED || it.kind == NodeKind.UNTRACKED }

    /** Every selected path, whatever node it was picked from. */
    fun selectedPaths(selected: Collection<GitFileStatusNode>): Set<FilePath> =
        selected.mapTo(HashSet()) { it.filePath }

    /**
     * Nodes of the given [kinds], across all roots of [state], whose path is not [excluded].
     *
     * Mirrors git4idea's `forEachStatus` in `GitStageTree.kt`, which is Kotlin-`internal`.
     */
    fun others(
        state: GitStageTracker.State,
        excluded: Set<FilePath>,
        kinds: Set<NodeKind>,
    ): List<GitFileStatusNode> = buildList {
        for ((root, rootState) in state.rootStates) {
            for (status in rootState.statuses.values) {
                if (status.path in excluded) continue
                for (kind in kinds) {
                    if (kind.`is`(status)) add(GitFileStatusNode(root, status, kind))
                }
            }
        }
    }
}
