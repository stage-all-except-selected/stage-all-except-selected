# Stage Other Files

JetBrains IDE plugin that stages every unstaged file except the ones you select in the Git staging
area.

## Build

Requires JDK 21; Gradle downloads it if none is installed.

```bash
./gradlew build         # compile and test
./gradlew buildPlugin   # plugin zip in build/distributions/
./gradlew runIde        # sandbox IDE with the plugin installed
```
