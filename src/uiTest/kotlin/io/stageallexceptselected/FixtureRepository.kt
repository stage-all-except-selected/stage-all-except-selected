package io.stageallexceptselected

import java.io.File

/** One Git repository of the UI-test project, driven through the `git` CLI exactly as a user would. */
class FixtureRepository(val dir: File) {

    /** Back to the initial empty commit on `main`, with no other branch, merge or untracked file. */
    fun reset() {
        if (dir.resolve(".git/MERGE_HEAD").exists()) git("merge", "--abort")
        git("checkout", "--quiet", "--force", "main")
        git("reset", "--quiet", "--hard", git("rev-list", "--max-parents=0", "HEAD").trim())
        // Without -x, so the excluded .idea/ and the nested shared-config/ repository survive.
        git("clean", "--quiet", "-fd")
        git("for-each-ref", "--format=%(refname:short)", "refs/heads/").lines()
            .filter { it.isNotBlank() && it != "main" }
            .forEach { git("branch", "--quiet", "-D", it) }
    }

    fun write(path: String, content: String) {
        val file = dir.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    fun read(path: String): String = dir.resolve(path).readText()

    /** Writes and commits [files], so each one is tracked and unmodified. */
    fun commit(vararg files: Pair<String, String>) {
        files.forEach { (path, content) -> write(path, content) }
        git("add", "--", *files.map { it.first }.toTypedArray())
        git("commit", "--quiet", "--message=Add ${files.joinToString { it.first }}")
    }

    fun stage(vararg paths: String) {
        git("add", "--", *paths)
    }

    /** `git status --porcelain` as path to its two-letter `XY` code, for example `" M"` or `"M "`. */
    fun status(): Map<String, String> =
        git("status", "--porcelain=v1", "--untracked-files=all").lines()
            .filter { it.isNotBlank() }
            .associate { it.substring(3) to it.substring(0, 2) }

    fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed in $dir:\n$output" }
        return output
    }

    /** Like [git], for commands whose failure is the point, such as a conflicting merge. */
    fun gitAllowingFailure(vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
