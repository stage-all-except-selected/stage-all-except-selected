# Stage Other Files

IntelliJ Platform plugin, written in Kotlin, that adds **Stage Other Files** to the Git staging
area's context menu. It compiles against IntelliJ IDEA Community 2024.2.6 (build 242) and must run
unchanged on every newer IDE.

## Commands

| Command | Does |
|---|---|
| `./gradlew build` | Compiles, runs unit tests, builds the plugin zip |
| `./gradlew test` | Unit and light platform tests only |
| `./gradlew verifyPluginProjectConfiguration` | Checks the descriptor and build setup |
| `./gradlew verifyPlugin -PverifyIde=<version>` | Plugin Verifier against one IDE; versions from `./gradlew printProductsReleases` without the `IU-` prefix |
| `./gradlew runIde` | Sandbox IDE with the plugin installed |

An unfiltered `./gradlew verifyPlugin` downloads every IDE in the range, about 45–50 GB.

## Compatibility rules

- Compile against the oldest supported IDE (`platformVersion` in `gradle.properties`). Keep
  `sinceBuild` set and `untilBuild` unset.
- Kotlin `apiVersion` and `languageVersion` stay at 2.0, the Kotlin bundled with 242.
  `kotlin.stdlib.default.dependency = false` keeps the stdlib out of the plugin.
- Never omit a defaulted argument when calling a `com.intellij` or `git4idea` Kotlin function or
  constructor, for example `GitFileStatus(index, workTree, path, origPath)`. The synthetic
  `$default` member it compiles to changes shape between IDE versions and throws
  `NoSuchMethodError`.
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

Add `-p` for private members and `-c` for bytecode. On Windows, use `;` as the separator and convert
paths with `cygpath -m`.
