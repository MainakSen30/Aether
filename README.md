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
- **`:core:assets`** — `AssetStorage`, `AppPrivateAssetStorage`, `AssetPaths`, `AssetValidator`, `AssetEngine`; import staging, content validation, and lifecycle foundations.
- **`:core:theme`** — `ThemeCatalog`, `Aether Black`, `ThemeResolver`, `ThemePackageValidator`, `ThemeInheritanceValidator`, `ThemePackageCodec`, `ThemeController`.
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

- **Room** (`aether.db`, version 2, `exportSchema = true`): `theme_metadata` (queryable metadata plus
  complete versioned JSON), `theme_drafts` (persistent draft definitions), `asset_metadata`,
  `asset_references`, `asset_imports`. Schemas and the explicit `1 → 2` migration are in
  `core/data/schemas/` and `AetherDatabase.kt`.
- **Preferences DataStore** (`aether_settings`): active theme ID, grid columns, gesture flag, animation
  flag, appearance mode.
- **App-private filesystem** (`filesDir/aether/`): `staging/`, `originals/`, `thumbnails/`, `cache/`,
  `trash/`. Paths are relative and validated against the canonical root (including symlinks).
- No binary assets are stored in Room or DataStore. Cache is never authoritative.
- Asset state strings use the existing `state` column; lifecycle markers added in Milestone 1 need
  **no schema change**. Room is version 2 for the additive theme definition/draft changes.
- Room transactions group metadata and import-status writes; filesystem changes use marker states
  and explicit recovery instead of pretending to share a transaction with Room.
- Room and DataStore do **not** share a transaction. `ThemeController.activate` validates and resolves
  the persisted definition first, then calls `ThemeRepositoryImpl.activate` to write DataStore. If
  that write fails, the controller makes a best-effort restore of the previously configured ID and
  publishes nothing; startup validation remains the final recovery path. No invalid theme is ever
  published as a fallback.
- Migration `1 → 2` adds definition columns to `theme_metadata` and creates `theme_drafts`; it is
  additive and never drops asset tables. Destructive migration is not used.
- `ThemeRepositoryImpl.seedBuiltIns()` is mutex-guarded and calls a single Room `@Transaction`; any failure leaves no partial catalog. Room and DataStore remain separate systems with no shared transaction.
  DataStore is written only after a definition has been read back successfully, but Room and
  DataStore still do not share a transaction.

## 7. Theme Engine responsibilities

- Distinguishes built-in definitions (`AetherThemeCatalog`), persisted definitions/drafts in Room,
  and active theme (`ThemeController.active`). A draft is published only through `activate`.
- Full `ThemeDefinition` objects are stored as versioned `ThemePackage` JSON, so tokens, nullable
  overrides, presets, overrides, parent ID, origin, and format version survive process death.
  Metadata columns duplicate only queryable fields from the same JSON blob.
- Startup calls `ThemeController.initialize()`: seed built-ins, validate the configured active theme
  (or select Aether Black only when no ID is configured), load the persisted definition, resolve it,
  then publish. An invalid configured ID is never replaced by a silent fallback; failures leave the
  runtime theme unpublished and are logged.
- Built-in seeding is idempotent and transactional. It adds catalog definitions without duplicates,
  refreshes existing built-in rows, and never overwrites imported/user-managed definitions or drafts.
- `ThemeDefinition` carries a single optional `parentId`; `ThemeInheritanceValidator` detects cycles
  and missing parents and returns root-to-leaf lineage.
- Tokens cover colors, typography, shapes, dimensions, surface treatment, component presets, and
  component overrides. Color fields are nullable so a child can override only part of its parent.
- `ThemePackage` carries `formatVersion`; codec rejects empty/oversized input, malformed JSON,
  duplicate or unknown keys, special floats, invalid enums/nested models, and unsupported versions.
  `ThemePackageValidator` also checks identity, name, parent, origin, ARGB range, nullable colors,
  finite/bounded numeric tokens, surface treatment, component IDs/presets/overrides, and the small
  documented `ThemeComponentRegistry`. Packages contain data only — no executable code.
- `ThemeInheritanceValidator` validates the complete available definition set: duplicate IDs, invalid
  definitions/ancestors, cycles of any length, missing parents, and invalid chains. Structured errors
  (`ThemeValidationError`, `InheritanceValidation`) carry actionable diagnostics; user-facing copy
  stays separate from internal exception messages.
