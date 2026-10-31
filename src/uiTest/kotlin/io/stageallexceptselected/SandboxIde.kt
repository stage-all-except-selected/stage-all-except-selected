package io.stageallexceptselected

import com.intellij.remoterobot.RemoteRobot

/**
 * IDE-side state the tests set up or read through robot-server's Rhino scripts.
 *
 * Rhino quirks every script here respects: a loop-body `const` is bound once, so loops use `var`;
 * `robot` and `log` are predefined names; `Class.forName` without a loader resolves against
 * robot-server's own plugin, which cannot see git4idea. Model changes run through [onEdt]:
 * robot-server's own EDT dispatch uses the "any" modality, which is not write-safe, and the
 * platform reports every model change made there as an error.
 */
class SandboxIde(private val robot: RemoteRobot) {

    /** What the Settings checkbox does: flips the setting and rebuilds the Commit tool window. */
    fun setStagingAreaEnabled(enabled: Boolean) {
        onEdt(
            """
            gitClass('git4idea.index.GitStageManagerKt')
                .getMethod('enableStagingArea', java.lang.Boolean.TYPE)
                .invoke(null, java.lang.Boolean.valueOf($enabled))
            """,
        )
    }

    /**
     * Picks up changes made with the `git` CLI, as returning focus to the IDE would: a synchronous
     * VFS refresh of the project, then a full VCS dirty scope. Runs off the EDT, where a
     * synchronous refresh is allowed and not a slow operation.
     */
    fun refreshFromDisk() {
        robot.runJs(
            """
            $PRELUDE
            var base = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath())
            com.intellij.openapi.vfs.VfsUtil['markDirtyAndRefresh(boolean,boolean,boolean,com.intellij.openapi.vfs.VirtualFile[])'](false, true, true, [base])
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
            """,
            false,
        )
    }

    /** Shows the Commit tool window, which holds the staging area when it is enabled. */
    fun showCommitToolWindow() {
        onEdt("com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Commit').activate(null)")
    }

    /** Opens [path] in an editor and replaces its text without saving, as typing would. */
    fun editWithoutSaving(path: String, text: String) {
        onEdt(
            beforeEdt = """
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(project.getBasePath() + '/$path')
            """,
            body = """
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
            var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({
                run: function() { document.setText('${text.escapeJs()}') }
            }))
            """,
        )
    }

    /** Opens [path] in an editor tab, as double-clicking it would. */
    fun openFile(path: String) {
        onEdt(
            beforeEdt = """
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(project.getBasePath() + '/$path')
            """,
            body = "com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)",
        )
    }

    /** Switches to the installed theme named [name], as Settings | Appearance does; returns the previous one. */
    fun setTheme(name: String): String = robot.callJs(
        """
        $PRELUDE
        var laf = com.intellij.ide.ui.LafManager.getInstance()
        var previous = '' + laf.getCurrentUIThemeLookAndFeel().getName()
        var target = null
        var themes = laf.getInstalledThemes().iterator()
        while (themes.hasNext()) {
            var theme = themes.next()
            if (theme.getName() == '${name.escapeJs()}') target = theme
        }
        if (target == null) throw 'no installed theme named ${name.escapeJs()}'
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({
            run: function() {
                laf.setCurrentLookAndFeel(target, false)
                laf.updateUI()
            }
        }), com.intellij.openapi.application.ModalityState.nonModal())
        previous
        """,
        false,
    )

    /**
     * Sizes the IDE frame so the part below its main toolbar is [width] by [height] logical pixels,
     * at the top-left of the screen.
     */
    fun sizeFrameBelowToolbar(width: Int, height: Int) {
        onEdt(
            """
            var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            var top = $TOOLBAR_BOTTOM
            frame.setExtendedState(java.awt.Frame.NORMAL)
            frame.setBounds(0, 0, $width, top + $height)
            frame.validate()
            """,
        )
    }

