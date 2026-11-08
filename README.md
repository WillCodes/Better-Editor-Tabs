# Better Editor Tabs

Better Editor Tabs improves the editor tab bar in JetBrains IDEs. Its first feature is Chrome/Edge-style manual tab
groups: coloured, named, collapsible groups of editor tabs that can be dragged between splits, stashed and restored,
and filled automatically by rules (module, directory or regex).

## Features

- **Tab Groups** (in development)

## Compatibility

- IntelliJ Platform 2026.2 or later (build 262+), with no upper bound
- New UI only
- Works in all JetBrains IDEs

## Development

Open the project in IntelliJ IDEA as a Gradle project. The `.run` directory provides these run configurations:

| Configuration       | Gradle command        |
|---------------------|-----------------------|
| Run IDE with Plugin | `./gradlew runIde`    |
| Run Tests           | `./gradlew check`     |
| Run Verifications   | `./gradlew verifyPlugin` |

On Windows, use `.\gradlew.bat` instead of `./gradlew`.
