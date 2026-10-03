# 🧠 StorageSense Desktop (PC Port)

> **Enterprise / Commercial-Grade On-Device Intelligent Storage & Retrieval System**  
> *Zero-Cloud Privacy Guarantee | Dual-Tier Autonomous Retrieval | Grounded AI Summary | Multi-Modal Deduplication | Smart Space Reclaimer*

---

## 📌 Overview

**StorageSense Desktop (PC Port)** brings the core storage intelligence and retrieval engine of Reiatsu to desktop workstations and laptops. It operates 100% locally on your hardware with **zero telemetry**, **zero cloud dependencies**, and **zero external token costs**.

### Core Capabilities:
1. **Dual-Tier Autonomous Retrieval**: Combines SQLite FTS5 BM25 lexical search and ChromaDB dense vector embeddings (`all-MiniLM-L6-v2`) with a real-time **Just-In-Time (JIT) Live Filesystem Sweeper**. Unindexed or newly downloaded files are found on-the-fly and can be indexed with one click.
2. **Grounded AI Summary**: Streams executive summaries and question answering token-by-token directly from your local Ollama models (`gemma4:e4b`, `llama3.2`, etc.) with intelligent offline extractive fallback if Ollama is restarting.
3. **Multi-Modal Deduplication**: Identifies exact binary duplicates (SHA-256), semantic near-duplicate document revisions (cosine centroid clustering $\ge 0.88$), and perceptual image duplicates (`pHash` Hamming distance $\le 5$).
4. **Smart Space Reclamation**: Identifies stale installers, duplicate clusters, and bloated temporary caches with a **Protected File Guardian** (protecting resumes, IDs, tax records, and certificates). Files are safely quarantined in a `.trash` sandbox with 1-click atomic restore.
5. **Zero C: Drive Disk Exhaustion**: Strict workspace isolation ensures all databases, ChromaDB stores, caches, and quarantine directories operate strictly within high-capacity secondary storage or designated folders.

---

## 🚀 Quick Start (1-Click Launchers)

No manual CLI commands needed! Use the provided batch launchers:

| Launcher File | What It Does |
| :--- | :--- |
| **`Start_StorageSense.bat`** | **Recommended.** Starts the local engine and launches the Graphical Web Dashboard at `http://localhost:8501`. |
| **`StorageSense_Assistant.bat`** | Launches the fast, interactive terminal menu (Search, Dedup, Clean, Telemetry). |
| **`Create_Desktop_Shortcut.bat`** | Generates a 1-click desktop shortcut on your Windows Desktop pointing directly to StorageSense. |

---

## 🛠️ Manual Setup & Installation

### 1. Prerequisites
- **Python**: 3.10 – 3.14 (64-bit recommended)
- **Local LLM (Optional but Recommended)**: [Ollama](https://ollama.ai) installed with `gemma4:e4b` or `llama3.2`:
  ```powershell
  ollama run gemma4:e4b
  ```

### 2. Install Dependencies
Open a terminal in this directory (`pc_port/`) and run:
```powershell
pip install -r requirements.txt
```

### 3. Launching
- **Web Dashboard**:
  ```powershell
  python run_app.py
  ```
- **Interactive CLI**:
  ```powershell
  python run_cli.py
  ```

---

## 💻 Headless CLI Commands

For automated or scripted operations:

- **Search with AI Summary**:
  ```powershell
  python run_cli.py --search "CET415 question papers"
  ```
- **Deduplication Scan**:
  ```powershell
  python run_cli.py --dedup "C:\Users\YourUser\Downloads"
  ```
- **Space Reclamation Proposal**:
  ```powershell
  python run_cli.py --clean 2.0
  ```
- **View System Telemetry & Ollama Status**:
  ```powershell
  python run_cli.py --telemetry
  ```
- **Restore Quarantined File**:
  ```powershell
  python run_cli.py --restore <TRASH_ID>
  ```

---

## 🧪 Verification & Unit Tests

Run the full unit test suite (all 56 unit tests pass with zero warnings):
```powershell
python -m unittest discover -s tests
```

---

## 📁 Architecture & File Layout

```
pc_port/
├── Start_StorageSense.bat         # 1-Click Graphical Web App Launcher
├── StorageSense_Assistant.bat     # 1-Click Interactive CLI Menu Launcher
├── Create_Desktop_Shortcut.bat    # Windows Desktop Shortcut Creator
├── create_shortcut.ps1            # Dynamic PowerShell shortcut generator
├── HOW_TO_RUN.txt                 # Quick instructions text file
├── README.md                      # This guide
├── requirements.txt               # Python package dependencies
├── run_app.py                     # Streamlit server launcher
├── run_cli.py                     # Headless and interactive CLI interface
├── config.py                      # Global paths, limits & hardware profile
│
├── actions/                       # Safe quarantine, atomic rollback & file launch
│   ├── action_engine.py
│   └── trash_manager.py
├── core/                          # Coordinator, SQLite WAL database, logger & env guards
│   ├── coordinator.py
│   ├── database.py
│   ├── document_ontology.py
│   ├── env_guard.py
│   ├── identity_expander.py
│   ├── query_parser.py
│   ├── system_ops.py
│   └── workspace_manager.py
├── indexing/                      # Background worker & folder watcher (watchdog)
│   ├── background_worker.py
│   └── folder_watcher.py
├── intelligence/                  # LLM agent, AI synthesis, deduplication & reclaimer
│   ├── dedup_engine.py
│   ├── llm_agent.py
│   └── space_reclaimer.py
├── parsers/                       # High-speed document and image extractors
│   ├── docx_parser.py
│   ├── image_parser.py
│   ├── pdf_parser.py
│   ├── pptx_parser.py
│   └── text_parser.py
├── search/                        # Dual-tier retrieval (FTS5 BM25, ChromaDB & JIT Scanner)
│   ├── bm25_engine.py
│   ├── chunker.py
│   ├── embedder.py
│   ├── hybrid_fusion.py
│   ├── hybrid_search.py
│   ├── live_scanner.py
│   └── vector_engine.py
├── ui/                            # Modern Streamlit Web Dashboard & UI cards
│   ├── app_streamlit.py
│   └── components.py
└── tests/                         # Comprehensive unit & integration test suite
```

---

## 🛡️ Privacy & Security Guarantee
- **100% Local**: No API keys, zero cloud telemetry, and zero user data ever leaves the host PC.
- **Protected Files**: Resumes, passports, tax filings, legal agreements, and identity documents are strictly guarded against automated modification or deletion.