    /**
     * The frame below its main toolbar, [width] by [height] logical pixels, captured from the screen
     * at full device resolution, as PNG bytes. The sandbox runs on X11, where a screen capture
     * cannot block the way it does through a Wayland portal.
     */
    fun captureBelowToolbar(width: Int, height: Int): ByteArray = robot.callJs(
        """
        $PRELUDE
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
        var top = $TOOLBAR_BOTTOM
        var at = frame.getLocationOnScreen()
        var area = new java.awt.Rectangle(at.x, at.y + top, $width, $height)
        var variants = new java.awt.Robot().createMultiResolutionScreenCapture(area).getResolutionVariants()
        var sharpest = variants.get(variants.size() - 1)
        var bytes = new java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(sharpest, 'png', bytes)
        bytes.toByteArray()
        """,
        false,
    )

    fun hasUnsavedDocuments(): Boolean = robot.callJs(
        "com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getUnsavedDocuments().length > 0",
        true,
    )

    fun closeAllEditors() {
        onEdt(
            """
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            var manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            var files = manager.getOpenFiles()
            for (var i = 0; i < files.length; i++) manager.closeFile(files[i])
            """,
        )
    }

    /** Raises the IDE frame: robot input lands on whatever window is on top. */
    fun raiseFrame() {
        robot.runJs(
            """
            var frames = java.awt.Frame.getFrames()
            for (var i = 0; i < frames.length; i++) {
                var frame = frames[i]
                if (frame.getClass().getName().indexOf('IdeFrameImpl') === -1) continue
                frame.setExtendedState(java.awt.Frame.NORMAL)
                frame.toFront()
            }
            """,
            true,
        )
    }

    /**
     * Disables and unloads this plugin the way Settings | Plugins does, then reports
     * `unloaded` or `refused`, `:collected` or `:retained` for its class loader, and `:actionGone`
     * or `:actionLeft` for its action. The descriptor is kept for [reloadPlugin].
     */
    fun unloadPlugin(): String = robot.callJs(
        """
        $PRELUDE
        var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId('$PLUGIN_ID'))
        java.lang.System.getProperties().put('$DESCRIPTOR_KEY', descriptor)
        var loader = new java.lang.ref.WeakReference(descriptor.getPluginClassLoader())
        descriptor = null
        var outcome = new java.util.concurrent.atomic.AtomicReference('')
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({
            run: function() {
                var plugins = java.util.Collections.singletonList(java.lang.System.getProperties().get('$DESCRIPTOR_KEY'))
                com.intellij.ide.plugins.PluginEnabler.HEADLESS.disable(plugins)
                var unloaded = com.intellij.ide.plugins.DynamicPlugins.INSTANCE.unloadPlugins(
                    plugins, project, null, new com.intellij.ide.plugins.DynamicPlugins.UnloadPluginOptions())
                outcome.set(unloaded ? 'unloaded' : 'refused')
            }
        }), com.intellij.openapi.application.ModalityState.nonModal())
        // Collection is checked off the EDT: blocking it stops the platform's deferred cleanup.
        for (var i = 0; i < 40 && loader.get() != null; i++) {
            java.lang.System.gc()
            java.lang.Thread.sleep(100)
        }
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction('$ACTION_ID')
        outcome.get() + (loader.get() == null ? ':collected' : ':retained') + (action == null ? ':actionGone' : ':actionLeft')
        """,
        false,
    )

    /** Enables and loads the plugin [unloadPlugin] removed; true when its action is back. */
    fun reloadPlugin(): Boolean = robot.callJs(
        """
        $PRELUDE
        var plugins = java.util.Collections.singletonList(java.lang.System.getProperties().remove('$DESCRIPTOR_KEY'))
        var loaded = new java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({
            run: function() {
                com.intellij.ide.plugins.PluginEnabler.HEADLESS.enable(plugins)
                loaded.set(com.intellij.ide.plugins.DynamicPlugins.INSTANCE.loadPlugins(plugins, project))
            }
        }), com.intellij.openapi.application.ModalityState.nonModal())
        loaded.get() && com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction('$ACTION_ID') != null
        """,
        false,
    )

