# StorageSense Android Architecture & Engineering Guide (gemini.md)

## 1. Project Overview & Zero-Cloud Guarantee

**StorageSense** is an intelligent, privacy-first on-device storage assistant for Android. It operates under a strict **Zero-Cloud, Zero-Network** architecture:
- `android.permission.INTERNET` is explicitly omitted from `AndroidManifest.xml`.
- 100% of natural language understanding, semantic search, optical character recognition (OCR), text embeddings, and file actions occur locally on the physical Android device.
- Zero telemetry, analytics, user data, or embeddings ever leave the phone.

---

## 2. On-Device AI & Neural Stack

### 2.1 Native LLM Inference (Gemma via Google MediaPipe Tasks GenAI)
- **Library**: `com.google.mediapipe:tasks-genai:0.10.14`
- **Native JNI Engine**: Bundled `libllm_inference_engine_jni.so` (arm64-v8a) providing direct hardware acceleration on Qualcomm Snapdragon (Adreno GPU / Hexagon NPU via OpenCL/Vulkan) and CPU.
- **Model Support**:
  - `gemma-2b-it.bin` (MediaPipe GenAI format)
  - `gemma-2b-it-gpu.bin` / `gemma-2b-it-cpu.bin`
  - `gemma4e2b` / `gemma-2b-it.litertlm` (LiteRT / MediaPipe format)
- **Storage Locations**:
  1. `/sdcard/StorageSense/models/`
  2. `/sdcard/Download/models/`
- **Implementation**: [OnDeviceLlmEngine.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/ai/llm/OnDeviceLlmEngine.kt)
  - Lazy, thread-safe model instantiation on a dedicated background dispatcher (`Dispatchers.Default`).
  - Streaming token-by-token generation via Kotlin Coroutines `Flow<String>`.
  - Automatic memory tracking and lifecycle management (`unload()` invoked during OS low-memory callbacks via `ModelManager`).
  - Graceful fallback: If no `.bin` model file is detected in the model paths, the system seamlessly uses on-device semantic ranking without crashing.

### 2.2 Neural Document Embeddings (all-MiniLM-L6-v2 ONNX INT8)
- **Runtime**: `com.microsoft.onnxruntime:onnxruntime-android:1.17.0`
- **Model**: `all-MiniLM-L6-v2` quantized to INT8 (384-dimensional dense vectors).
- **Tokenizer**: Bundled WordPiece tokenizer with complete vocabulary (`app/src/main/assets/tokenizer/vocab.txt`).
- **Implementation**: [TextEmbeddingModel.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/ai/embedding/TextEmbeddingModel.kt)
  - Computes dense vector embeddings for document chunks and user queries.
  - Normalizes output embeddings with $L_2$ norm for fast cosine dot-product comparisons.

### 2.3 On-Device OCR & Document Extraction
- **OCR**: Google ML Kit Digital Ink / Vision Text Recognition (`com.google.android.gms:play-services-mlkit-text-recognition:19.0.0`) runs completely offline on photos, screenshots, and scanned receipts.
- **Document Parsers**:
  - PDF: Android `PdfRenderer` + Apache PDFBox Android.
  - DOCX / PPTX: Native streaming XML parser extracting body and slide text without heavyweight office runtimes.
  - Plaintext & Code: Streaming buffered reader.

---

## 3. Storage Indexing & Hybrid Search Engine

### 3.1 Sub-Millisecond Lexical Search (SQLite FTS4 + MatchInfo)
- **Room FTS Table**: `file_fts` virtual table using SQLite FTS4 with the Porter stemmer.
- **Ranker**: Native TF-IDF matchinfo scoring parser implemented in [FtsMatchInfoRanker.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/search/FtsMatchInfoRanker.kt), extracting column frequencies and exact phrase hits.

### 3.2 Dense Vector Search
- Implemented in [VectorSearchEngine.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/search/VectorSearchEngine.kt).
- Computes cosine similarity between query embeddings and chunk embeddings stored in Room (`document_embeddings`).
- Supports similarity thresholds ($\ge 0.40$) and top-$K$ candidate selection.

### 3.3 Reciprocal Rank Fusion (RRF)
- Implemented in [HybridSearchEngine.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/search/HybridSearchEngine.kt).
- Merges ranked lists from lexical FTS4 and dense vector search using standard RRF:
  $$\text{RRF Score}(d) = \sum_{m \in \{\text{BM25}, \text{Vector}\}} \frac{w_m}{k + \text{rank}_m(d)}$$
  with constant $k = 60$ and weights $w_{\text{BM25}} = 0.5$, $w_{\text{Vector}} = 0.5$.

