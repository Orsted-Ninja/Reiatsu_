# StorageSense — Complete Project Development Plan

> **DO NOT START CODING UNTIL THIS ENTIRE DOCUMENT IS READ AND UNDERSTOOD.**
> This is the single source of truth for what to build, how to build it, in what order, and why.

---

## Table of Contents

1. [What Are We Building (One-Line Answer)](#1-what-are-we-building)
2. [Platform Decision & Why](#2-platform-decision--why)
3. [The Non-Negotiable Rules](#3-the-non-negotiable-rules)
4. [What The App Actually Does (User Perspective)](#4-what-the-app-actually-does)
5. [Complete Technology Stack — Every Single Piece](#5-complete-technology-stack)
6. [Project Structure — Folder & Package Layout](#6-project-structure)
7. [Development Approach — How We Build This](#7-development-approach)
8. [Module-by-Module Build Plan](#8-module-by-module-build-plan)
9. [Image Handling — The Full Picture](#9-image-handling)
10. [Duplicate Detection — The Full Picture](#10-duplicate-detection)
11. [How Everything Connects — Data Flow Walkthrough](#11-data-flow-walkthrough)
12. [Development Order — What To Build First](#12-development-order)
13. [Hackathon Timeline (Day-by-Day)](#13-hackathon-timeline)
14. [Model Acquisition — What To Download & From Where](#14-model-acquisition)
15. [Testing Strategy](#15-testing-strategy)
16. [Demo Preparation](#16-demo-preparation)
17. [Risks & Fallback Plans](#17-risks--fallback-plans)
18. [What NOT To Build](#18-what-not-to-build)
19. [Decision Log — Why We Chose What We Chose](#19-decision-log)
20. [Pre-Development Checklist](#20-pre-development-checklist)

---

## 1. What Are We Building

**StorageSense** is an Android app that lets you talk to your phone's storage in plain English. It understands the *content* of your files — not just their names — and can search, organize, deduplicate, and clean up your storage intelligently. Everything runs 100% on-device. No internet. No cloud. No APIs.

**One sentence for judges:**
> "StorageSense lets you manage your entire digital storage using natural language — privately, locally, and safely."

---

## 2. Platform Decision & Why

### Decision: Native Android (Kotlin)

| Option Considered | Verdict | Reason |
|---|---|---|
| **Native Android (Kotlin)** | ✅ **CHOSEN** | Direct access to LiteRT-LM, ONNX Runtime, ML Kit, ObjectBox, filesystem APIs, WorkManager, GPU/NPU delegates. No bridge overhead. Best performance for on-device AI. |
| Flutter / React Native | ❌ Rejected | Cannot directly call LiteRT-LM or ONNX native APIs. Would need JNI bridges for everything. Adds 20-50ms latency per inference call. Debugging AI crashes through a framework bridge is a nightmare at a hackathon. |
| Kotlin Multiplatform | ❌ Rejected | Overkill — we're only targeting Android. Adds build complexity for zero benefit. |
| Python (Termux/Chaquopy) | ❌ Rejected | Python on Android is slow, hacky, and unreliable for production apps. Can't access Android UI toolkit, WorkManager, or filesystem APIs properly. |
| Web App (PWA) | ❌ Rejected | Cannot access local filesystem, cannot run LLMs, cannot access GPU/NPU. Non-starter. |

### Why Not Desktop (Python)?

The transcript and spec say this must run on an **Android phone (Poco F6 / OnePlus Nord 4)** with **8-12GB RAM**. The hackathon requires **local AI only**. A desktop Python app would be easier to build but doesn't match the target. The privacy story is stronger on mobile — "your files never leave your phone."

### Build System

| Tool | Choice |
|---|---|
| **Language** | Kotlin 1.9+ |
| **Build Tool** | Gradle 8.x with Kotlin DSL (`build.gradle.kts`) |
| **Min SDK** | 26 (Android 8.0) — needed for FTS5 support |
| **Target SDK** | 35 (Android 15) |
| **Compile SDK** | 35 |
| **Android Gradle Plugin** | 8.3+ |

---

## 3. The Non-Negotiable Rules

These rules come directly from the transcript.txt and StorageSense.pdf. They are NOT optional.

### Rule 1: The LLM Never Touches Files
```
User speaks → LLM parses intent → Deterministic engine finds files → User sees preview → User confirms → Engine acts
```
The LLM produces a JSON intent. A normal Kotlin function matches files. The user sees what will happen. The user clicks "Confirm." Only then does anything get deleted/moved/archived. **No exceptions.**

### Rule 2: Zero Cloud, Zero Network
- The app must declare `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>` in the manifest.
- Architecturally impossible to leak data. No HTTP clients. No analytics. No crash reporting that phones home.

### Rule 3: Reversible Destructive Actions
- "Delete" means "move to `.storagesense/trash/`" (recoverable for 30 days).
- Permanent delete is a separate, clearly-labeled action.
- Archive means compress + move, with the original path recorded for undo.

### Rule 4: Everything Works Offline
- All models are pre-loaded on the device.
- All processing happens on-device.
- The app must work in airplane mode.

---

## 4. What The App Actually Does

### The Five Core User Flows

| # | User Says | What Happens Under The Hood | Feature Name |
|---|---|---|---|
| 1 | "Find all my DBMS notes" | BM25 keyword search + Vector semantic search → RRF fusion → Show matching PDFs, DOCXs, PPTs even if filenames don't say "DBMS" | **Semantic Search** |
| 2 | "Delete screenshots of handwritten notes" | OCR extracts text from images → Vision model identifies handwritten content → MobileCLIP matches "handwritten notes" concept → Shows preview → User confirms deletion | **Image-Aware Search + Action** |
| 3 | "Remove duplicate assignments, keep latest" | SHA-256 hash for exact dupes + Vector similarity for near-dupes → Group by content similarity → Sort each group by date → Propose deleting older copies | **Duplicate Detection** |
| 4 | "Free up 5 GB without deleting important" | Score all files by (size × staleness × replaceability) → Exclude files matching "important" heuristics → Propose largest safe-to-remove files until 5 GB reached | **Smart Cleanup** |
| 5 | "Find documents containing Kubernetes" | Pure BM25/FTS5 keyword search → Instant results → Highlight matched terms | **Keyword Search** |

### File Types We Must Handle

| Category | Extensions | How We Process Them |
|---|---|---|
| **PDFs** | `.pdf` | PdfBox-Android extracts text → Chunk → Embed → FTS5 + ObjectBox |
| **Word Docs** | `.docx` | ZipInputStream + XmlPullParser extracts `<w:t>` tags → Chunk → Embed |
| **Presentations** | `.pptx` | ZipInputStream + XmlPullParser extracts `<a:t>` tags per slide → Chunk → Embed |
| **Plain Text** | `.txt`, `.md`, `.csv` | Read directly → Chunk → Embed |
| **Images** | `.jpg`, `.png`, `.webp` | ML Kit OCR (text extraction) + MobileCLIP (image embedding) → FTS5 + ObjectBox |
| **Screenshots** | `.png`, `.jpg` | Same as images, but also use Gemma Vision on-demand for classification |
| **Archives** | `.zip` | List contents, index metadata. Don't extract (too slow for indexing). |
| **Installers/Junk** | `.apk`, `.exe`, `.msi` | Metadata only (name, size, date). No content extraction. Used for cleanup heuristics. |
| **Videos** | `.mp4`, `.mkv` | Metadata only (name, size, date, duration via MediaMetadataRetriever). Content indexing too expensive. |

---

## 5. Complete Technology Stack

### AI Models (Downloaded Pre-Hackathon)

| Model | Purpose | Format | Size | Source |
|---|---|---|---|---|
| **Gemma 4 E4B** | Main LLM — intent parsing, RAG answers, vision | `.litertlm` (Q4) | ~3.5 GB | HuggingFace litert-community |
| **Gemma 4 E2B** | Fallback LLM for low-RAM situations | `.litertlm` (Q4) | ~2.6 GB | HuggingFace litert-community |
| **all-MiniLM-L6-v2** | Text embedding (384-dim) | `.onnx` (INT8) | ~22 MB | HuggingFace sentence-transformers |
| **MobileCLIP-S2** | Image embedding (512-dim) + text-to-image search | `.onnx` | ~50 MB | HuggingFace apple/MobileCLIP |
| **ML Kit Text Recognition v2** | On-device OCR | Bundled in APK | ~20 MB | Google ML Kit (Gradle dependency) |

**Total model storage: ~6.2 GB** (E2B is optional fallback, so ~3.7 GB minimum)

### Android Libraries

| Category | Library | Gradle Coordinate | Why This One |
|---|---|---|---|
| **LLM Runtime** | LiteRT-LM | `com.google.ai.edge:litertlm` | Google's official on-device LLM runtime. Replaces deprecated MediaPipe. Auto GPU/NPU delegation. |
| **Embedding Runtime** | ONNX Runtime Mobile | `com.microsoft.onnxruntime:onnxruntime-android:1.18.0` | Run MiniLM and MobileCLIP. Vulkan/NNAPI acceleration. |
| **OCR** | ML Kit Text Recognition | `com.google.mlkit:text-recognition:16.0.1` | Bundled mode = fully offline. Best accuracy/speed ratio. |
| **Vector DB** | ObjectBox | `io.objectbox:objectbox-kotlin:4.0.0` | Built-in HNSW index. Designed for mobile. Disk-backed. Kotlin-first. |
| **Relational DB** | Room | `androidx.room:room-ktx:2.6.1` | SQLite wrapper. Hosts FTS5 tables and file metadata. |
| **Full-Text Search** | SQLite FTS5 | Built into Android (API 24+) | BM25 ranking via `bm25()` function. Zero dependencies. |
| **PDF Parsing** | PdfBox-Android | `com.tom-roush:pdfbox-android:2.0.27.0` | Apache PDFBox port. No AWT dependencies. |
| **DOCX Parsing** | Pure Kotlin | Zero dependencies | `.docx` = ZIP → `word/document.xml` → `<w:t>` tags. 20 lines of code. |
| **PPTX Parsing** | Pure Kotlin | Zero dependencies | `.pptx` = ZIP → `ppt/slides/slide*.xml` → `<a:t>` tags. |
| **DOCX Fallback** | Mammoth | `org.zwobble.mammoth:mammoth:1.5.0` | For complex DOCX files where the pure-Kotlin parser misses formatting. |
| **Background Work** | WorkManager | `androidx.work:work-runtime-ktx:2.9.0` | Survives app kills. Constraint-based scheduling. |
| **UI** | Jetpack Compose + Material 3 | `androidx.compose.material3:material3` | Modern declarative UI. Streaming LLM text rendering. |
| **DI** | Hilt | `com.google.dagger:hilt-android:2.50` | Standard Android DI. Singleton model sessions. |
| **Image Loading** | Coil | `io.coil-kt:coil-compose:2.6.0` | Lightweight image loading for file thumbnails in search results. |

### What We Are NOT Using (And Why)

| Rejected Tool | Why Rejected |
|---|---|
| **Apache POI** | Requires `java.awt.*` (not on Android). Adds 35+ MB to APK. Crashes at runtime with NoClassDefFoundError. |
| **FAISS** | No Android SDK. Requires manual NDK/JNI build. Overkill for <1M vectors. |
| **ChromaDB** | Server-based Python. Can't run embedded on Android. |
| **LangChain** | Python-only. Massive overhead. We need 20 lines of RRF, not a framework. |
| **NNAPI** | Deprecated in Android 15. LiteRT-LM uses Vulkan/QNN instead. |
| **MediaPipe LLM** | Maintenance-only mode. LiteRT-LM is its replacement with better performance. |
| **Ollama / llama.cpp** | Could work but LiteRT-LM gives better Qualcomm NPU support for Gemma models specifically. |

---

## 6. Project Structure

```
StorageSense/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   ├── models/
│   │   │   │   ├── minilm_int8.onnx              # Text embedding (~22 MB)
│   │   │   │   └── mobileclip_s2.onnx             # Image embedding (~50 MB)
│   │   │   └── tokenizer/
│   │   │       └── vocab.txt                       # WordPiece vocab for MiniLM
│   │   ├── java/com/storagesense/app/
│   │   │   ├── StorageSenseApp.kt                  # Application class (@HiltAndroidApp)
│   │   │   ├── di/                                 # Dependency Injection
│   │   │   │   ├── AppModule.kt
│   │   │   │   ├── AIModule.kt
│   │   │   │   └── RepositoryModule.kt
│   │   │   ├── data/                               # Data Layer
│   │   │   │   ├── local/room/                     # Room DB + FTS5
│   │   │   │   │   ├── StorageSenseDatabase.kt
│   │   │   │   │   ├── FileMetadataDao.kt
│   │   │   │   │   ├── SearchDao.kt
│   │   │   │   │   └── entity/
│   │   │   │   ├── local/objectbox/                # ObjectBox vector DB
│   │   │   │   │   ├── ObjectBoxSetup.kt
│   │   │   │   │   └── entity/
│   │   │   │   ├── extractor/                      # Text Extraction
│   │   │   │   │   ├── DocumentExtractor.kt (interface)
│   │   │   │   │   ├── PdfExtractor.kt
│   │   │   │   │   ├── DocxExtractor.kt
│   │   │   │   │   ├── PptxExtractor.kt
│   │   │   │   │   ├── PlainTextExtractor.kt
│   │   │   │   │   └── ExtractorFactory.kt
│   │   │   │   └── repository/                     # Repo implementations
│   │   │   ├── domain/                             # Domain Layer
│   │   │   │   ├── model/                          # StorageIntent, SearchResult, etc.
│   │   │   │   ├── repository/                     # Interfaces
│   │   │   │   └── usecase/                        # HybridSearchUseCase, etc.
│   │   │   ├── ai/                                 # AI Model Wrappers
│   │   │   │   ├── ModelManager.kt                 # Load/unload lifecycle
│   │   │   │   ├── embedding/TextEmbeddingModel.kt
│   │   │   │   ├── clip/MobileCLIPModel.kt
│   │   │   │   ├── ocr/OcrEngine.kt
│   │   │   │   ├── llm/LlmSession.kt
│   │   │   │   ├── llm/IntentParser.kt
│   │   │   │   └── vision/VisionAnalyzer.kt
│   │   │   ├── search/                             # Search Engine
│   │   │   │   ├── Bm25SearchEngine.kt
│   │   │   │   ├── VectorSearchEngine.kt
│   │   │   │   ├── RrfFusion.kt
│   │   │   │   ├── HybridSearchEngine.kt
│   │   │   │   └── DuplicateDetector.kt
│   │   │   ├── indexing/                           # Indexing Pipeline
│   │   │   │   ├── FileScanner.kt
│   │   │   │   ├── TextChunker.kt
│   │   │   │   ├── IndexingPipeline.kt
│   │   │   │   └── ImageIndexer.kt
│   │   │   ├── action/                             # Action Engine
│   │   │   │   ├── ActionEngine.kt
│   │   │   │   ├── SafeFileOps.kt
│   │   │   │   └── SpaceReclaimer.kt
│   │   │   ├── worker/                             # Background Workers
│   │   │   │   ├── FullScanWorker.kt
│   │   │   │   ├── IncrementalScanWorker.kt
│   │   │   │   └── DocumentIndexingWorker.kt
│   │   │   └── ui/                                 # Presentation Layer
│   │   │       ├── theme/
│   │   │       ├── navigation/NavGraph.kt
│   │   │       ├── chat/ChatScreen.kt + ChatViewModel.kt
│   │   │       ├── dashboard/DashboardScreen.kt
│   │   │       └── settings/SettingsScreen.kt
│   │   └── res/
│   └── src/test/                                   # Unit tests
│   └── src/androidTest/                            # Instrumented tests
├── build.gradle.kts                                # Root build file
├── settings.gradle.kts
└── gradle.properties
```

**Note on LLM storage:** Gemma 4 E4B (~3.5 GB) is too large for `assets/`. It gets pushed to the phone via `adb push` to `/sdcard/StorageSense/models/` and loaded from that path at runtime. Only the small embedding models (~72 MB combined) go in `assets/`.

---

## 7. Development Approach

### Philosophy: Bottom-Up, Data-First, AI-Last

```
Step 1: Can we READ files?           → Extractors
Step 2: Can we STORE the data?       → Room + ObjectBox + FTS5
Step 3: Can we SEARCH by keyword?    → BM25 (works without any AI model)
Step 4: Can we SEARCH by meaning?    → Embedding model + Vector search
Step 5: Can we SEARCH images?        → OCR + CLIP
Step 6: Can we MERGE search results? → RRF fusion
Step 7: Can we FIND duplicates?      → Hash + semantic similarity
Step 8: Can we UNDERSTAND intent?    → LLM intent parser
Step 9: Can we ACT on intent?        → Action engine
Step 10: Can we PRESENT it nicely?   → UI
```

**Why this order?**
- Steps 1-3 give you a working (non-AI) search app. If everything else fails, you still have a demo.
- Steps 4-6 add the "wow factor" (semantic search). Still works without the LLM.
- Steps 7-8 add intelligence. The LLM is the LAST thing you integrate, not the first.
- Steps 9-10 are polish.

### Verification At Every Step

| Module | How To Verify It Works |
|---|---|
| Extractors | Unit test: feed a PDF, check extracted text contains expected strings |
| Room + ObjectBox | Instrumented test: insert and query data |
| BM25 | Insert 10 chunks, search "DBMS", verify top result is DBMS content |
| Embedding model | Embed two similar sentences, check cosine similarity > 0.7 |
| Vector search | Insert 10 embeddings, query, verify nearest neighbor is correct |
| RRF | Two ranked lists → fused list has expected order |
| Duplicate detector | Identical files → detected; similar files → detected; different files → not |
| LLM | Type query, check JSON output is parseable |
| Action engine | Mock file operations, verify trash folder contains moved file |

---

## 8. Module-by-Module Build Plan

### 8.1 Module 1: Project Skeleton & Build System

**What to do:**
1. Create new Android project in Android Studio (Empty Compose Activity)
2. Package name: `com.storagesense.app`, min SDK 26, target SDK 35
3. Add ALL Gradle dependencies upfront (don't add them one by one — wastes Gradle sync time)
4. Create the package structure (all empty packages)
5. Add `StorageSenseApp.kt` with `@HiltAndroidApp`
6. AndroidManifest.xml permissions: `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`. **REMOVE** `INTERNET`.
7. Run the app on device to verify it compiles and launches

**Time:** 1-2 hours

### 8.2 Module 2: File Scanning & Metadata Collection

**What to do:**
1. `FileScanner.kt` — walks directories recursively, collects path/name/extension/size/lastModified
2. Computes SHA-256 hash per file (for files > 10 MB, hash first 1 MB + last 1 MB + file size as fast approximate)
3. `FileMetadataEntity.kt` Room entity, `FileMetadataDao.kt`
4. Room database class

**Key decision — Scoped Storage:** Use `MANAGE_EXTERNAL_STORAGE` (allowed for file manager apps). Fallback to `MediaStore` + SAF if needed.

**Time:** 2 hours

### 8.3 Module 3: Text Extraction

**PDF:** PdfBox-Android → `PDFTextStripper().getText(document)`. If extracted text < 30 chars/page → flag for OCR.

**DOCX (Zero Dependencies):** Open as `ZipInputStream` → find `word/document.xml` → `XmlPullParser` → collect `<w:t>` tags.

**PPTX (Zero Dependencies):** Same approach → find `ppt/slides/slide*.xml` → collect `<a:t>` tags.

**Plain Text:** `File.readText()`

**ExtractorFactory:** Returns correct extractor based on file extension.

**Key decision — NO Apache POI.** It requires `java.awt.*` (absent on Android), bloats APK by 35+ MB, and crashes at runtime. The 20-line zip parser handles 95% of files. Mammoth library is the fallback for complex DOCX.

**Time:** 2-3 hours

### 8.4 Module 4: Image Understanding

**Three components:**

| Component | What It Does | When It Runs | Speed |
|---|---|---|---|
| **ML Kit OCR** | Extracts visible text from images | Background indexing (every image) | ~50ms |
| **MobileCLIP** | Generates 512-dim image embedding | Background indexing (every image) | ~20ms |
| **Gemma Vision** | Detailed description + classification | On-demand (user asks about specific image) | ~2-3 sec |

See [Section 9 (Image Handling)](#9-image-handling) for the full deep-dive.

**Time:** 3-4 hours

### 8.5 Module 5: Text Chunking & Embedding

**Chunker:** Recursive character splitting. Chunk size = 400 tokens, overlap = 80 tokens. Split by `\n\n` → `\n` → `. ` → ` `.

**Embedding Model:** ONNX Runtime wrapper for all-MiniLM-L6-v2. Input: tokenized text. Output: 384-dim float array. Requires WordPiece tokenizer implementation.

**Key decision — Tokenizer:** MiniLM uses WordPiece. You need a Kotlin implementation or a compiled tokenizer. Port the HuggingFace logic (~100 lines) or use a simplified whitespace + vocab lookup.

**Verification:** Embed "database management system" and "DBMS relational SQL" → cosine similarity > 0.5. Embed "database" and "chocolate cake" → similarity < 0.2.

**Time:** 3-4 hours

### 8.6 Module 6: Storage Layer

**Room:** FileMetadata entity + FTS5 virtual table (created via raw SQL migration).

**ObjectBox:** `DocumentChunkEntity` with `@HnswIndex(dimensions = 384)`. `ImageIndexEntity` with `@HnswIndex(dimensions = 512)`.

**Key decision — Two databases:** Room (SQLite) for metadata + FTS5. ObjectBox for vectors. They serve different purposes. Don't force everything into one.

**Time:** 2 hours

### 8.7 Module 7: BM25 Search

FTS5 MATCH query with `bm25()` ranking. Column weights: filename = 5.0, content = 1.0.

Note: `bm25()` returns negative values (more negative = better match). `ORDER BY bm25_score` naturally sorts best-first.

**Pitfall:** FTS5 crashes on special characters. Sanitize input.

**Time:** 1.5 hours

### 8.8 Module 8: Vector Search

ObjectBox HNSW nearest neighbor query. Default HNSW params are fine (neighborsPerNode = 30).

**Time:** 1 hour

### 8.9 Module 9: Hybrid Search & RRF Fusion

Run BM25 + Vector in parallel (`coroutineScope { async {} }`). Feed both lists into RRF (k=60). Apply metadata filters after fusion.

**Why RRF:** Uses only ranks, not scores. No normalization needed. No tuning needed. k=60 works universally.

**Time:** 1.5 hours

### 8.10 Module 10: Duplicate Detection

Three strategies: hash-based exact dupes, semantic text similarity (threshold 0.85), CLIP image similarity (threshold 0.90). See [Section 10](#10-duplicate-detection) for full details.

**Time:** 2-3 hours

### 8.11 Module 11: LLM Integration

**Intent Parser:** System prompt → LLM outputs JSON with action/query/filters → parse to `StorageIntent` sealed class. Regex fallback for malformed JSON.

**RAG Engine:** Top 3-5 chunks as context → keep prompt under 1500 tokens → stream response.

**Biggest risk:** Small LLMs produce malformed JSON 20-30% of the time. Always try-catch. Test with 20 queries before demo.

**Time:** 4 hours

### 8.12 Module 12: Action Engine

`proposeAction()` → returns preview. `executeApproved()` → runs only after user confirms.

Delete = move to `.storagesense/trash/`. Every action logged to JSON for undo.

**Time:** 2 hours

### 8.13 Module 13: Background Indexing

WorkManager workers: FullScanWorker (first launch), IncrementalScanWorker (periodic), DocumentIndexingWorker (per-file).

Batch embedding: 16-32 chunks per ONNX call (10x faster than one-at-a-time). Foreground service for full scans.

**Time:** 3 hours

### 8.14 Module 14: Memory Manager

Singleton `ModelManager`. Core rule: **Embedding + LLM NEVER coexist in RAM simultaneously.**

State machine: IDLE → EMBEDDING_LOADED → (unload) → LLM_LOADED → (idle 60s) → IDLE

Check `ActivityManager.getMemoryInfo()` before loading. Register `ComponentCallbacks2` for low-memory events.

**Time:** 2 hours

### 8.15 Module 15: UI Layer

**Chat (primary):** Message list + streaming text + FileResultCards + ActionApprovalSheet.

**Dashboard:** Storage pie chart + indexing progress + quick actions.

**Settings:** Model selection, directories to scan, trash duration.

Chat is primary. Don't over-invest in dashboard.

**Time:** 4-5 hours

---

## 9. Image Handling — The Full Picture

### Two-Tier Architecture

```
TIER 1: FAST PATH (Background Indexing — Every Image, ~50ms each)
├── ML Kit OCR → Extract any visible text (receipts, screenshots, notes)
├── MobileCLIP → Generate 512-dim image embedding (visual meaning)
├── File metadata → EXIF data, resolution, file size, creation date
└── Store: OCR text → FTS5, CLIP embedding → ObjectBox, metadata → Room

TIER 2: DEEP PATH (On-Demand — Only When User Asks, ~2-3 sec each)
├── Gemma 4 E4B Vision → Detailed description + classification
├── Categories: screenshot / photo / document / meme / receipt / diagram / handwritten_note
└── Cache result in ImageIndex entity for future queries
```

**Why two tiers?** Fast path can index 1000 images in <1 minute. Deep path takes ~2-3 seconds PER image. Background-indexing every image through the LLM would take 50+ minutes and drain the battery.

### Query Routing

| User Query | Search Method |
|---|---|
| "Find screenshots of my notes" | CLIP text→image embedding search |
| "Find images with text about Kubernetes" | BM25 on OCR-extracted text |
| "What is this image?" (user selects one) | Gemma Vision (deep path) |
| "Delete memes from Downloads" | CLIP identifies → Gemma confirms → propose deletion |
| "Find photos similar to this one" | CLIP image→image similarity search |
| "Find duplicate photos" | CLIP embedding cosine similarity > 0.90 → group |

### MobileCLIP Preprocessing (Critical — Get This Wrong = Garbage)

1. Resize image to 256×256 (center crop, NOT stretch)
2. Convert pixels to float [0.0, 1.0]
3. Normalize: `(pixel - mean) / std`
   - mean = [0.48145466, 0.4578275, 0.40821073]
   - std = [0.26862954, 0.26130258, 0.27577711]
4. Output tensor shape: [1, 3, 256, 256] (NCHW)

### Scanned PDF → OCR Fallback

When PDF text extractor returns < 30 chars/page → render each page to Bitmap via `PdfRenderer` → run ML Kit OCR → treat OCR text as regular text → chunk → embed → index.

---

## 10. Duplicate Detection — The Full Picture

### Three Levels

**Level 1: Exact Duplicates (SHA-256 Hash)**
- Group files by hash. Any group with 2+ files = exact duplicates.
- Catches: copied files, downloaded twice.
- Works on ALL file types including videos.
- Speed: O(n)

**Level 2: Near-Duplicate Documents (Semantic Similarity)**
- For each document's chunks, find chunks from other documents with cosine similarity > 0.85.
- Handles: `Assignment_v1.docx` vs `Assignment_Final_CORRECTED.docx`.
- Uses ObjectBox HNSW (O(n log n)), NOT brute-force O(n²).
- Threshold: 0.85 average similarity.

**Level 3: Near-Duplicate Images (CLIP Similarity)**
- For each image, find others with CLIP embedding cosine similarity > 0.90.
- Handles: resized copies, compressed copies, slightly cropped versions.
- Threshold: 0.90 (higher than text because different photos still have moderate similarity).

### Which File To Keep?

1. **Most recently modified** (likely has latest edits)
2. If dates equal: **larger file** (likely higher quality)
3. If both equal: **shorter path** (more accessible)

---

## 11. Data Flow Walkthrough

### Example: "Find all my DBMS notes"

```
Step 1:  UI → ChatViewModel receives "Find all my DBMS notes"
Step 2:  ModelManager loads Gemma 4 E4B (~3 sec if cold, instant if warm)
Step 3:  IntentParser → LLM → JSON: {"action":"SEARCH", "query":"DBMS database management notes"}
Step 4:  ModelManager unloads LLM, loads MiniLM embedding model (~200ms)
Step 5:  HybridSearchEngine runs BM25 + Vector search IN PARALLEL:
           5a. BM25: FTS5 MATCH "DBMS database management notes" → 50 chunks
           5b. Vector: embed query → ObjectBox HNSW → 50 nearest chunks
Step 6:  RRF merges both lists → top 20
Step 7:  Metadata enrichment: attach file name, path, size from Room
Step 8:  Filter by file_types (pdf, docx, pptx)
Step 9:  ModelManager unloads embedding, loads LLM again
Step 10: RAG prompt with top 5 results as context → stream response
Step 11: UI renders streaming text + FileResultCards

Total: ~5-8 seconds (dominated by LLM load/inference)
```

**Key insight:** The LLM loads/unloads TWICE (intent parsing + RAG response). This is the reality of 8GB RAM constraints. Optimization: for simple queries (starts with "Find"/"Search"), skip intent parsing entirely.

---

## 12. Development Order

```
PHASE 1: FOUNDATION ── App lists files (no AI)
  Module 1 (Skeleton) → Module 2 (Scanner) → Module 6 (Storage)

PHASE 2: TEXT EXTRACTION ── App reads file contents (no AI)
  Module 3 (PDF/DOCX/PPTX/TXT extractors)

PHASE 3: KEYWORD SEARCH ── First demo-able milestone
  Module 5 (Chunker only) → Module 7 (BM25) → Basic Chat UI
  ✅ CHECKPOINT: Type "DBMS" → see matching chunks

PHASE 4: SEMANTIC SEARCH ── The wow factor
  Module 5 (Embedding model) → Module 8 (Vector) → Module 9 (RRF)
  ✅ CHECKPOINT: "database notes" matches files named "DBMS"

PHASE 5: IMAGE UNDERSTANDING
  Module 4 (OCR + CLIP + Image indexer)
  ✅ CHECKPOINT: "screenshots with text about databases" works

PHASE 6: INTELLIGENCE ── LLM + Actions
  Module 14 (Memory) → Module 11 (LLM) → Module 10 (Duplicates) → Module 12 (Actions)
  ✅ CHECKPOINT: Full natural language interface

PHASE 7: BACKGROUND + POLISH
  Module 13 (Workers) → Module 15 (Dashboard, Settings)

PHASE 8: DEMO PREP
  Pre-index demo data → Rehearse 5-minute script
```

**Even if you only finish Phase 1-4, you have a demo-worthy app** (hybrid semantic search). Everything after is progressively better but not required.

---

## 13. Hackathon Timeline

### 3-Day Hackathon

| Day | Morning (4h) | Afternoon (4h) | Evening (3h) |
|---|---|---|---|
| **Day 1** | Phase 1: Skeleton + Scanner + DB | Phase 2 + 3: Extractors + BM25 + basic UI | Phase 4: Embeddings + Vector + RRF |
| **Day 2** | Phase 5: Image pipeline | Phase 6: Memory + LLM + Intent parser | Phase 6 contd: Duplicates + Actions |
| **Day 3** | Phase 7: Workers + Dashboard | Phase 8: Demo prep + polish | Buffer + rehearsal |

### 24-Hour Hackathon (Cut scope)

| Hours | What |
|---|---|
| 0-4 | Phase 1-2: Skeleton + Scanner + Extractors |
| 4-8 | Phase 3: BM25 + Basic UI |
| 8-14 | Phase 4: Embeddings + Vector + RRF |
| 14-18 | Phase 6: LLM + Intent parsing (skip images) |
| 18-22 | Duplicates + Actions + Polish |
| 22-24 | Demo prep |

---

## 14. Model Acquisition

### Download BEFORE The Hackathon

```bash
# Gemma 4 E4B
huggingface-cli download litert-community/gemma4-e4b-q4 --local-dir ./models/

# Gemma 4 E2B (fallback)
huggingface-cli download litert-community/gemma4-e2b-q4 --local-dir ./models/

# MiniLM → ONNX INT8
pip install optimum onnxruntime
optimum-cli export onnx --model sentence-transformers/all-MiniLM-L6-v2 minilm_onnx/
python -c "
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic('minilm_onnx/model.onnx', 'minilm_int8.onnx', weight_type=QuantType.QInt8)
"

# Push LLM to phone
adb push models/gemma4-e4b-q4.litertlm /sdcard/StorageSense/models/

# WordPiece vocab
wget https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt
```

### CRITICAL: Test models on device BEFORE the hackathon. Don't discover issues during the event.

---

## 15. Testing Strategy

### Unit Tests (JVM — no device)

- `TextChunkerTest` — size limits, overlap, empty input
- `RrfFusionTest` — merge correctness, k parameter
- `IntentParserTest` — JSON parsing, malformed JSON handling
- `DocxExtractorTest` — ZIP parsing extracts correct text
- `SpaceReclaimerTest` — priority ordering, "important" exclusion

### Instrumented Tests (on device)

- `RoomDatabaseTest` — FTS5 creation, BM25 ranking
- `ObjectBoxTest` — HNSW insert + nearest neighbor
- `EmbeddingModelTest` — ONNX loads, produces 384-dim vector
- `EndToEndSearchTest` — index 10 files → search → verify results

### Manual Checklist (Before Demo)

- [ ] App launches without crash
- [ ] "Find all my DBMS notes" returns relevant results
- [ ] "Find documents containing Kubernetes" returns keyword matches
- [ ] Image search finds screenshots with specific text
- [ ] Duplicate detection works (exact + near-duplicates)
- [ ] "Free up 5 GB" produces suggestions
- [ ] Delete moves to trash (not permanent)
- [ ] Works in airplane mode
- [ ] No OOM crashes

---

## 16. Demo Preparation

### Demo Dataset (Pre-Load)

- 5-6 PDFs with overlapping topics (DBMS with various filenames)
- 2-3 DOCX files (including duplicate pair: v1 and final version)
- 1 resume PDF (should be protected from cleanup)
- 5 screenshots of handwritten notes
- 3 meme images
- 2 receipt photos
- 5 old APK files in Downloads
- 3 large video files (>500 MB each)

**Pre-index EVERYTHING before demo. Never index live.**

### 5-Minute Demo Script

```
[0:00] Intro: "StorageSense understands the MEANING of your files."

[0:30] DEMO 1 — Semantic Search:
       "Find all my DBMS notes" → Shows 4 files with different names

[1:30] DEMO 2 — Image Understanding:
       "Show me screenshots of handwritten notes" → OCR + CLIP results

[2:30] DEMO 3 — Duplicates:
       "Find duplicate assignments, keep latest" → Groups shown → Confirm

[3:30] DEMO 4 — Smart Cleanup:
       "Free up 5 GB" → Breakdown shown, resume protected → Confirm

[4:30] CLOSING:
       "Everything ran on this phone. No cloud. No API. Private, local, safe."
```

---

## 17. Risks & Fallback Plans

| Risk | Fallback |
|---|---|
| **LLM OOM on 8GB** | Use E2B (2.6 GB). Reduce maxTokens. Skip RAG, show raw results. |
| **LLM malformed JSON (30% rate)** | Regex fallback parser. Test 20 queries to tune prompt. |
| **ONNX model won't load** | Try MediaPipe Text Embedder (`.tflite`). Different API, same result. |
| **PdfBox crashes on specific PDF** | Catch exception, skip file. Try PdfRenderer + OCR fallback. |
| **MobileCLIP garbage embeddings** | Double-check preprocessing normalization. Fall back to OCR-only for images. |
| **Indexing too slow at demo** | Pre-index everything. Have backup pre-indexed DB. |
| **Android kills app during inference** | Foreground service. Monitor `MemoryInfo.lowMemory`. |
| **Model download fails at hackathon** | Download ALL models beforehand. Carry on USB drive. |

---

## 18. What NOT To Build

| Feature | Why Skip |
|---|---|
| Cloud sync | Violates "local only" |
| Multi-device mesh | Out of scope per spec |
| Video transcription | Too compute-heavy for mobile |
| Real-time FileObserver | Unreliable on modern Android — use periodic scans |
| LoRA training on device | Unnecessary — pre-trained models suffice |
| Custom voice input | Use Android's built-in STT |
| Analytics / crash reporting | Requires internet |

---

## 19. Decision Log

| Decision | Chosen | Rationale |
|---|---|---|
| Platform | Android native Kotlin | Direct LiteRT-LM/ONNX/ML Kit access. No bridge overhead. |
| LLM | Gemma 4 E4B | Native multimodal. Best LiteRT-LM integration. Designed for edge. |
| LLM Runtime | LiteRT-LM | Google official. Auto GPU/NPU. Replaces deprecated MediaPipe. |
| Embedding | all-MiniLM-L6-v2 (22 MB) | Smallest footprint. Proven. Leaves RAM for LLM. |
| Image Embedding | MobileCLIP-S2 | Purpose-built for mobile. 50 MB. Cross-modal search. |
| Vector DB | ObjectBox | Built-in HNSW. Kotlin-first. Mobile-optimized. Disk-backed. |
| Full-Text | SQLite FTS5 | Zero deps. Built into Android. Native BM25. |
| Fusion | RRF (k=60) | No normalization. Zero tuning. Production-proven. |
| PDF Parser | PdfBox-Android | Apache license. No AWT deps. |
| DOCX Parser | Pure Kotlin Zip+XML | Zero deps. 20 lines. POI bloats 35 MB + crashes. |
| OCR | ML Kit (bundled) | Best accuracy. Fastest. Offline. |
| Delete | Move to trash | Reversible. Safer. User trust. |
| Text similarity threshold | 0.85 | Below 0.80 too many false positives. Above 0.90 misses edits. |
| Image similarity threshold | 0.90 | Different photos still have moderate similarity — need higher bar. |

---

## 20. Pre-Development Checklist

### Hardware
- [ ] Target phone available and charged
- [ ] USB cable works for ADB
- [ ] Developer mode + USB debugging enabled
- [ ] `adb devices` shows phone

### Software
- [ ] Android Studio latest stable
- [ ] SDK 35 installed
- [ ] Kotlin plugin updated

### Models (DO THIS NOW)
- [ ] Gemma 4 E4B downloaded (~3.5 GB)
- [ ] Gemma 4 E2B downloaded (~2.6 GB)
- [ ] all-MiniLM-L6-v2 ONNX INT8 exported (~22 MB)
- [ ] MobileCLIP-S2 ONNX exported (~50 MB)
- [ ] vocab.txt downloaded
- [ ] ALL models tested on device — loads and runs inference

### Demo Data
- [ ] Sample PDFs prepared
- [ ] Duplicate DOCX files created
- [ ] Screenshots and images collected
- [ ] Old APK files placed in Downloads
- [ ] All files pushed to phone

### Team
- [ ] Everyone has read this document
- [ ] Each person knows their assigned modules
- [ ] Everyone understands: build Phase 1 → 8, in order
- [ ] Everyone knows: LLM NEVER touches filesystem

---

> **The most important thing:** Even if you only finish Phases 1-4, you have a demo-worthy app. Build the foundation solid. The AI is icing on the cake.
