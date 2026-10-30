package io.github.yendrrek.stageotherfiles

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import git4idea.index.GitFileStatus
import git4idea.index.GitStageTracker
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.NodeKind
import java.util.EnumSet

class OtherFilesSelectorTest : BasePlatformTestCase() {

    private val root = LightVirtualFile("repo")
    private val otherRoot = LightVirtualFile("other-repo")

    fun `test one selected unstaged file among five leaves the other four`() {
        val statuses = (1..5).map { unstaged(root, "file$it.txt") }
        val state = state(root to statuses)

        val others = others(state, selected = listOf(node(root, statuses[0], NodeKind.UNSTAGED)))

        assertSameElements(paths(others), paths(statuses.drop(1)))
        assertTrue(others.all { it.kind == NodeKind.UNSTAGED && it.root == root })
    }

    fun `test a selected partially staged file is never returned`() {
        val partial = status(root, 'M', 'M', "partial.txt")
        val other = unstaged(root, "other.txt")
        val state = state(root to listOf(partial, other))

        val others = others(state, selected = listOf(node(root, partial, NodeKind.UNSTAGED)))

        assertSameElements(paths(others), paths(listOf(other)))
    }

    fun `test conflicted and staged-only files are never returned`() {
        val selected = unstaged(root, "selected.txt")
        val other = unstaged(root, "other.txt")
        val statuses = listOf(
            selected,
            other,
            status(root, 'M', ' ', "staged.txt"),
            status(root, 'U', 'U', "conflicted.txt"),
        )
        val state = state(root to statuses)

        val others = others(state, selected = listOf(node(root, selected, NodeKind.UNSTAGED)))

        assertSameElements(paths(others), paths(listOf(other)))
    }

    fun `test unversioned files are returned only when UNTRACKED is in the kinds`() {
        val selected = unstaged(root, "selected.txt")
        val other = unstaged(root, "other.txt")
        val unversioned = status(root, '?', '?', "new.txt")
        val state = state(root to listOf(selected, other, unversioned))
        val selection = listOf(node(root, selected, NodeKind.UNSTAGED))

        val trackedOnly = others(state, selection)
        val withUnversioned = others(state, selection, EnumSet.of(NodeKind.UNSTAGED, NodeKind.UNTRACKED))

        assertSameElements(paths(trackedOnly), paths(listOf(other)))
        assertSameElements(paths(withUnversioned), paths(listOf(other, unversioned)))
        assertEquals(NodeKind.UNTRACKED, withUnversioned.single { it.filePath == unversioned.path }.kind)
    }

    fun `test files from every root are returned`() {
        val selected = unstaged(root, "selected.txt")
        val inFirstRoot = unstaged(root, "first.txt")
        val inSecondRoot = unstaged(otherRoot, "second.txt")
        val state = state(root to listOf(selected, inFirstRoot), otherRoot to listOf(inSecondRoot))

        val others = others(state, selected = listOf(node(root, selected, NodeKind.UNSTAGED)))

        assertSameElements(paths(others), paths(listOf(inFirstRoot, inSecondRoot)))
        assertEquals(otherRoot, others.single { it.filePath == inSecondRoot.path }.root)
    }

    fun `test everything selected returns nothing`() {
        val statuses = (1..3).map { unstaged(root, "file$it.txt") }
        val state = state(root to statuses)

        val others = others(state, selected = statuses.map { node(root, it, NodeKind.UNSTAGED) })

        assertEmpty(others)
    }

    fun `test a selected staged entry excludes the same file's unstaged entry`() {
        val partial = status(root, 'M', 'M', "partial.txt")
        val other = unstaged(root, "other.txt")
        val state = state(root to listOf(partial, other))

        val others = others(state, selected = listOf(node(root, partial, NodeKind.STAGED)))

        assertSameElements(paths(others), paths(listOf(other)))
    }

    fun `test hasUnstagedOrUntracked accepts only unstaged and unversioned selections`() {
        val modified = status(root, 'M', 'M', "modified.txt")
        val unversioned = status(root, '?', '?', "new.txt")
        val conflicted = status(root, 'U', 'U', "conflicted.txt")

        assertTrue(OtherFilesSelector.hasUnstagedOrUntracked(listOf(node(root, modified, NodeKind.UNSTAGED))))
        assertTrue(OtherFilesSelector.hasUnstagedOrUntracked(listOf(node(root, unversioned, NodeKind.UNTRACKED))))
        assertFalse(OtherFilesSelector.hasUnstagedOrUntracked(listOf(node(root, modified, NodeKind.STAGED))))
        assertFalse(OtherFilesSelector.hasUnstagedOrUntracked(listOf(node(root, conflicted, NodeKind.CONFLICTED))))
        assertFalse(OtherFilesSelector.hasUnstagedOrUntracked(emptyList()))
    }

    private fun others(
        state: GitStageTracker.State,
        selected: List<GitFileStatusNode>,
        kinds: Set<NodeKind> = EnumSet.of(NodeKind.UNSTAGED),
    ): List<GitFileStatusNode> =
        OtherFilesSelector.others(state, OtherFilesSelector.selectedPaths(selected), kinds)

    private fun unstaged(root: VirtualFile, name: String) = status(root, ' ', 'M', name)

    // origPath passed explicitly: omitting it calls git4idea's synthetic default-argument constructor.
    private fun status(root: VirtualFile, index: Char, workTree: Char, name: String) =
        GitFileStatus(index, workTree, LocalFilePath("/${root.name}/$name", false), null)

    private fun node(root: VirtualFile, status: GitFileStatus, kind: NodeKind) = GitFileStatusNode(root, status, kind)

    private fun state(vararg roots: Pair<VirtualFile, List<GitFileStatus>>) = GitStageTracker.State(
        roots.associate { (root, statuses) ->
            root to GitStageTracker.RootState(root, true, statuses.associateBy { it.path })
        },
    )

    private fun paths(nodes: List<GitFileStatusNode>): List<FilePath> = nodes.map { it.filePath }

    @JvmName("statusPaths")
    private fun paths(statuses: List<GitFileStatus>): List<FilePath> = statuses.map { it.path }
}
