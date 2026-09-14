# ImuxLauncher

Base Android Minecraft launcher written in Kotlin with Jetpack Compose Material 3.

## Modules

- `:launcher` — Android application, splash screen and Material You version UI.
- `:game-core` — shared game/release domain layer and Ktor client scaffold.

## Version model

`VersionManager` queries the current repository's GitHub Releases API at:

`https://api.github.com/repos/cuuw619-maker/ImuxLauncher/releases/latest`

Archive repositories are represented by the same `GameVersion` model and can be configured as they are added.

## Build

```bash
gradle :launcher:assembleDebug
```

APK output:

`launcher/build/outputs/apk/debug/launcher-debug.apk`

GitHub Actions builds the debug APK automatically on pushes to `main`.
