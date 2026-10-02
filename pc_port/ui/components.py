import streamlit as st
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, List
from core.system_ops import open_file_in_os, show_in_explorer
from core.logger import setup_logger

logger = setup_logger("UIComponents")

def format_bytes(size_bytes: int) -> str:
    if size_bytes < 1024:
        return f"{size_bytes} B"
    elif size_bytes < 1024 * 1024:
        return f"{size_bytes / 1024:.1f} KB"
    elif size_bytes < 1024 * 1024 * 1024:
        return f"{size_bytes / (1024 * 1024):.1f} MB"
    else:
        return f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"

def format_timestamp(ts: float) -> str:
    try:
        return datetime.fromtimestamp(ts).strftime("%b %d, %Y %H:%M")
    except Exception:
        return "Unknown"

def get_file_badge_color(ext: str) -> str:
    ext = ext.lower()
    if ext == ".pdf":
        return "#e63946"
    elif ext in [".docx", ".doc"]:
        return "#2a9d8f"
    elif ext in [".pptx", ".ppt"]:
        return "#e76f51"
    elif ext in [".png", ".jpg", ".jpeg", ".webp"]:
        return "#9d4edd"
    elif ext in [".py"]:
        return "#3572A5"
    elif ext in [".json", ".csv"]:
        return "#2ea44f"
    elif ext in [".md", ".txt", ".log"]:
        return "#58a6ff"
    else:
        return "#457b9d"

def render_file_result_card(file_data: Dict[str, Any], rank: int = 1, coord=None):
    fpath = Path(file_data["file_path"])
    score = file_data.get("fused_score", 0.0)
    snippet = file_data.get("best_snippet", "")
    is_live = file_data.get("is_live_discovery", False)
    is_chunked = file_data.get("is_chunked", not is_live)
    chunk_count = file_data.get("chunk_count", 0)
    verified_id = file_data.get("is_verified_identity")
    ext = fpath.suffix.lower()
    badge_color = get_file_badge_color(ext)
    size_str = format_bytes(fpath.stat().st_size) if fpath.exists() else "Unknown"
    fhash = abs(hash(str(fpath)))

    with st.container(border=True):
        col1, col2 = st.columns([7, 3])
        with col1:
            verified_badge = ""
            if verified_id:
                verified_badge = f"<span style='background-color:#059669; color:white; padding:3px 8px; border-radius:4px; font-size:11px; font-weight:bold; margin-left:8px;'>🛡️ VERIFIED {verified_id.upper()}</span>"

            if is_chunked:
                chunk_badge = f"<span style='background-color:#10b981; color:#000; padding:2px 8px; border-radius:4px; font-size:11px; font-weight:bold; margin-left:8px;'>📦 CHUNKED IN DB ({chunk_count} chunks)</span>"
            else:
                chunk_badge = "<span style='background-color:#f59e0b; color:#000; padding:2px 8px; border-radius:4px; font-size:11px; font-weight:bold; margin-left:8px;'>⚡ UNCHUNKED (ON-DISK)</span>"

            st.markdown(
                f"<span style='background-color:{badge_color}; color:white; padding:3px 8px; border-radius:4px; font-size:11px; font-weight:bold;'>{ext.upper().replace('.', '') or 'FILE'}</span> "
                f"<strong style='font-size:16px;'>#{rank} {file_data['file_name']}</strong>{verified_badge}{chunk_badge}",
                unsafe_allow_html=True
            )
            file_uri = f"file:///{str(fpath).replace('\\', '/')}"
            st.markdown(
                f"<a href='{file_uri}' target='_blank' style='font-size:12px; color:#58a6ff; text-decoration:none;'>🔗 Direct File Link: <code>{fpath}</code></a>",
                unsafe_allow_html=True
            )
        with col2:
            st.markdown(f"**Relevance Score:** `{score:.4f}`")
            st.caption(f"Size: {size_str}")

        if snippet:
            st.markdown(
                f"<div style='background-color:#1e2430; border-left:4px solid {badge_color}; padding:10px 14px; border-radius:0 6px 6px 0; font-size:13px; color:#d0d7de; margin:8px 0;'>"
                f"{snippet}</div>",
                unsafe_allow_html=True
            )

        # Full-Width Prominent Action Toolbar
        col_act1, col_act2, col_act3 = st.columns([1.5, 1.8, 3.5])
        with col_act1:
            if st.button("📂 Open File", key=f"open_{rank}_{fhash}", use_container_width=True):
                if open_file_in_os(str(fpath)):
                    st.session_state[f"action_status_{fhash}"] = ("success", f"📂 Launched **{fpath.name}** in default application. (Check your taskbar if the window opened behind your browser)")
                    st.toast(f"✅ Opening {fpath.name} in default application...", icon="📂")
                else:
                    st.session_state[f"action_status_{fhash}"] = ("error", f"❌ Could not open file: {fpath}")
                    st.error(f"Could not open file: {fpath}")
        with col_act2:
            if st.button("🔍 Show in Folder", key=f"rev_{rank}_{fhash}", use_container_width=True):
                if show_in_explorer(str(fpath)):
                    st.session_state[f"action_status_{fhash}"] = ("success", f"🔍 Opened Windows File Explorer in `{fpath.parent}` highlighting **{fpath.name}**.")
                    st.toast(f"✅ Opened in File Explorer: {fpath.parent}", icon="🔍")
                else:
                    st.session_state[f"action_status_{fhash}"] = ("error", f"❌ Could not reveal folder for: {fpath}")
                    st.error(f"Could not reveal folder for: {fpath}")
        with col_act3:
            if coord:
                if is_chunked:
                    if st.button("🗑️ Unchunk (Remove from Index)", key=f"unchunk_{rank}_{fhash}", use_container_width=True, help="Removes this file's chunks from ChromaDB & SQLite without deleting the actual file"):
                        if coord.unchunk_file(str(fpath)):
                            file_data["is_chunked"] = False
                            file_data["chunk_count"] = 0
                            st.session_state[f"action_status_{fhash}"] = ("info", f"🗑️ Unchunked **{fpath.name}** from database index.")
                            st.toast(f"Removed chunks for {fpath.name}", icon="🗑️")
                            st.rerun()
                else:
                    if st.button("⚡ Chunk & Index File to DB", key=f"chunk_{rank}_{fhash}", type="primary", use_container_width=True, help="Indexes chunks into ChromaDB vector store and SQLite FTS5 BM25"):
                        with st.spinner(f"Chunking {fpath.name}..."):
                            if coord.chunk_file(str(fpath)):
                                file_data["is_chunked"] = True
                                c_info = coord.is_file_chunked(str(fpath))
                                file_data["chunk_count"] = c_info.get("chunk_count", 1)
                                st.session_state[f"action_status_{fhash}"] = ("success", f"⚡ Indexed **{fpath.name}** ({file_data['chunk_count']} chunks) into ChromaDB & SQLite.")
                                st.toast(f"Indexed {fpath.name} ({file_data['chunk_count']} chunks)!", icon="⚡")
                                st.rerun()

        # Display persistent action feedback directly inside the card
        if f"action_status_{fhash}" in st.session_state:
            status_type, status_text = st.session_state[f"action_status_{fhash}"]
            if status_type == "success":
                st.success(status_text)
            elif status_type == "error":
                st.error(status_text)
            elif status_type == "info":
                st.info(status_text)

        if fpath.exists():
            if ext in [".png", ".jpg", ".jpeg", ".webp"]:
                with st.expander("🖼️ Preview Image", expanded=False):
                    try:
                        st.image(str(fpath), use_container_width=True)
                    except Exception:
                        st.caption("Unable to load image preview.")
            elif ext in [".txt", ".md", ".py", ".json", ".csv", ".log", ".java", ".c", ".cpp", ".html", ".js"]:
                with st.expander("📄 Preview Content", expanded=False):
                    try:
                        with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                            sample_text = fp.read(3000)
                        lang = "python" if ext == ".py" else ("json" if ext == ".json" else ("markdown" if ext == ".md" else "text"))
                        st.code(sample_text, language=lang)
                    except Exception as e:
                        st.caption(f"Unable to read preview: {e}")

