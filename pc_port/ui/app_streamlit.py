import streamlit as st
import os
import sys
import time
from pathlib import Path

# Add project root to sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from config import SUPPORTED_EXTENSIONS, OLLAMA_BASE_URL, TRASH_DIR, WORKSPACE_DRIVE
from core.coordinator import EngineCoordinator
from core.system_ops import get_system_telemetry, open_file_in_os, show_in_explorer, get_standard_user_folders, find_supported_files, get_available_drives
from ui.components import (
    render_file_result_card,
    render_execution_trace,
    render_duplicate_card,
    format_bytes,
    format_timestamp
)

st.set_page_config(
    page_title="StorageSense — Local Agentic Storage AI",
    page_icon="🧠",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Custom SatqueryAI-Inspired Dark Glassmorphism CSS
st.markdown("""
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap');

html, body, [class*="css"] {
    font-family: 'Inter', -apple-system, sans-serif;
}

code, pre {
    font-family: 'JetBrains Mono', monospace !important;
}

.stMetric {
    background-color: #161b22;
    border: 1px solid #30363d;
    border-radius: 8px;
    padding: 10px 14px;
}

div[data-testid="stExpander"] {
    border: 1px solid #30363d !important;
    border-radius: 8px !important;
    background-color: #0d1117 !important;
}
</style>
""", unsafe_allow_html=True)

# Initialize EngineCoordinator singleton
@st.cache_resource
def get_coordinator():
    return EngineCoordinator()

coord = get_coordinator()

# Top Telemetry Header Bar
telemetry = get_system_telemetry()
llm_status = coord.llm_agent.get_model_telemetry()
index_stats = coord.get_indexed_stats()

c_ram, c_model, c_index, c_trash = st.columns(4)
with c_ram:
    st.metric(
        "🧠 System RAM",
        f"{telemetry['ram_used_gb']} / {telemetry['ram_total_gb']} GB",
        f"{telemetry['ram_percent']}% utilized",
        delta_color="inverse" if telemetry['ram_percent'] > 85 else "off"
    )

with c_model:
    m_name = llm_status.get("active_model", "None").split("/")[-1]
    st.metric(
        "⚡ Active Ollama Model",
        m_name[:18] + ("..." if len(m_name) > 18 else ""),
        f"{llm_status.get('latency_ms', 0)} ms latency" if llm_status.get("status") == "online" else "Offline (Hybrid)"
    )

with c_index:
    st.metric(
        "📁 Indexed Storage",
        f"{index_stats['total_files']} files",
        f"{index_stats['total_chunks']} chunks ({format_bytes(index_stats['total_bytes'])})"
    )

with c_trash:
    st.metric(
        "🗑️ Quarantined Trash",
        f"{index_stats['trashed_files']} files",
        f"{format_bytes(index_stats['trashed_bytes'])} reclaimable"
    )

st.divider()

# Sidebar: Directory Ingestion, Model Switcher & Presets
with st.sidebar:
    st.title("🧠 StorageSense")
    st.caption("Production-Grade Personal Storage Agent")

    # Dynamic Model Selector
    available_models = coord.llm_agent.get_available_models()
    if available_models:
        cur_idx = 0
        if coord.llm_agent.model in available_models:
            cur_idx = available_models.index(coord.llm_agent.model)
        chosen_model = st.selectbox(
            "⚡ Local Ollama Model:",
            available_models,
            index=cur_idx,
            help="Select which local model executes intent parsing and grounded RAG."
        )
        if chosen_model != coord.llm_agent.model:
            coord.llm_agent.set_active_model(chosen_model)
            st.rerun()
    else:
        st.warning("🟡 Ollama service offline or no models found. (Instant Hybrid Search is active)")

    ws_dir = coord.workspace_mgr.get_workspace_dir()
    drive_label = telemetry.get('drive_name', WORKSPACE_DRIVE)
    st.markdown(f"**Drive Root**: `{drive_label}` | Free: **{telemetry.get('drive_free_gb', 0)} GB**")
    st.caption(f"Active Workspace: `{ws_dir}`")
    st.divider()

    st.subheader("📂 Ingest Storage Folders")
    
    if "indexing_notice" in st.session_state:
        notice = st.session_state.pop("indexing_notice")
        if notice.get("failed"):
            st.warning(f"Indexed {notice['success']} files. {len(notice['failed'])} file(s) skipped or had errors.")
            with st.expander("⚠️ View Skipped / Failed Files"):
                for fn, err in notice["failed"][:50]:
                    st.caption(f"• **{fn}**: {err}")
        else:
            st.success(f"Successfully indexed {notice['success']} files! Zero bytes written to C:.")

    presets = get_standard_user_folders()
    dl_key = next((k for k in presets if k.startswith("Downloads")), None)
    doc_key = next((k for k in presets if k.startswith("Documents")), None)
    dt_key = next((k for k in presets if k.startswith("Desktop")), None)
    proj_key = next((k for k in presets if k.startswith("Current Project")), None)
    
    # 1-Click Scan All Common Locations
    if st.button("🌐 1-Click Scan All Locations", type="secondary", use_container_width=True, help="Indexes Downloads, Documents, Desktop, and Project files across all drives while keeping all database files on workspace drive"):
        common_folders = [Path(p) for p in presets.values() if Path(p).exists()]
        all_files = []
        for fold in common_folders:
            all_files.extend(find_supported_files(fold, SUPPORTED_EXTENSIONS))

        if not all_files:
            st.warning("No supported files found across common locations.")
        else:
            pbar = st.progress(0, text=f"Found {len(all_files)} files across drives. Indexing...")
            success_count = 0
            failed_files = []
            for i, f in enumerate(all_files):
                try:
                    if coord.search_engine.index_file(f):
                        success_count += 1
                    else:
                        failed_files.append((f.name, "File could not be parsed or is empty"))
                except Exception as e:
                    failed_files.append((f.name, str(e)))
                    logger.warning(f"Error indexing {f}: {e}")
                pbar.progress((i + 1) / len(all_files), text=f"[{i+1}/{len(all_files)}] {f.name}")
            st.session_state["indexing_notice"] = {"success": success_count, "failed": failed_files}
            st.rerun()

    st.caption("Quick Select Folder Preset:")
    col_p1, col_p2 = st.columns(2)
    with col_p1:
        if st.button("📥 Downloads", use_container_width=True):
            st.session_state["scan_folder_val"] = presets.get(dl_key, str(Path.home() / "Downloads"))
        if st.button("📄 Documents", use_container_width=True):
            st.session_state["scan_folder_val"] = presets.get(doc_key, str(Path.home() / "Documents"))
    with col_p2:
        if st.button("🖥️ Desktop", use_container_width=True):
            st.session_state["scan_folder_val"] = presets.get(dt_key, str(Path.home() / "Desktop"))
        if st.button("📁 Project Folder", use_container_width=True):
            st.session_state["scan_folder_val"] = presets.get(proj_key, str(BASE_DIR.parent))

    default_scan_dir = st.session_state.get("scan_folder_val", presets.get(proj_key, str(BASE_DIR.parent)))
    folder_input = st.text_input("Folder Path to Scan:", value=default_scan_dir)

    if st.button("🚀 Start Indexing Scan", type="primary", use_container_width=True):
        target = Path(folder_input).resolve()
        if target.exists() and (target.is_dir() or target.is_file()):
            files_to_index = find_supported_files(target, SUPPORTED_EXTENSIONS)
            
            if not files_to_index:
                st.warning("No supported files found in the selected location.")
            else:
                pbar = st.progress(0, text=f"Found {len(files_to_index)} files. Indexing...")
                success_count = 0
                failed_files = []
                for i, f in enumerate(files_to_index):
                    try:
                        if coord.search_engine.index_file(f):
                            success_count += 1
                        else:
                            failed_files.append((f.name, "File could not be parsed or is empty"))
                    except Exception as e:
                        failed_files.append((f.name, str(e)))
                        logger.warning(f"Error indexing {f}: {e}")
                    pbar.progress((i + 1) / len(files_to_index), text=f"[{i+1}/{len(files_to_index)}] {f.name}")
                
                st.session_state["indexing_notice"] = {"success": success_count, "failed": failed_files}
                st.rerun()
        else:
            st.error("Invalid directory path.")

# Main Navigation Tabs
tab_chat, tab_dedup, tab_cleanup, tab_trash, tab_settings = st.tabs([
    "💬 Search & Talk to Storage",
    "🔄 Deduplication Studio",
    "🧹 Goal-Oriented Space Reclaimer",
    "🗑️ Reversible Trash & Undo",
    "⚙️ Settings & Workspace"
])

# ----------------- TAB 1: CONVERSATIONAL SEARCH -----------------
with tab_chat:
    st.header("Search & Conversational Retrieval")
    st.caption("Query your storage by concept, meaning, exact keywords, or ask natural language questions.")

    # Empty State Quick-Start Guidance
    if index_stats["total_files"] == 0:
        with st.container(border=True):
            st.subheader("👋 Welcome to StorageSense!")
            st.markdown(
                "No files have been indexed yet. Click below to index sample files in your project directory "
                "or choose a folder from the sidebar."
            )
            if st.button("📁 Quick-Index Current Project Folder", type="primary"):
                target = Path(presets.get(proj_key, str(BASE_DIR.parent)))
                files_to_index = find_supported_files(target, SUPPORTED_EXTENSIONS)
                success_cnt = 0
                failed_files = []
                for f in files_to_index:
                    try:
                        if coord.search_engine.index_file(f):
                            success_cnt += 1
                        else:
                            failed_files.append((f.name, "File could not be parsed or is empty"))
                    except Exception as e:
                        failed_files.append((f.name, str(e)))
                        logger.warning(f"Error indexing {f}: {e}")
                st.session_state["indexing_notice"] = {"success": success_cnt, "failed": failed_files}
                st.rerun()

    # Persistent Chunk & Index Governance Studio
    all_chunked_list = coord.get_all_chunked_files()
    total_chunked_cnt = len(all_chunked_list)
    total_chunks_cnt = sum(c.get("chunk_count", 0) for c in all_chunked_list)

    with st.container(border=True):
        g_c1, g_c2 = st.columns([7, 3])
        with g_c1:
            st.markdown(
                f"**📦 Chunks & Index Status**: `{total_chunked_cnt}` files chunked in DB (`{total_chunks_cnt}` total chunks) &nbsp;|&nbsp; ⚡ On-Device Live Discovery Ready"
            )
        with g_c2:
            show_chunk_mgr = st.checkbox("Manage Chunked Files", value=False, key="toggle_chunk_mgr")

        if show_chunk_mgr:
            if not all_chunked_list:
                st.caption("No files are currently chunked in the database.")
            else:
                st.markdown("##### 📦 All Active Chunked Files in Database")
                for cf in all_chunked_list[:25]:
                    f_col1, f_col2, f_col3 = st.columns([6, 2, 2])
                    with f_col1:
                        st.markdown(f"**{cf['file_name']}** ({cf['chunk_count']} chunks)")
                        st.caption(f"`{cf['file_path']}`")
                    with f_col2:
                        st.caption(f"Size: {format_bytes(cf['file_size'])}")
                    with f_col3:
                        f_hash = abs(hash(cf['file_path']))
                        if st.button("🗑️ Unchunk", key=f"mgr_unchunk_{f_hash}", use_container_width=True):
                            coord.unchunk_file(cf['file_path'])
                            st.toast(f"Unchunked {cf['file_name']}")
                            st.rerun()

    avail_drives = get_available_drives()
    drive_opts = ["🌐 All Available Drives"] + [f"Drive {k[-2:]} ({v})" for k, v in avail_drives.items()] + ["📁 Custom Directory..."]

    with st.form("search_form", clear_on_submit=False):
        query_input = st.text_input(
            "Enter query or document excerpt:",
            placeholder="e.g. Find where the question papers for cet415 is stored, or paste first-page text (e.g. in D:)",
            key="main_search_query_input"
        )
        col_scope, col_mode, col_filter, col_opt, col_btn = st.columns([2.2, 2.2, 1.8, 2.0, 1.0])
        with col_scope:
            chosen_drive_lbl = st.selectbox(
                "Target Disk Scope:",
                drive_opts,
                help="Target a specific drive (e.g. D:, F:) or scan all drives."
            )
        with col_mode:
            chosen_mode_lbl = st.selectbox(
                "Search Mode:",
                [
                    "⚡ Dual-Tier Hybrid",
                    "🔍 Live Filesystem Only",
                    "📦 Pre-Indexed Chunks Only"
                ],
                help="'Dual-Tier Hybrid' checks both index and disk. 'Live Filesystem Only' ignores pre-indexed chunks and sweeps the disk directly. 'Pre-Indexed Only' searches only indexed chunks."
            )
        with col_filter:
            type_filter = st.selectbox("Format Filter:", ["All Formats", "PDFs Only", "Word (.docx)", "PowerPoint", "Code & Text (.py, .md, .txt)", "Images"])
        with col_opt:
            generate_ai = st.checkbox("🤖 Auto-Summarize", value=True, help="Automatically synthesize an AI summary for search results via local model.")
            allow_auto_chunk = st.checkbox("⚡ Auto-Chunk Discovered", value=False, help="If unchecked (default), discovered files remain unchunked on disk until you explicitly click 'Chunk File'.")
        with col_btn:
            st.write("")
            submit_search = st.form_submit_button("🔍 Search", type="primary", use_container_width=True)

        custom_scope_path = ""
        if chosen_drive_lbl == "📁 Custom Directory...":
            custom_scope_path = st.text_input("Enter folder path to search:", placeholder="e.g. D:\\stata_assignment or F:\\DEEP LEARNING")

    ext_map = {
        "PDFs Only": [".pdf"],
        "Word (.docx)": [".docx"],
        "PowerPoint": [".pptx"],
        "Code & Text (.py, .md, .txt)": [".txt", ".md", ".py", ".json", ".csv", ".log", ".java", ".c", ".cpp", ".rs", ".html", ".js", ".ts"],
        "Images": [".png", ".jpg", ".jpeg", ".webp"]
    }
    selected_exts = ext_map.get(type_filter, None)

    target_drive_arg = None
    target_dirs_arg = None
    if chosen_drive_lbl == "📁 Custom Directory..." and custom_scope_path.strip():
        target_dirs_arg = [custom_scope_path.strip()]
    elif chosen_drive_lbl != "🌐 All Available Drives":
        for k, v in avail_drives.items():
            if f"Drive {k[-2:]} ({v})" == chosen_drive_lbl:
                target_drive_arg = v
                break

    mode_map = {
        "⚡ Dual-Tier Hybrid": "hybrid",
        "🔍 Live Filesystem Only": "live_only",
        "📦 Pre-Indexed Chunks Only": "indexed_only"
    }
    search_mode_arg = mode_map.get(chosen_mode_lbl, "hybrid")

    if submit_search and query_input.strip():
        query = query_input.strip()
        intent = coord.llm_agent.parse_intent(query)
        detected_drive = intent.get("target_drive")
        final_target_drive = target_drive_arg or detected_drive
        final_query = intent.get("query", query)

        st.session_state["search_intent"] = intent
        st.session_state["search_query"] = query
        st.session_state["enable_ai"] = generate_ai
        st.session_state.pop("cached_ai_response", None)
        st.session_state.pop("cached_ai_response_q", None)

        with st.spinner(f"Searching StorageSense ({chosen_mode_lbl})..."):
            results, trace = coord.search_with_trace(
                final_query,
                top_k=10,
                file_types=selected_exts,
                target_drive=final_target_drive,
                target_dirs=target_dirs_arg,
                search_mode=search_mode_arg,
                enable_live_scan=(search_mode_arg != "indexed_only"),
                auto_chunk=allow_auto_chunk
            )
            st.session_state["search_results"] = results
            st.session_state["search_trace"] = trace

    # Render persisted search results
    if "search_results" in st.session_state:
        results = st.session_state.get("search_results", [])
        trace = st.session_state.get("search_trace", {})
        intent = st.session_state.get("search_intent", {})
        active_q = st.session_state.get("search_query", "")
        should_gen_ai = st.session_state.get("enable_ai", False)

        st.caption(f"**Action**: `{intent.get('action', 'SEARCH')}` | **Query Topic**: `{intent.get('query', active_q)}` | **Parser Latency**: `{intent.get('parse_time_ms', 0)} ms` ({intent.get('method', 'instant')})")
        render_execution_trace(trace)

        if not results:
            st.warning("No files matched your query. Make sure you have scanned your folders from the sidebar or selected the correct drive.")
        else:
            # Chunk Governance Status & Batch Action Bar
            unchunked_count = sum(1 for r in results if not r.get("is_chunked", False))
            chunked_count = len(results) - unchunked_count

            with st.container(border=True):
                gov_c1, gov_c2, gov_c3 = st.columns([3.5, 2.2, 2.2])
                with gov_c1:
                    st.markdown(
                        f"**Index Governance**: 📦 `{chunked_count}` Chunked in DB &nbsp;|&nbsp; ⚡ `{unchunked_count}` Unchunked on Disk"
                    )
                with gov_c2:
                    if unchunked_count > 0:
                        if st.button(f"⚡ Chunk All Discovered ({unchunked_count})", type="primary", use_container_width=True, key="chunk_all_btn"):
                            with st.spinner(f"Chunking {unchunked_count} discovered files..."):
                                for r in results:
                                    if not r.get("is_chunked", False):
                                        coord.chunk_file(r["file_path"])
                                        r["is_chunked"] = True
                                st.success(f"Chunked and indexed {unchunked_count} files!")
                                st.rerun()
                with gov_c3:
                    if chunked_count > 0:
                        if st.button(f"🗑️ Unchunk All ({chunked_count})", use_container_width=True, key="unchunk_all_btn"):
                            for r in results:
                                if r.get("is_chunked", False):
                                    coord.unchunk_file(r["file_path"])
                                    r["is_chunked"] = False
                            st.info(f"Unchunked {chunked_count} files from index.")
                            st.rerun()

            # Grounded AI Summary Section
            if should_gen_ai:
                with st.container(border=True):
                    ai_head_col, ai_btn_col = st.columns([4, 1.2])
                    with ai_head_col:
                        model_label = coord.llm_agent.model if coord.llm_agent.is_available() and coord.llm_agent.model != "none" else "StorageSense Grounded Hybrid"
                        st.markdown(f"#### 🤖 Grounded AI Summary `({model_label})`")
                    with ai_btn_col:
                        if st.button("🔄 Regenerate", key="regen_ai_summary_btn", use_container_width=True):
                            st.session_state.pop("cached_ai_response", None)
                            st.session_state.pop("cached_ai_response_q", None)
                            st.rerun()

                    with st.chat_message("assistant"):
                        if "cached_ai_response" in st.session_state and st.session_state.get("cached_ai_response_q") == active_q:
                            st.markdown(st.session_state["cached_ai_response"])
                        else:
                            stream_gen = coord.llm_agent.generate_rag_stream(active_q, results)
                            accumulated = []
                            def caching_stream():
                                for chunk in stream_gen:
                                    accumulated.append(chunk)
                                    yield chunk
                            st.write_stream(caching_stream())
                            full_text = "".join(accumulated)
                            st.session_state["cached_ai_response"] = full_text
                            st.session_state["cached_ai_response_q"] = active_q
            else:
                with st.container(border=True):
                    sum_c1, sum_c2 = st.columns([4, 1.8])
                    with sum_c1:
                        st.markdown("💡 *Want an executive AI summary answering your question from these files?*")
                    with sum_c2:
                        if st.button("🤖 Generate AI Summary", type="primary", use_container_width=True, key="activate_ai_summary_btn"):
                            st.session_state["enable_ai"] = True
                            st.session_state.pop("cached_ai_response", None)
                            st.session_state.pop("cached_ai_response_q", None)
                            st.rerun()

            st.markdown(f"### 📂 Ranked File Matches ({len(results)})")
            for i, r in enumerate(results):
                render_file_result_card(r, rank=i+1, coord=coord)

# ----------------- TAB 2: DEDUPLICATION STUDIO -----------------
with tab_dedup:
    st.header("Intelligent 3-Tier Deduplication Studio")
    st.caption("Identifies byte-level exact duplicates, document near-duplicate revisions via centroid vector similarity, and perceptual image duplicates.")

    with coord.db.get_connection() as conn:
        reg_count = conn.execute("SELECT count(*) as cnt FROM file_registry").fetchone()["cnt"]

    if reg_count == 0:
        st.warning("⚠️ **Your file registry is currently empty (0 indexed files).** Deduplication compares registered files to find exact, semantic, and image duplicates.")

    with st.expander("📁 Target Folder to Scan & Deduplicate (Optional)", expanded=(reg_count == 0)):
        col_scan_path, col_scan_btn = st.columns([3, 1])
        with col_scan_path:
            std_folders = get_standard_user_folders()
            default_fld = std_folders.get("Downloads (C:)", str(Path.home() / "Downloads"))
            scan_target = st.text_input("Folder path:", value=default_fld, help="Enter a folder to index and analyze for duplicates")
        with col_scan_btn:
            st.write("")
            st.write("")
            if st.button("⚡ Scan & Deduplicate", type="primary", use_container_width=True):
                with st.spinner(f"Indexing files from {scan_target} and analyzing duplicate clusters..."):
                    groups = coord.dedup_detector.find_all_duplicates(folder=scan_target)
                    st.session_state["dup_groups"] = groups
                    st.rerun()

    col_btn1, col_btn2 = st.columns([2, 1])
    with col_btn1:
        if st.button("🔎 Run Duplicate Analysis (All Indexed Files)", type="secondary"):
            with st.spinner("Computing hash buckets, document centroids, and image pHash distances..."):
                groups = coord.dedup_detector.find_all_duplicates()
                st.session_state["dup_groups"] = groups
                st.rerun()

    groups = st.session_state.get("dup_groups", [])
    if not groups:
        if reg_count > 0:
            st.info("No duplicates detected among indexed files. Click 'Run Duplicate Analysis' or specify a folder above to scan new files.")
    else:
        total_reclaim = sum(g.reclaimable_bytes for g in groups)
        all_del_candidates = [c for g in groups for c in g.candidates_to_delete]
        
        st.success(f"Detected **{len(groups)}** duplicate clusters. Potential space reclaimable: **{format_bytes(total_reclaim)}** across {len(all_del_candidates)} candidate copies.")

        if st.button(f"🗑️ Quarantine ALL Older Copies ({len(all_del_candidates)} files, Free {format_bytes(total_reclaim)})", type="primary"):
            prop = coord.action_engine.create_proposal(
                action_type="DEDUPLICATE",
                description=f"Quarantine all {len(all_del_candidates)} duplicate copies",
                target_files=all_del_candidates
            )
            res = coord.action_engine.execute_proposal(prop)
            if res.get("errors"):
                for err in res["errors"]:
                    st.error(f"⚠️ {err}")
            st.success(f"Quarantined {res['success_count']} files to safe trash! Freed {format_bytes(res['freed_bytes'])}")
            st.session_state["dup_groups"] = []
            st.rerun()

        for idx, g in enumerate(groups):
            render_duplicate_card(g, idx)
            if st.button(f"🗑️ Quarantine Older Copies in Group #{idx+1}", key=f"clean_grp_{idx}", type="secondary"):
                proposal = coord.action_engine.create_proposal(
                    action_type="DEDUPLICATE",
                    description=f"Quarantine older copies in {g.match_type} group",
                    target_files=g.candidates_to_delete
                )
                res = coord.action_engine.execute_proposal(proposal)
                if res.get("errors"):
                    for err in res["errors"]:
                        st.error(f"⚠️ {err}")
                st.success(f"Quarantined {res['success_count']} files to safe trash! Freed {format_bytes(res['freed_bytes'])}")
                st.session_state["dup_groups"] = [grp for grp in groups if grp.group_id != g.group_id]
                st.rerun()

# ----------------- TAB 3: SPACE RECLAIMER -----------------
with tab_cleanup:
    st.header("Goal-Oriented Space Reclaimer")
    st.caption("Safely frees disk storage by targeting stale installers, old archives, and unused large files while strictly protecting critical records.")

    col_target, col_btn_clean = st.columns([2, 1])
    with col_target:
        target_gb = st.slider("Target Space to Reclaim (GB):", min_value=0.5, max_value=25.0, value=2.0, step=0.5)
    with col_btn_clean:
        st.write("")
        st.write("")
        scan_reclaim = st.button("📊 Analyze Cleanup Candidates", type="primary", use_container_width=True)

    if scan_reclaim or "reclaim_candidates" in st.session_state:
        if scan_reclaim:
            with st.spinner("Analyzing files against age, installer types, and sensitivity rules..."):
                cands = coord.space_reclaimer.recommend_cleanup(target_bytes=int(target_gb * 1024 * 1024 * 1024))
                st.session_state["reclaim_candidates"] = cands

        candidates = st.session_state.get("reclaim_candidates", [])
        if not candidates:
            st.info("No cleanup candidates found matching criteria.")
        else:
            total_cand_size = sum(c.file_size for c in candidates)
            st.success(f"Identified **{len(candidates)}** candidate files freeing **{format_bytes(total_cand_size)}**")

            with st.container(border=True):
                for idx, c in enumerate(candidates):
                    col_file, col_acts = st.columns([4, 1])
                    with col_file:
                        st.markdown(f"- 📁 **{c.file_name}** ({format_bytes(c.file_size)}) — *{c.reason}*")
                        st.caption(f"`{c.file_path}`")
                    with col_acts:
                        c_hash = abs(hash(c.file_path))
                        if st.button("🔍 Reveal", key=f"reclaim_rev_{idx}_{c_hash}"):
                            if show_in_explorer(c.file_path):
                                st.toast(f"✅ Opened in File Explorer: {Path(c.file_path).parent}", icon="🔍")
                            else:
                                st.error(f"Could not reveal: {c.file_path}")

            if st.button("⚠️ Confirm Safe Cleanup (Move to Quarantined Trash)", type="primary"):
                file_paths = [c.file_path for c in candidates]
                proposal = coord.action_engine.create_proposal(
                    action_type="FREE_SPACE",
                    description=f"Reclaim {format_bytes(total_cand_size)}",
                    target_files=file_paths
                )
                res = coord.action_engine.execute_proposal(proposal)
                if res.get("errors"):
                    for err in res["errors"]:
                        st.error(f"⚠️ {err}")
                st.success(f"Moved {res['success_count']} files to safe trash! Reclaimed {format_bytes(res['freed_bytes'])}")
                del st.session_state["reclaim_candidates"]
                st.rerun()

# ----------------- TAB 4: REVERSIBLE TRASH & UNDO -----------------
with tab_trash:
    st.header("Reversible Trash & Recovery Registry")
    st.caption(f"All deleted files are quarantined in `{TRASH_DIR}` with ACID transaction logs. Restore any file with a single click.")

    trash_items = coord.trash_manager.list_trash()

    col_info, col_purge = st.columns([3, 2])
    with col_info:
        st.markdown(f"**Quarantined Items:** `{len(trash_items)}` | Total Size: `{format_bytes(sum(i['file_size'] for i in trash_items))}`")
    with col_purge:
        if "confirm_purge_active" not in st.session_state:
            st.session_state["confirm_purge_active"] = False

        if not st.session_state["confirm_purge_active"]:
            if st.button("🔥 Permanently Purge All Trash", type="secondary", disabled=(len(trash_items) == 0)):
                st.session_state["confirm_purge_active"] = True
                st.rerun()
        else:
            st.warning("⚠️ Permanently delete all quarantined items? Cannot be undone.")
            col_p_yes, col_p_no = st.columns(2)
            with col_p_yes:
                if st.button("💥 Confirm Purge", type="primary"):
                    purged = coord.trash_manager.purge_trash()
                    st.session_state["confirm_purge_active"] = False
                    st.warning(f"Permanently purged {purged} items.")
                    st.rerun()
            with col_p_no:
                if st.button("Cancel", type="secondary"):
                    st.session_state["confirm_purge_active"] = False
                    st.rerun()

    st.divider()
    if not trash_items:
        st.info("Quarantine trash is empty.")
    else:
        for item in trash_items:
            with st.container(border=True):
                col_name, col_size, col_actions = st.columns([4, 1, 2])
                with col_name:
                    st.markdown(f"**{item['file_name']}**")
                    st.caption(f"Original Path: `{item['original_path']}`")
                with col_size:
                    st.markdown(format_bytes(item["file_size"]))
                    st.caption(format_timestamp(item["timestamp"]))
                with col_actions:
                    btn_restore, btn_show = st.columns(2)
                    with btn_restore:
                        if st.button("↩️ Restore", key=f"restore_{item['id']}"):
                            res = coord.action_engine.undo_trash(item["id"])
                            if res.get("success"):
                                st.success(f"Restored {item['file_name']}!")
                                st.rerun()
                            else:
                                st.error(f"Failed to restore: {res.get('message')}")
                    with btn_show:
                        if st.button("🔍 In Trash", key=f"reveal_trash_{item['id']}"):
                            if show_in_explorer(item["trash_path"]):
                                st.toast(f"✅ Opened in File Explorer: {item['trash_path']}", icon="🔍")
                            else:
                                st.error(f"Could not reveal: {item['trash_path']}")

# ----------------- TAB 5: SETTINGS & WORKSPACE -----------------
with tab_settings:
    st.header("⚙️ StorageSense Workspace & Storage Location")
    st.caption("Inspect and configure where StorageSense databases, ChromaDB vector stores, .trash sandboxes, and logs reside.")

    stats = coord.workspace_mgr.get_workspace_stats()
    cur_ws = stats["workspace_dir"]
    free_gb = stats["drive_free_bytes"] / (1024 ** 3)
    total_gb = stats["drive_total_bytes"] / (1024 ** 3)

    st.info(f"📁 **Active StorageSense Workspace**: `{cur_ws}` (Drive `{stats['workspace_drive']}`: **{free_gb:.1f} GB** free of **{total_gb:.1f} GB**)")

    col_m1, col_m2, col_m3, col_m4 = st.columns(4)
    col_m1.metric("SQLite DB (BM25 & Registry)", format_bytes(stats["db_size_bytes"]))
    col_m2.metric("ChromaDB Vectors", format_bytes(stats["chroma_size_bytes"]))
    col_m3.metric("Trash Sandbox", format_bytes(stats["trash_size_bytes"]))
    col_m4.metric("Total StorageSense Footprint", format_bytes(stats["total_workspace_size_bytes"]))

    st.divider()
    st.subheader("🔄 Relocate StorageSense Workspace & Chunks")
    st.markdown("You can configure any local drive or directory (e.g. `F:\\StorageSense`, `D:\\MyStorageSense`). All databases, chunk indices, and temporary files will operate from there.")

    with st.form("workspace_relocate_form"):
        new_ws_path = st.text_input("New Workspace Directory Path:", value=cur_ws)
        migrate_check = st.checkbox("Migrate existing database and chunks to new folder", value=True, help="Copies storagesense.db, chroma_db, and .trash to the new location safely.")
        submit_ws = st.form_submit_button("🚀 Apply & Switch Workspace Directory", type="primary", use_container_width=True)

    if submit_ws and new_ws_path.strip() and Path(new_ws_path.strip()).resolve() != Path(cur_ws).resolve():
        with st.spinner("Validating and switching workspace directory..."):
            ok, msg = coord.workspace_mgr.set_workspace_dir(new_ws_path.strip(), migrate_files=migrate_check)
            if ok:
                st.success(msg)
                st.rerun()
            else:
                st.error(msg)

    st.divider()
    st.caption("Need to return to default PC location?")
    if st.button("↩️ Reset to Default (F:\\ASCENT\\idea\\pc_port)"):
        ok, msg = coord.workspace_mgr.reset_to_default()
        if ok:
            st.success("Reset workspace to default.")
            st.rerun()
        else:
            st.error(msg)

