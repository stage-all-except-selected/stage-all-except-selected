# Stage All Except Selected

IntelliJ Platform plugin, written in Kotlin, that adds **Stage All Except Selected** to the Git
staging area's context menu. It compiles against IntelliJ IDEA Community 2024.2.6 (build 242) and must run
unchanged on every newer IDE.

## Commands

| Command | Does |
|---|---|
| `./gradlew test` | Unit and light platform tests only; UI tests are `uiTest` |
| `./gradlew verifyPlugin -PverifyIde=<version>` | Plugin Verifier against one IDE; versions from `./gradlew printProductsReleases` without the `IU-` prefix |

An unfiltered `./gradlew verifyPlugin` downloads every IDE in the range, about 45–50 GB.

## Run the UI tests

`src/uiTest` drives a sandbox IDE through remote-robot and checks the result with `git status`.

```bash
Xvfb :99 -screen 0 1920x1080x24 &
DISPLAY=:99 ./gradlew runIdeForUiTests &   # IntelliJ IDEA 2024.2.6, robot-server on :8082
./gradlew uiTest
```

- `runIdeForUiTestsLatest` runs the same sandbox on the installed IDE named by `uiTestLatestIdePath`
  in `~/.gradle/gradle.properties`. Only one sandbox can run at a time.
- Use Xvfb on a Wayland desktop: robot input cannot reach a Wayland window.
- A failing test saves a screenshot to `build/reports/uiTest/<test>.png`. A test also fails when the
  plugin logs an error to `idea.log`.

## Capture the Marketplace screenshots

`MarketplaceScreenshotsUiTest` writes `marketplace/screenshots/`. It runs only when given an output
directory, on the newest IDE, rendered at 2x:

```bash
Xvfb :99 -screen 0 2880x1800x24 &
DISPLAY=:99 ./gradlew runIdeForUiTestsLatest -PuiTestUiScale=2 &
./gradlew uiTest --tests '*MarketplaceScreenshotsUiTest' -PuiTestScreenshotDir=marketplace/screenshots
```

## Sign the plugin

`signPlugin` and `verifyPluginSignature` read `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
`PRIVATE_KEY_PASSWORD` from the environment, never from a file in the repository. Without them
`signPlugin` skips itself and reports success.

Key and certificate creation is in `.github/release-signing.md`.

## CI

`publish.yml` needs the repository secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN`, and fails before signing if one is empty.

## Compatibility rules

- Compile against the oldest supported IDE (`platformVersion` in `gradle.properties`). Keep
  `sinceBuild` set and `untilBuild` unset.
- Kotlin `apiVersion` and `languageVersion` stay at 1.9: IntelliJ IDEA 2024.2.6 bundles Kotlin
  stdlib 1.9.24. Kotlin 2.3 is the last compiler that accepts 1.9, so the Kotlin Gradle plugin stays
  on 2.3.x until `pluginSinceBuild` is raised.
- Never omit a defaulted argument when calling a `com.intellij` or `git4idea` Kotlin function or
  constructor, for example `GitFileStatus(index, workTree, path, origPath)`. The synthetic
  `$default` member it compiles to changes shape between IDE versions and throws
  `NoSuchMethodError`. `PlatformDefaultArgumentCallsTest` enforces this.
- Never call a Kotlin-`internal` git4idea member (name-mangled, or refused by the compiler), such as
  `GitStageTracker.State.fileStatusNodes` or `isStagingAreaAvailable`.
- No `@ApiStatus.Internal` platform API. `acceptedInternalApiUsages` in `build.gradle.kts` stays
  empty.
- Never change `<id>` in `plugin.xml` after the first Marketplace upload.
- No `<component>` entries; register listeners in `plugin.xml` so the plugin unloads without a
  restart.

## Check a platform signature

Never guess an API. Dump the type from the compile baseline with `javap` and read the whole listing,
never a `head`-truncated one:

```bash
IDE=$(find ~/.gradle/caches/*/transforms -maxdepth 6 -name 'ideaIC-2024.2.6' -type d | head -1)
CP=$(find "$IDE" -name '*.jar' | sed 's|/[^/]*$||' | sort -u | sed 's|$|/*|' | tr '\n' ':')
javap -cp "$CP" git4idea.index.GitStageTracker
```

Add `-p` for private members and `-c` for bytecode.