def render_execution_trace(trace: Dict[str, Any]):
    """Renders observable agent execution trace with live filesystem diagnostics."""
    latency = trace.get('total_search_time_ms', 0)
    live_diag = trace.get("live_diagnostics", {})
    has_live = trace.get("live_scan_hits", 0) > 0 or live_diag.get("files_examined", 0) > 0
    files_inspected = live_diag.get("files_examined", 0)

    with st.expander(f"🧠 Observable Real-Time Execution Trace ({latency}ms | {files_inspected} files examined on disk)", expanded=False):
        cols = st.columns(5 if has_live else 4)
        cols[0].metric("BM25 Lexical Hits", trace.get("bm25_hits", 0), f"{trace.get('bm25_time_ms', 0)} ms")
        cols[1].metric("Chroma Vector Hits", trace.get("vector_hits", 0), f"{trace.get('vector_time_ms', 0)} ms")
        cols[2].metric("RRF Rank Fusion", "k=60", f"{trace.get('rrf_time_ms', 0)} ms")
        if has_live:
            cols[3].metric("⚡ Live Files Examined", files_inspected, f"{trace.get('live_scan_time_ms', 0)} ms")
            cols[4].metric("Top Score", trace.get("top_score", 0.0), f"{latency} ms total")
        else:
            cols[3].metric("Top Score", trace.get("top_score", 0.0), f"{latency} ms total")

        if live_diag:
            roots_str = ", ".join([f"`{r}`" for r in live_diag.get("roots_scanned", [])[:6]])
            st.markdown(
                f"**Real-Time Laptop Scan Proof**: Evaluated **{files_inspected}** files across active roots: {roots_str} "
                f"in **{live_diag.get('scan_time_ms', 0)} ms** with zero prior pre-indexing required."
            )
        st.caption("Pipeline: Query Intent ➔ Inverted BM25 Index ➔ ChromaDB Vector Space ➔ Live Filesystem Sweeper ➔ Reciprocal Rank Fusion ➔ Identity Verifier")


