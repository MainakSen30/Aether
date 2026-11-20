# Aether

Aether is a native Android launcher and personalization ecosystem. This repository contains the
initial, buildable scaffold: architecture, persistence, Theme Engine, Asset Engine, design system,
Hilt wiring, and a minimal HOME-eligible Compose entry point. It is deliberately **not** a complete
launcher yet.

## 1. Product overview

| Item | Value |
|---|---|
| Package | `dev.aether.launcher` |
| Platform | Native Android, Jetpack Compose |
| Device target | Nothing Phone 2 (API 28+) |
| Compile / target / min SDK | 36 / 36 / 28 |
| Architecture | Modular monolith, MVVM + UDF, selective reducer-style state |
| Initial visual direction | Aether Black — deep black, warm cream, restrained Material 3 with subtle glass surfaces |

Planned product areas (launcher, drawer, widgets, icons, wallpapers, theme engine, Studio, control
center, gestures, settings) are represented by foundations only. Only `:feature:launcher` exists as
a feature module in this milestone.

## 2. Technology stack

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Gradle wrapper | 9.5.0 |
| Kotlin (KGP) | 2.4.21 |
| KSP | 2.3.12 |
| Hilt | 2.60.1 |
| Compose BOM | 2026.06.01 (see Deviations) |
| Room | 2.8.5 |
| Preferences DataStore | 1.1.7 |
| Coroutines | 1.10.2 |
| Kotlin serialization | 1.9.0 |
| Java/JVM target | 17 |

No networking, auth, analytics, backend, cloud, or third-party UI libraries are included.

AGP 9.3 requires Gradle 9.5.0 or newer; KSP 2.3.12 supports KGP 2.3.20–2.4.x and AGP 8.13+.
Versions were resolved against Google Maven and Maven Central before implementation.

## 3. Module structure

```text
Aether/
├── app/                         Android application assembly
├── core/
│   ├── model/                   Pure Kotlin models, IDs, theme/asset models (JVM)
│   ├── domain/                  Repository interfaces and contracts (JVM)
│   ├── data/                    Room, DataStore, entity/DAO mappings, repositories
│   ├── assets/                  Asset Engine, safe paths, app-private storage (JVM)
│   ├── theme/                   Theme Engine, resolver, inheritance, JSON package (JVM)
│   └── designsystem/            Material 3 integration, Aether tokens, shared components
├── feature/
│   └── launcher/                Placeholder launcher screen + ViewModel
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties             Machine-specific SDK path (gitignored)
└── README.md
```

Dependency direction:

```text
model ← domain ← { data, assets, theme }
theme → designsystem → feature:launcher → app
data, assets → app
```

`app` is the only module that assembles infrastructure implementations with Hilt providers.

## 4. Module responsibilities

- **`:app`** — `AetherApplication`, `MainActivity`, HOME intent filter, app-level Hilt module.
- **`:core:model`** — `ThemeId`, `AssetId`, strongly typed theme/asset/settings models. No Android dependency.
- **`:core:domain`** — `ThemeRepository`, `AssetRepository`, `PreferencesRepository`. No Room, Compose, or Android dependency.
- **`:core:data`** — Room database v1 with exported schema, entities, DAOs, DataStore, repository implementations, Hilt `DataModule`.
- **`:core:assets`** — `AssetStorage`, `AppPrivateAssetStorage`, `AssetPaths`, `AssetEngine`; import staging and lifecycle foundations.
- **`:core:theme`** — `ThemeCatalog`, `Aether Black`, `ThemeResolver`, `ThemeInheritanceValidator`, `ThemePackageCodec`, `ThemeController`.
- **`:core:designsystem`** — `AetherTheme`, color/typography/shape/dimension mapping, `AetherSurface`.
- **`:feature:launcher`** — `LauncherScreen`, `LauncherUiState`, `LauncherViewModel`.

Feature modules do not depend on other feature modules' internals. Domain models never depend on
Compose or Room.

## 5. Architecture decisions implemented

1. One installable package; internal modules are Gradle Android/JVM libraries.
2. MVVM + UDF in the launcher feature (`StateFlow` → Compose `collectAsState`).
3. Repository interfaces in `:core:domain`; implementations in `:core:data`.
4. Hilt with `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, and `@Provides` modules.
   Singleton scope is used only for database, DataStore, storage, and theme runtime objects.
5. Pure Kotlin `:core:model`, `:core:domain`, `:core:assets`, `:core:theme` so tests run on the JVM
   with fakes and temporary directories.
6. Theme resolution priority: component override → active theme token → Aether default → platform fallback.
7. Single-parent inheritance with cycle/missing-parent validation.
8. Versioned, data-only JSON theme packages; unknown keys are rejected.
9. Active theme state is published only after repository activation succeeds; drafts never mutate it.
10. Asset metadata in Room, binaries in app-private files, small settings in DataStore.

## 6. Persistence boundaries

- **Room** (`aether.db`, version 1, `exportSchema = true`): `theme_metadata`, `asset_metadata`,
  `asset_references`, `asset_imports`. Schema is written to `core/data/schemas/`.
- **Preferences DataStore** (`aether_settings`): active theme ID, grid columns, gesture flag, animation
  flag, appearance mode.
- **App-private filesystem** (`filesDir/aether/`): `staging/`, `originals/`, `thumbnails/`, `cache/`,
  `trash/`. Paths are relative and validated against the canonical root (including symlinks).
- No binary assets are stored in Room or DataStore. Cache is never authoritative.
- Room and DataStore do **not** share a transaction. `ThemeRepositoryImpl.activate` validates
  metadata first, then writes DataStore; `ThemeController` publishes runtime state only on success.
- No production migration exists yet; destructive migration is not used.

## 7. Theme Engine responsibilities

- Distinguishes built-in definitions (`AetherThemeCatalog`), active theme (`ThemeController.active`),
  and drafts (`saveDraft`/`preview`). A draft is published only through `activate`.
- `ThemeDefinition` carries a single optional `parentId`; `ThemeInheritanceValidator` detects cycles
  and missing parents and returns root-to-leaf lineage.
- Tokens cover colors, typography, shapes, dimensions, surface treatment, component presets, and
  component overrides. Color fields are nullable so a child can override only part of its parent.
- `ThemePackage` carries `formatVersion`; codec rejects unknown fields, validator rejects unsupported
  versions and self-parenting. Packages contain data only — no executable code.
- `Aether Black`: `#050505` background, `#141414` surface, `#F3EEE4` foreground, restrained primary,
  0.92-alpha glass surface, no gradients or blur.