### 3.4 Dual Storage Discovery & Android Scoped Storage Limits
- **Dual Pipeline Crawl**: [FileScanner.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/indexing/FileScanner.kt) performs a fast recursive filesystem crawl over external storage combined with a `MediaStore` provider query fallback. This ensures media files, WhatsApp/Telegram media (in `Android/media/`), and downloaded files are fully cataloged.
- **Batch FTS Insertion**: [StorageIndexManager.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/indexing/StorageIndexManager.kt) groups discovered files into 500-item batch transactions for Room and SQLite FTS4, indexing 13,000+ files in under 2 seconds without UI freeze.
- **Storage Accounting Breakdown (190 GB vs ~103.7 GB)**:
  - *Firmware / System Partitions*: ~15-20 GB (`/system`, `/vendor`, `/product`).
  - *Installed App Binaries*: ~30-45 GB (`/data/app/`).
  - *Private App Sandboxes*: ~30-40 GB (`/sdcard/Android/data/` and `/sdcard/Android/obb/`), which the Android Linux kernel and SELinux strictly isolate from third-party apps even with `MANAGE_EXTERNAL_STORAGE`.
  - *User-Accessible Storage Space*: **~103.7 GB (13,323+ files)** representing 100% of user media, downloads, archives, videos, photos, and documents.

---

## 4. Hardened Deduplication & Safety Protections

### 4.1 Combinatorial Scalability & Centroid Pre-Filtering
- Document chunks previously suffered from combinatorial explosion ($O(N^2 \times C_A \times C_B)$ pairwise comparisons).
- **Centroid Filter**: Before evaluating individual chunk similarities, [DuplicateDetector.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/search/DuplicateDetector.kt) computes document-level mean centroid vectors. Chunk-level comparisons are only performed if the document centroids exceed $\ge 0.70$ cosine similarity.

### 4.2 Exact Hash Deletion Protection
- To avoid false-positive deletions on files $> 512\text{ KB}$ caused by partial head/tail sampling, [FileScanner.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/indexing/FileScanner.kt) computes a full SHA-256 hash before classifying any file group as `EXACT_HASH`.

### 4.3 Sensitive Document Safeguards
- [SpaceReclaimer.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/action/SpaceReclaimer.kt) enforces strict heuristics to preserve critical user records:
  - Files containing "tax", "resume", "cv", "invoice", "statement", "id_", "passport", "aadhaar", "pan" in their name or path are protected from automated cleanup.
  - Files modified within the last 14 days are protected.
  - The latest copy of duplicate files is always preserved by default.

### 4.4 Reversible Action Engine & Undo Log
- **Staging Trash**: Destructive actions move files to `~/.storagesense/trash/` rather than immediate permanent deletion.
- **Undo Log**: Every executed action writes an undo entry into Room and the local filesystem, enabling immediate 1-tap rollbacks.
- **Two-Phase Approval**: Any action (trash, cleanup, permanent delete) requires explicit user confirmation via [ActionApprovalSheet.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/ui/chat/ActionApprovalSheet.kt).

### 4.5 File Viewing & Interactive Deletion Management
- **FileProvider Integration**: Registered `androidx.core.content.FileProvider` in [AndroidManifest.xml](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/AndroidManifest.xml) with [file_paths.xml](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/res/xml/file_paths.xml), granting read URIs with `FLAG_GRANT_READ_URI_PERMISSION`.
- **Native File Opener**: [FileActionHelper.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/ui/util/FileActionHelper.kt) resolves MIME types dynamically and issues `Intent.ACTION_VIEW` to let users inspect files in their default apps (gallery, PDF viewer, media player).
- **Category File Browser**: In the Dashboard, tapping any storage category (e.g., "Videos (74 GB)") displays a bottom sheet with all category files, complete with direct "Open" and "Delete" triggers.
- **Interactive Search Result Cards**: [FileResultCard.kt](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/src/main/java/com/storagesense/app/ui/chat/FileResultCard.kt) presents instant "Open" and "Delete" actions alongside hybrid search results.

---

## 5. Build & Deployment Guide

### 5.1 OneDrive File-Locking Fix
When developing on Windows with OneDrive syncing the workspace, Gradle daemon often encounters `AccessDeniedException` during artifact movement. The build is configured in [app/build.gradle.kts](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/app/build.gradle.kts) to redirect build outputs outside OneDrive:
```kotlin
layout.buildDirectory.set(File(System.getProperty("user.home"), ".gradle-ascent-build/app"))
```
Gradle builds should use:
```powershell
.\gradlew.bat assembleDebug --project-cache-dir C:\Users\ASUS\.gradle-ascent-cache
```

### 5.2 1-Click ADB Deployment Script (`Deploy_To_Phone.bat`)
A standalone batch script [Deploy_To_Phone.bat](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/Deploy_To_Phone.bat) automates:
1. Building `app-debug.apk`.
2. Detecting connected devices (`adb devices`).
3. Installing APK with `-r -d` flags.
4. Granting `MANAGE_EXTERNAL_STORAGE` via `appops`.
5. Creating model directories (`/sdcard/StorageSense/models/` and `/sdcard/Download/models/`).
6. Launching `MainActivity`.

### 5.3 Model Setup (`Download_And_Push_Gemma.bat` & `scripts/download_model.py`)
To deploy Gemma weights to the phone:
1. Run [Download_And_Push_Gemma.bat](file:///C:/Users/ASUS/OneDrive/Desktop/ascent/Download_And_Push_Gemma.bat) or execute:
   ```powershell
   python scripts/download_model.py --push
   ```
2. The model binary will be pushed to `/sdcard/StorageSense/models/gemma-2b-it.bin`.
3. In StorageSense Settings, the On-Device AI Engine will switch from `STANDBY` to `READY` with full hardware acceleration.