def render_duplicate_card(group, idx: int, on_clean_callback=None):
    with st.container(border=True):
        col1, col2 = st.columns([4, 1])
        with col1:
            st.markdown(f"#### Group #{idx+1} — `{group.match_type}`")
            st.caption(f"Reclaimable Space: **{format_bytes(group.reclaimable_bytes)}** | Confidence: **{group.confidence*100:.0f}%**")
        with col2:
            st.success("Analysis Ready")

        # Side-by-side comparison
        col_keep, col_del = st.columns(2)
        with col_keep:
            with st.container(border=True):
                st.markdown("✅ **KEEP (Newest Modified)**")
                kp = Path(group.recommended_keep_path)
                st.markdown(f"**{kp.name}**")
                st.caption(f"Path: `{kp}`")
                if kp.exists():
                    st.caption(f"Size: {format_bytes(kp.stat().st_size)} | Modified: {format_timestamp(kp.stat().st_mtime)}")
                    btn_k1, btn_k2 = st.columns(2)
                    kp_hash = abs(hash(str(kp)))
                    with btn_k1:
                        if st.button("📂 Open", key=f"open_keep_{idx}_{kp_hash}"):
                            if open_file_in_os(str(kp)):
                                st.toast(f"✅ Opening {kp.name} in default application...", icon="📂")
                            else:
                                st.error(f"Could not open file: {kp}")
                    with btn_k2:
                        if st.button("🔍 Reveal", key=f"rev_keep_{idx}_{kp_hash}"):
                            if show_in_explorer(str(kp)):
                                st.toast(f"✅ Opened in File Explorer: {kp.parent}", icon="🔍")
                            else:
                                st.error(f"Could not reveal: {kp}")

                    if kp.suffix.lower() in [".png", ".jpg", ".jpeg", ".webp"]:
                        try:
                            st.image(str(kp), width=220)
                        except Exception as e_img:
                            logger.debug(f"Could not render image preview for {kp}: {e_img}")
                            st.caption("*(Image preview unavailable)*")
                    elif kp.suffix.lower() in [".txt", ".md", ".py", ".json", ".csv"]:
                        with st.expander("📄 View Snippet", expanded=False):
                            try:
                                with open(kp, "r", encoding="utf-8", errors="ignore") as fp:
                                    st.code(fp.read(1500))
                            except Exception as e_snip:
                                logger.debug(f"Could not read snippet for {kp}: {e_snip}")
                                st.caption("*(Text snippet unavailable)*")

        with col_del:
            with st.container(border=True):
                st.markdown("🗑️ **DELETE CANDIDATES (Older / Copies)**")
                for c_idx, c in enumerate(group.candidates_to_delete):
                    cp = Path(c)
                    cp_hash = abs(hash(str(cp)))
                    st.markdown(f"❌ **{cp.name}**")
                    st.caption(f"Path: `{cp}`")
                    if cp.exists():
                        st.caption(f"Size: {format_bytes(cp.stat().st_size)} | Modified: {format_timestamp(cp.stat().st_mtime)}")
                        btn_c1, btn_c2 = st.columns(2)
                        with btn_c1:
                            if st.button("📂 Open", key=f"open_cand_{idx}_{c_idx}_{cp_hash}"):
                                if open_file_in_os(str(cp)):
                                    st.toast(f"✅ Opening {cp.name} in default application...", icon="📂")
                                else:
                                    st.error(f"Could not open file: {cp}")
                        with btn_c2:
                            if st.button("🔍 Reveal", key=f"rev_cand_{idx}_{c_idx}_{cp_hash}"):
                                if show_in_explorer(str(cp)):
                                    st.toast(f"✅ Opened in File Explorer: {cp.parent}", icon="🔍")
                                else:
                                    st.error(f"Could not reveal: {cp}")

                        if cp.suffix.lower() in [".png", ".jpg", ".jpeg", ".webp"]:
                            try:
                                st.image(str(cp), width=220)
                            except Exception as e_cimg:
                                logger.debug(f"Could not render image preview for {cp}: {e_cimg}")
                                st.caption("*(Image preview unavailable)*")
                        elif cp.suffix.lower() in [".txt", ".md", ".py", ".json", ".csv"]:
                            with st.expander(f"📄 View Candidate #{c_idx+1}", expanded=False):
                                try:
                                    with open(cp, "r", encoding="utf-8", errors="ignore") as fp:
                                        st.code(fp.read(1500))
                                except Exception as e_csnip:
                                    logger.debug(f"Could not read snippet for {cp}: {e_csnip}")
                                    st.caption("*(Text snippet unavailable)*")