## 8. Asset Engine responsibilities

- Categories: wallpaper, icon, theme image, font, thumbnail, other.
- Lifecycle foundation: receive → inspect/validate → stage → process/register → publish → cleanup.
  `AssetEngine.register` promotes a staged file into `originals/<category>/<assetId>.<ext>` and stores
  metadata with a SHA-256 checksum and a relative path.
- `AssetPaths` rejects traversal, absolute paths, unsafe file names, unsupported extensions, and
  sizes above 128 MiB; canonical path checks stop symlink escapes.
- Multiple consumers can reference one asset through `asset_references`; deletion is rejected while
  references exist.
- Temporary assets are deleted; user-managed assets move to `trash/` and are marked `TRASHED`
  (recoverable). Cleanup of the trash itself is a later milestone.

## 9. Dependency injection structure

- `DataModule` (`@InstallIn(SingletonComponent::class)`): Room database, DAOs, DataStore,
  `PreferencesRepository`, `ThemeRepository`, `AssetRepository`.
- `AppModule` (`@InstallIn(SingletonComponent::class)`): `AssetStorage` rooted at `filesDir/aether`,
  `AssetEngine`, `ThemeCatalog`, `ThemeResolver`, `ThemeController`.
- Features inject interfaces/controllers; fakes can replace repositories and `AssetStorage` in tests.

## 10. Build and run

Requirements: JDK 17, Android SDK platform 36 and build-tools 36.1.0, local Gradle wrapper (no global
Gradle install needed). `local.properties` must contain the machine's SDK path:

```properties
sdk.dir=/Users/mainaksen/Library/Android/sdk
```

Local commands:

```bash
cd /Users/mainaksen/projects/Aether
./gradlew projects                    # Gradle configuration / module graph
./gradlew :app:assembleDebug          # Kotlin compile, resources, Hilt/KSP, debug APK
./gradlew test                        # all local JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

To open in Android Studio: **File → Open → /Users/mainaksen/projects/Aether**. On the test device,
choose Aether in Android's default-launcher dialog; the app never changes the default itself.

## 11. Known limitations

- The launcher screen is a placeholder; no grid, drawer, search, folders, drag-and-drop, gestures,
  widgets, control center, Studio, or settings UI.
- Drafts are process-local; full theme token blobs are not yet persisted. Only theme metadata is in Room.
- Built-in themes are not seeded into `theme_metadata`; `activate()` currently requires pre-existing
  metadata, so activation is a foundation rather than an end-user flow.
- Theme JSON import has codec + validator but no UI or storage pipeline.
- Asset import has staging/promotion/trash foundations but no byte-stream import UI, processing,
  thumbnail generation, or trash restore UI.
- Checksums are computed but not yet verified against an external manifest.
- Room schema version 1 has no migrations yet.
- Only local JVM unit tests exist; there are no instrumented/device tests in this milestone.
- No device run was performed; HOME eligibility was verified by manifest inspection, not by changing
  the device's default launcher.

## 12. Future implementation milestones

1. Seed built-in theme metadata and persist full theme definitions; add safe activation with rollback.
2. Seed installed-app discovery into Room (launcher intent queries are already declared) and build the
   first home-screen grid.
3. Complete the asset import pipeline (inspection, validation, processing, thumbnails, trash restore).
4. Application drawer, search, folders, and drag-and-drop.
5. Gesture engine, widgets, control center, Studio, and personalization settings.

Do not start these milestones without approval.

## Deviations from the specification

1. **Compose BOM 2026.06.01 instead of 2026.09.00.** The specified BOM pins Compose 1.12.1, whose
   Android artifacts declare `minCompileSdk=37`; the specification fixes compile SDK at 36. Changing
   the compile SDK would have been a larger deviation, so the newest BOM compatible with API 36 was
   selected (Compose 1.11.4, `minCompileSdk=35`). All other requested versions are unchanged.
2. **No `org.jetbrains.kotlin.android` plugin.** AGP 9.3 hard-fails when it is applied; built-in Kotlin
   is mandatory. Kotlin 2.4.21 and KSP 2.3.12 are pinned through the root `buildscript` classpath as
   required by the AGP 9 migration guide. Pure JVM modules still use `kotlin-jvm`.
3. **`local.properties` is created locally and gitignored.** The SDK path is machine-specific and the
   environment does not export `ANDROID_HOME`.
