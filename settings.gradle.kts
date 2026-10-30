import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

// Look up the current release of each plugin below before raising it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Also supplies the version of org.jetbrains.intellij.platform in build.gradle.kts.
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "stage-other-files"

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
