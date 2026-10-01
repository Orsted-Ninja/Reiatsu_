# StorageSense

An intelligent, 100% on-device, privacy-first Android storage management application powered by local AI.

## Highlights

- **Zero Cloud, Zero Network:** Completely isolated on-device operation. `android.permission.INTERNET` is explicitly removed.
- **Natural Language Storage Commands:** Conversational assistant translating user requests into deterministic, reversible actions.
- **Deterministic Action Engine & Safety:** Two-phase execution with mandatory previews and explicit user confirmation before any action.
- **Reversible Trash:** Deleted files are staged to `.storagesense/trash/` (recoverable for 30 days) with full audit and undo logs.
- **Hybrid Search Engine:** Combines SQLite FTS4 full-text search (BM25/TF-IDF) with on-device vector embeddings using Reciprocal Rank Fusion (RRF).
- **Document & Image Indexing:** Supports PDF, DOCX, PPTX, TXT, and on-device OCR via ML Kit.
- **Smart Duplicate & Space Cleanup:** SHA-256 duplicate detection and categorized space reclaimer.

---

## Architecture & Tech Stack

- **Language:** Kotlin 2.0
- **UI:** Jetpack Compose + Material 3 Design
- **Architecture:** Clean Architecture + MVVM + Repository Pattern
- **Dependency Injection:** Dagger Hilt
- **Database:** Room + SQLite FTS4
- **Concurrency:** Kotlin Coroutines & StateFlow
- **ML / AI:** OnnxRuntime Android, Bundled ML Kit OCR, WordPiece Tokenizer
- **Target SDK:** Android 15 / 16 (API 35/36)
- **Min SDK:** Android 8.0 (API 26)

---

## Project Structure & Git Sharing Rules

When sharing this Android project to GitHub, standard Android development practices are configured via `.gitignore`:

### Included in Repository
- `app/src/` — All Kotlin source code, Jetpack Compose UI, resources, assets, and Android manifest.
- `gradle/` & `gradlew` / `gradlew.bat` — Gradle wrapper so anyone can clone and build without pre-installing Gradle.
- `build.gradle.kts` & `settings.gradle.kts` — Project build configuration.
- `gradle/libs.versions.toml` — Version catalog.

### Excluded via `.gitignore` (Never committed)
- `local.properties` — Contains local machine-specific SDK paths.
- `build/` & `app/build/` — Generated build outputs and intermediate files.
- `*.apk`, `*.aab` — Generated application binaries.
- `.idea/`, `.gradle/` — IDE and cache directories.
- `*.jks`, `*.keystore` — Signing keys and secrets.

---

## Building from Terminal

Clone the repository and build using the included Gradle Wrapper:

```bash
# Clone the repository
git clone https://github.com/<your-username>/<repo-name>.git
cd <repo-name>

# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Install to connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License
MIT License