- Drafts are preview-only and isolated in `theme_drafts`. Persisted definitions always win for active
  resolution, so a colliding draft can never shadow a saved theme. Save, draft, preview, activation,
  and load all validate before any state is published.
- `Aether Black`: `#050505` background, `#141414` surface, `#F3EEE4` foreground, restrained primary,
  0.92-alpha glass surface, no gradients or blur.

## 8. Asset Engine responsibilities

- Categories: wallpaper, icon, theme image, font, thumbnail, other.
- Lifecycle foundation: receive → inspect/validate → stage → process/register → publish → cleanup.
  `AssetEngine.register` promotes a staged file into `originals/<category>/<assetId>.<ext>` and stores
  metadata with a SHA-256 checksum and a relative path.
- `AssetPaths` rejects traversal, absolute paths, empty/`.`/`..` segments, unsafe file names,
  unsupported extensions, and sizes above 128 MiB; canonical path and parent-directory checks stop
  symlink escapes. Moves never overwrite an occupied target.
- `AssetValidator` runs before any registration DB write. It checks actual staged size, declared-vs-
  actual size, category/extension/signature, the declared `staging/<importId>/` path, PNG/JPEG/WebP
  headers, bounded dimensions and WebP chunk lengths, sfnt font headers and table records, UTF-8/NUL/
  JSON constraints for text, canonical paths, and streaming SHA-256. Invalid registration produces
  `AssetValidationError`, leaves no orphaned metadata, and records a `FAILED` import.
- SVG is rejected until a narrowly scoped sanitizer/safe renderer exists; there is no WebView or
  browser execution path for assets. Image validation is header-level and does not fully decode files.
- Explicit states are `STAGED`, `REGISTERED`, `PENDING_TRASH`, `TRASHED`, `PENDING_RESTORE`, and
  `PENDING_DELETE`. Only `REGISTERED` is published by `observeAssets()`.
- Trashing uses a deterministic app-relative path, `trash/<assetId>.<ext>`. The original path is
  reconstructed from the stable asset ID, category, and extension; no absolute path is persisted.
  `delete()` is idempotent for already-trashed assets and `restore()` is idempotent for active assets.
- `restore()` refuses to overwrite an occupied original path. `permanentlyDelete()` removes the
  trashed file and metadata; repeated calls are safe.
- Filesystem/Room coordination is compensating: `PENDING_TRASH`, `PENDING_RESTORE`, and
  `PENDING_DELETE` markers are recovered or reverted by `AssetEngine.recover()`. Room transactions
  keep metadata and import status consistent for database-only steps.
- `cleanupAbandonedStaging()` removes stale staging entries and stale `STAGED` records;
  `cleanupTemporary()` removes unreferenced temporary assets.
- Multiple consumers can reference one asset through `asset_references`. Reference removal targets
  the exact `(assetId, consumerType, consumerId)` row, and deletion is rejected while any valid
  reference remains.
- Temporary assets are deleted permanently; user-managed assets are recoverable from trash.

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
- Theme JSON import has codec + validator but no UI or storage pipeline.
- Legacy metadata-only rows without a stored definition are reported as invalid; no tokens are
  fabricated for them.
- Preview is suspend-based because it reads persisted definitions; no Theme Studio UI exists yet.
- Asset Engine now covers transactional registration, trash, restore, permanent deletion, recovery,
  and cleanup, but there is no byte-stream import UI, media processing, thumbnail generation, or
  trash/restore UI. Recovery is explicit (`AssetEngine.recover()`), not automatic at app startup.
- Checksums are verified against stored metadata during registration and recovery, but not against an
  external manifest. Header-level format checks are not a full media decoder.
- Room schema version 2 has one additive migration from version 1.
- Only local JVM unit tests exist; there are no instrumented/device tests in this milestone.
- No device run was performed; HOME eligibility was verified by manifest inspection, not by changing
  the device's default launcher.

## 12. Future implementation milestones

1. ~~Seed built-in theme metadata and persist full theme definitions; add safe activation with rollback.~~ Completed.
2. ~~Strengthen Theme Engine and Asset Engine validation as a real boundary for persistent state.~~ Completed.
3. Seed installed-app discovery into Room (launcher intent queries are already declared) and build the
   first home-screen grid.
4. Complete the asset import pipeline (inspection UI, processing, thumbnails, trash restore UI).
5. Application drawer, search, folders, and drag-and-drop.
6. Gesture engine, widgets, control center, Studio, and personalization settings.

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
