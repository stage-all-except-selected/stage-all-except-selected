import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Look up the current release of each plugin and library below before raising it.
// org.jetbrains.intellij.platform takes its version from the settings plugin in settings.gradle.kts.
plugins {
    // 2.3 is the last Kotlin compiler that accepts API and language version 1.9 (see below), so stay
    // on 2.3.x until pluginSinceBuild is raised.
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
val pluginVersion = providers.gradleProperty("pluginVersion").get()
version = pluginVersion

// Client (uiTestImplementation) and server (robotServerPlugin) must be the same version.
val remoteRobotVersion = "0.11.23"

// The throwaway project the UI-test IDE opens: two Git roots, rebuilt on every launch.
val uiTestProjectDir = layout.buildDirectory.dir("inventory-service")

sourceSets {
    // Slow, needs a live IDE started separately; never part of `./gradlew test`.
    create("uiTest")
}

val uiTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations.getByName("uiTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

// The releases the Plugin Verifier checks. pluginVerification.ides.select{} and
// printProductsReleases both read these, so CI's per-IDE matrix is the list a local verifyPlugin uses.
// IU, not the IC compiled against: IPGP rejects every IC release from 253 on, so recommended()
// silently stops at 2025.2. IU is a superset of IC, so this widens coverage without changing the
// compile baseline.
val verifiedIdeType = IntelliJPlatformType.IntellijIdea
val verifiedIdeChannels = listOf(
    ProductRelease.Channel.RELEASE,
    ProductRelease.Channel.EAP,
    ProductRelease.Channel.RC,
)

dependencies {
    intellijPlatform {
        // Compile against the OLDEST supported platform and run forward.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("Git4Idea")

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // The platform test framework is built on JUnit 4 and throws opentest4j assertion errors.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    // kotlin.stdlib.default.dependency=false removes the stdlib everywhere; uiTest does not get the
    // platform's copy, so it needs its own.
    uiTestImplementation(kotlin("stdlib"))
    uiTestImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    uiTestImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
    // RemoteRobot's constructor takes an OkHttpClient, which remote-robot only puts on the runtime
    // classpath. Same version as remote-robot's own dependency.
    uiTestImplementation("com.squareup.okhttp3:okhttp:3.14.9")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // The Kotlin stdlib bundled with the OLDEST supported IDE: 2024.2.6 ships 1.9.24. A newer
        // API level compiles fine and throws NoSuchMethodError on that IDE.
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        // Silences only the deprecation warning for the pinned version above.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
}

intellijPlatform {
    // The plugin has no settings page, so there is nothing to index.
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        // `pluginVersion`, the String above: inside this block `version` is the Property itself,
        // which never matches a changelog section. Fall back to [Unreleased] because patchChangelog
        // moves its entries into the stamped section; reading [Unreleased] alone ships empty notes.
        changeNotes = provider {
            changelog.renderItem(
                (changelog.getOrNull(pluginVersion) ?: changelog.getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Deliberately unset: the Plugin Verifier guards compatibility, not a ceiling that locks
            // users out on every IDE upgrade. A plain null falls back to "242.*", the target's major.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // -PverifyIde=<version> verifies one release (printProductsReleases output without its
            // "IU-" prefix). The whole matrix needs 45-50 GB of disk.
            val verifyIde = providers.gradleProperty("verifyIde")
            if (verifyIde.isPresent) {
                create(verifiedIdeType, verifyIde.get())
            } else {
                select {
                    types = listOf(verifiedIdeType)
                    channels = verifiedIdeChannels
                }
            }
        }

        // INTERNAL_API_USAGES: Marketplace refuses an upload over it. MISSING_DEPENDENCIES: an
        // unresolvable <depends> stops the plugin loading, and otherwise only prints.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }

    // Environment variables only, so a value can never be committed in gradle.properties.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    // One release per line; a CI verify matrix is built from this.
    printProductsReleases {
        types = listOf(verifiedIdeType)
        channels = verifiedIdeChannels
    }

    runIde {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Didea.is.internal=true")
        }
    }

    // Start `runIdeForUiTests` in the background first, then run this against it.
    register<Test>("uiTest") {
        description = "Runs remote-robot UI tests against a `runIdeForUiTests` IDE started separately."
        group = "verification"
        testClassesDirs = sourceSets["uiTest"].output.classesDirs
        classpath = sourceSets["uiTest"].runtimeClasspath
        systemProperty("uiTest.projectDir", uiTestProjectDir.get().asFile.absolutePath)
        // -PuiTestScreenshotDir=<dir> runs MarketplaceScreenshotsUiTest, which writes there.
        providers.gradleProperty("uiTestScreenshotDir").orNull?.let {
            systemProperty("uiTest.screenshotDir", file(it).absolutePath)
        }
        // Reruns against whatever state the running IDE is in; there is no meaningful up-to-date.
        outputs.upToDateWhen { false }
        // remote-robot's Gson parsing reflects into Throwable; without this every failure surfaces
        // as an opaque IllegalArgumentException.
        jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    }
}

/**
 * Rebuilds the UI-test project from `src/uiTest/fixture-project`: a Git repository at the project
 * root and a second one in `shared-config/`, both mapped in `.idea/vcs.xml`. The tests reset the working
 * trees themselves; the repositories only need to exist before the IDE opens the project.
 */
val prepareUiTestProject = tasks.register("prepareUiTestProject") {
    description = "Creates the two-repository project the UI-test IDE opens."
    group = "verification"

    val template = layout.projectDirectory.dir("src/uiTest/fixture-project").asFile
    val target = uiTestProjectDir.map { it.asFile }
    inputs.dir(template)
    outputs.upToDateWhen { false }

    doLast {
        fun git(directory: File, vararg args: String) {
            val process = ProcessBuilder("git", *args).directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed in $directory:\n$output" }
        }

        val projectDir = target.get()
        projectDir.deleteRecursively()
        template.copyRecursively(projectDir)
        for (root in listOf(projectDir, projectDir.resolve("shared-config"))) {
            root.mkdirs()
            git(root, "init", "--quiet", "--initial-branch=main")
            git(root, "config", "user.name", "UI Test")
            git(root, "config", "user.email", "ui-test@example.com")
            git(root, "config", "commit.gpgsign", "false")
            git(root, "commit", "--quiet", "--allow-empty", "--message=Initial commit")
        }
        // The IDE's own files and the nested repository are not part of the root repository.
        projectDir.resolve(".git/info/exclude").appendText("/.idea/\n/inventory-service.iml\n/shared-config/\n")
    }
}

// Internal platform API this plugin may call. Keep it empty: an entry is a decision to ship an API
// the Marketplace may refuse, made in review with the reasoning written down, never to turn a build
// green. Format: "com.example.PlatformClass.member used by io.github.yendrrek.stageotherfiles.Caller".
val acceptedInternalApiUsages = emptySet<String>()

/**
 * Fails on any `@ApiStatus.Internal` platform usage the Verifier reports that is not in
 * [acceptedInternalApiUsages], naming the member and the IDEs that reported it.
 *
 * Kept alongside INTERNAL_API_USAGES in failureLevel because that level is one switch for the whole
 * category: drop it to accept one call and every other internal call goes silent. Members also
 * become internal in later IDEs, so the Verifier's report is the only signal there is.
 *
 * Finalizes verifyPlugin so a plain `./gradlew verifyPlugin` cannot skip it, and does not depend on
 * it, so re-reading a report on disk costs no IDE downloads.
 */
val verifyInternalApiAllowlist = tasks.register("verifyInternalApiAllowlist") {
    description = "Fails on any internal platform API usage the Verifier reports that is not accepted in build.gradle.kts."
    group = "verification"

    val reportsDirectory = tasks.named<VerifyPluginTask>("verifyPlugin").flatMap { it.verificationReportsDirectory }
    val accepted = acceptedInternalApiUsages
    outputs.upToDateWhen { false }

    doLast {
        val reportsRoot = reportsDirectory.get().asFile

        // No report at all means nothing was checked, never that the plugin is clean.
        val ideReports = reportsRoot.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
        check(ideReports.isNotEmpty()) {
            "no Plugin Verifier reports under $reportsRoot, so nothing was checked. If verifyPlugin " +
                "itself failed above, fix that first."
        }

        // "Internal <kind> <member> is <verb> in|by <user>. This <kind> is marked with @…Internal …"
        val usage = Regex("""^Internal \w+ (.+?) is \w+ (?:in|by) (.+)$""")

        // Signatures and synthetic `$1` suffixes are dropped so an entry survives a recompile.
        fun normalise(line: String): String? {
            val head = line.substringBeforeLast(". This ")
            val (member, user) = usage.find(head)?.destructured ?: return null
            val calledMember = member.substringBefore('(').substringBefore(" : ").trim()
            val callingClass = user.substringBefore('(')
                .let { if ('(' in user) it.substringBeforeLast('.') else it }
                .substringBefore('$')
                .trim()
            return "$calledMember used by $callingClass"
        }

        val offenders = sortedMapOf<String, MutableSet<String>>()
        val seen = mutableSetOf<String>()
        var reportsRead = 0

        ideReports.forEach { ide ->
            // Absent when that IDE found no internal usage: a pass.
            ide.walkTopDown().filter { it.isFile && it.name == "internal-api-usages.txt" }.forEach { report ->
                reportsRead++
                report.readLines()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        // An unparseable line fails: a changed report format must not pass silently.
                        val normalised = normalise(line.trim())
                            ?: error("could not parse a Plugin Verifier finding in $report; its report format has changed:\n  $line")
                        seen += normalised
                        if (normalised !in accepted) {
                            offenders.getOrPut(normalised) { sortedSetOf() } += ide.name
                        }
                    }
            }
        }

        if (offenders.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Internal platform API used without being accepted in build.gradle.kts:")
                    offenders.forEach { (finding, ides) -> appendLine("  $finding  [${ides.joinToString(", ")}]") }
                    appendLine()
                    appendLine("JetBrains Marketplace refuses an upload that uses internal API.")
                    append("Replace the call with public API; dump and read the whole type before concluding none exists.")
                },
            )
        }

        // A de-internalised accepted call is good news, not a failure.
        (accepted - seen).forEach {
            logger.lifecycle("verifyInternalApiAllowlist: no longer reported, so the entry can go: $it")
        }
        logger.lifecycle("verifyInternalApiAllowlist: ${ideReports.size} IDE report(s), $reportsRead with internal usage, all accepted.")
    }
}

tasks.named("verifyPlugin") {
    finalizedBy(verifyInternalApiAllowlist)
}

intellijPlatformTesting {
    runIde {
        val uiTestIdeArgs = listOf(
            "-Drobot-server.port=8082",
            // No first-run walls for the robot to get stuck behind.
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Didea.is.internal=true",
            // Overrides the -Didea.required.plugins.id=<this plugin> IPGP adds. From 2026.2 that
            // property makes the platform refuse to unload the plugin, so an unload test cannot do
            // what a user's Disable does.
            "-Didea.required.plugins.id=com.jetbrains.test.robot-server-plugin",
            // macOS native chrome remote-robot cannot see or drive.
            "-DjbScreenMenuBar.enabled=false",
            "-Dapple.laf.useScreenMenuBar=false",
            "-Dide.mac.file.chooser.native=false",
        )

        fun RunIdeTask.configureForUiTests() {
            dependsOn(prepareUiTestProject)
            args(uiTestProjectDir.get().asFile.absolutePath)
            jvmArgumentProviders += CommandLineArgumentProvider { uiTestIdeArgs }
            // -PuiTestUiScale=2 renders the IDE at twice the pixel density, for sharp screenshots.
            val uiScale = providers.gradleProperty("uiTestUiScale")
            jvmArgumentProviders += CommandLineArgumentProvider {
                listOfNotNull(uiScale.orNull?.let { "-Dsun.java2d.uiScale=$it" })
            }
            // java.awt.Robot cannot reach a native Wayland window. An empty WAYLAND_DISPLAY makes
            // the JBR resolve awt.toolkit.name=auto to X11 and open the IDE on DISPLAY. Unsetting it
            // is not enough: the JBR then falls back to $XDG_RUNTIME_DIR/wayland-0.
            environment("WAYLAND_DISPLAY", "")
            // Read through a provider so a changed DISPLAY invalidates the configuration cache: the
            // environment a task inherits is a snapshot from whichever build configured it.
            environment("DISPLAY", providers.environmentVariable("DISPLAY").getOrElse(""))
        }

        register("runIdeForUiTests") {
            task { configureForUiTests() }
            plugins {
                robotServerPlugin(remoteRobotVersion)
            }
        }

        // The same sandbox on an INSTALLED newest IDE, since platform behaviour moves under the
        // plugin. Installed rather than downloaded: Gradle 9 holds the download cache immutable,
        // and an IDE that starts JCEF writes into its own install directory, which breaks the next
        // launch. The path is machine-specific, so it lives in ~/.gradle/gradle.properties.
        // Only one sandbox can run at a time: both listen on :8082.
        register("runIdeForUiTestsLatest") {
            localPath = layout.dir(
                providers.gradleProperty("uiTestLatestIdePath")
                    .orElse(
                        provider {
                            throw GradleException(
                                "runIdeForUiTestsLatest needs uiTestLatestIdePath in ~/.gradle/gradle.properties: " +
                                    "the directory of an installed IntelliJ IDEA, e.g. a Toolbox app directory.",
                            )
                        },
                    )
                    .map { file(it) },
            )
            task { configureForUiTests() }
            plugins {
                robotServerPlugin(remoteRobotVersion)
            }
        }
    }
}