    /** The feature release of the JDK the IDE runs on. */
    fun jdkFeature(): Int = robot.callJs<String>("'' + java.lang.Runtime.version().feature()", false).toInt()

    fun ideaLogLength(): Long = robot.callJs<String>(
        """
        var path = java.nio.file.Paths.get(com.intellij.openapi.application.PathManager.getLogPath(), 'idea.log')
        '' + (java.nio.file.Files.exists(path) ? java.nio.file.Files.size(path) : 0)
        """,
        false,
    ).toLong()

    /** `idea.log` from byte [offset] on, or all of it if the log rotated to a shorter file. */
    fun ideaLogFrom(offset: Long): String = robot.callJs(
        """
        var path = java.nio.file.Paths.get(com.intellij.openapi.application.PathManager.getLogPath(), 'idea.log')
        var result = ''
        if (java.nio.file.Files.exists(path)) {
            var bytes = java.nio.file.Files.readAllBytes(path)
            var start = bytes.length < $offset ? 0 : $offset
            result = '' + new java.lang.String(java.util.Arrays.copyOfRange(bytes, start, bytes.length), java.nio.charset.StandardCharsets.UTF_8)
        }
        result
        """,
        false,
    )

    /** The IDE's own windows painted offscreen, as PNG bytes; cannot block like a screen capture. */
    fun screenshot(): ByteArray = robot.callJs(
        """
        var screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize()
        var image = new java.awt.image.BufferedImage(screen.width, screen.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        var canvas = image.createGraphics()
        canvas.setColor(java.awt.Color.DARK_GRAY)
        canvas.fillRect(0, 0, screen.width, screen.height)
        var windows = java.awt.Window.getWindows()
        for (var w = 0; w < windows.length; w++) {
            var target = windows[w]
            if (!target.isShowing() || target.getWidth() <= 0 || target.getHeight() <= 0) continue
            try {
                var at = target.getLocationOnScreen()
                var pen = canvas.create(at.x, at.y, target.getWidth(), target.getHeight())
                try { target.paint(pen) } finally { pen.dispose() }
            } catch (ignored) { }
        }
        canvas.dispose()
        var bytes = new java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, 'png', bytes)
        bytes.toByteArray()
        """,
        true,
    )

    /**
     * Runs [body] on the EDT in a write-safe non-modal context and waits for it. [beforeEdt] runs
     * first on robot-server's own thread, for work such as a VFS refresh that is slow on the EDT.
     */
    private fun onEdt(body: String, beforeEdt: String = "") {
        robot.runJs(
            """
            $PRELUDE
            $beforeEdt
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({
                run: function() {
                    $body
                }
            }), com.intellij.openapi.application.ModalityState.nonModal())
            """,
            false,
        )
    }

    private fun String.escapeJs(): String = replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    private companion object {
        const val PLUGIN_ID = "io.stageallexceptselected"
        const val ACTION_ID = "StageAllExceptSelected.Stage"
        const val DESCRIPTOR_KEY = "uiTest.unloadedDescriptor"

        /** JavaScript expression: the y of the main toolbar's bottom edge within `frame`. */
        const val TOOLBAR_BOTTOM = """(function() {
                var type = java.lang.Class.forName('com.intellij.openapi.wm.impl.headertoolbar.MainToolbar', false, frame.getClass().getClassLoader())
                var toolbar = com.intellij.util.ui.UIUtil.findComponentOfType(frame.getRootPane(), type)
                return javax.swing.SwingUtilities.convertPoint(toolbar, 0, toolbar.getHeight(), frame).y
            })()"""

        /**
         * `project` is the one open project; `gitClass` loads a git4idea staging class. From 2026.2
         * git4idea is split into content modules, and the plugin's main loader defines its own
         * copies of their classes, so the loader comes from the live Stage action instead.
         */
        val PRELUDE = """
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]
            function gitClass(name) {
                var stage = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction('Git.Stage.Add')
                return java.lang.Class.forName(name, true, stage.getClass().getClassLoader())
            }
        """.trimIndent()
    }
}
