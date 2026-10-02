import os
import sys
import time
from pathlib import Path
from typing import Optional, List, Dict, Any
from pydantic import BaseModel
from fastapi import FastAPI, HTTPException, Query, BackgroundTasks
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

# Ensure pc_port is on sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from config import SUPPORTED_EXTENSIONS, WORKSPACE_DRIVE, TRASH_DIR
from core.coordinator import EngineCoordinator
from core.system_ops import (
    get_system_telemetry,
    open_file_in_os,
    show_in_explorer,
    get_standard_user_folders,
    find_supported_files
)
from core.logger import setup_logger

logger = setup_logger("VaultServer")

app = FastAPI(
    title="StorageSense Obsidian Vault API",
    description="Local Agentic Storage Intelligence REST Service",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

VAULT_DIR = Path(__file__).resolve().parent / "vault"
VAULT_DIR.mkdir(parents=True, exist_ok=True)

# Lazy coordinator instance
_coordinator: Optional[EngineCoordinator] = None

def get_coord() -> EngineCoordinator:
    global _coordinator
    if _coordinator is None:
        _coordinator = EngineCoordinator()
    return _coordinator

class ChatRequest(BaseModel):
    message: str

class ActionRequest(BaseModel):
    file_path: str

class TrashRestoreRequest(BaseModel):
    entry_id: str

class ScanRequest(BaseModel):
    folder_path: str

class ModelSelectRequest(BaseModel):
    model_name: str

class CleanupRequest(BaseModel):
    target_gb: float = 2.0


@app.get("/", response_class=HTMLResponse)
async def serve_index():
    index_file = VAULT_DIR / "index.html"
    if not index_file.exists():
        return HTMLResponse("<h1>StorageSense Vault Loading...</h1>", status_code=200)
    return HTMLResponse(index_file.read_text(encoding="utf-8"))


@app.get("/api/telemetry")
async def api_telemetry():
    coord = get_coord()
    telemetry = get_system_telemetry()
    llm_telemetry = coord.llm_agent.get_model_telemetry()
    index_stats = coord.get_indexed_stats()
    return {
        "status": "online",
        "telemetry": telemetry,
        "llm": llm_telemetry,
        "stats": index_stats,
        "workspace_drive": WORKSPACE_DRIVE
    }


@app.get("/api/search")
async def api_search(
    q: str = Query(..., description="Search query string"),
    modality: Optional[str] = Query(None, description="Modality filter: docs, images, videos, code, all"),
    top_k: int = Query(20, description="Max results"),
    live_scan: bool = Query(True, description="Enable JIT filesystem discovery")
):
    coord = get_coord()
    ext_filter = None
    if modality:
        m = modality.lower()
        if m in ["docs", "documents", "pdf"]:
            ext_filter = [".pdf", ".docx", ".doc", ".pptx", ".ppt", ".txt", ".md"]
        elif m in ["images", "photos", "media"]:
            ext_filter = [".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"]
        elif m in ["videos"]:
            ext_filter = [".mp4", ".mov", ".mkv", ".avi", ".webm"]
        elif m in ["code", "schemas", "repos"]:
            ext_filter = [".py", ".json", ".csv", ".yaml", ".yml", ".html", ".js", ".ts", ".kt", ".java", ".c", ".cpp"]

    start_t = time.time()
    results = coord.search(
        query=q,
        top_k=top_k,
        file_types=ext_filter,
        enable_live_scan=live_scan
    )
    elapsed_ms = round((time.time() - start_t) * 1000, 2)

    # Format results with rich metadata
    formatted = []
    for r in results:
        fpath = Path(r["file_path"])
        ext = fpath.suffix.lower()
        size_bytes = fpath.stat().st_size if fpath.exists() else 0
        mod_time = fpath.stat().st_mtime if fpath.exists() else 0
        
        # Categorize
        if ext in [".pdf", ".docx", ".doc", ".pptx", ".ppt", ".txt", ".md"]:
            category = "DOCUMENT"
        elif ext in [".png", ".jpg", ".jpeg", ".webp", ".gif"]:
            category = "IMAGE"
        elif ext in [".mp4", ".mov", ".mkv", ".avi"]:
            category = "VIDEO"
        elif ext in [".py", ".json", ".csv", ".yaml", ".html", ".js", ".kt", ".java"]:
            category = "CODE"
        else:
            category = "OTHER"

        formatted.append({
            "file_name": r.get("file_name", fpath.name),
            "file_path": str(fpath),
            "score": round(float(r.get("fused_score", 0.0)), 4),
            "snippet": r.get("best_snippet", ""),
            "is_live_discovery": r.get("is_live_discovery", False),
            "is_chunked": r.get("is_chunked", True),
            "chunk_count": r.get("chunk_count", 0),
            "is_verified_identity": r.get("is_verified_identity"),
            "category": category,
            "extension": ext.replace(".", "").upper(),
            "size_bytes": size_bytes,
            "last_modified": mod_time
        })

    return {
        "query": q,
        "elapsed_ms": elapsed_ms,
        "count": len(formatted),
        "results": formatted
    }


@app.post("/api/chat")
async def api_chat(req: ChatRequest):
    coord = get_coord()
    user_msg = req.message.strip()
    if not user_msg:
        raise HTTPException(status_code=400, detail="Empty message")

    start_t = time.time()
    
    # 1. Search for relevant context across storage
    search_results = coord.search(user_msg, top_k=6, enable_live_scan=True)
    citations = []
    context_chunks = []
    total_bytes_reclaimable = 0

    for r in search_results:
        fpath = Path(r["file_path"])
        fsize = fpath.stat().st_size if fpath.exists() else 0
        total_bytes_reclaimable += fsize
        snippet = r.get("best_snippet", "")
        citations.append({
            "file_name": r.get("file_name", fpath.name),
            "file_path": str(fpath),
            "score": round(float(r.get("fused_score", 0.0)), 4),
            "snippet": snippet,
            "size_bytes": fsize,
            "extension": fpath.suffix.lower().replace(".", "").upper()
        })
        if snippet:
            context_chunks.append(f"[{fpath.name}]: {snippet}")

    # 2. Query LLM agent
    context_text = "\n\n".join(context_chunks)
    try:
        reply_text = coord.llm_agent.ask(user_msg, context=context_text)
    except Exception as e:
        logger.warning(f"Ollama query failed: {e}")
        reply_text = f"I retrieved {len(search_results)} relevant files matching your request from the local metadata index."

    elapsed_ms = round((time.time() - start_t) * 1000, 1)

    return {
        "reply": reply_text,
        "citations": citations,
        "metrics": {
            "elapsed_ms": elapsed_ms,
            "context_chunks_count": len(context_chunks),
            "tokens_estimate": len(reply_text.split()) * 2,
            "matched_files_count": len(citations),
            "reclaimable_bytes": total_bytes_reclaimable
        }
    }


@app.get("/api/dedup")
async def api_dedup():
    coord = get_coord()
    exact_groups = coord.dedup_detector.find_exact_duplicates()
    semantic_groups = coord.dedup_detector.find_semantic_duplicates()
    image_groups = coord.dedup_detector.find_image_duplicates()

    def serialize_group(g):
        return {
            "group_id": g.group_id,
            "type": g.type.value if hasattr(g.type, "value") else str(g.type),
            "score": round(float(g.similarity_score), 3),
            "keep": {
                "name": g.keep_candidate.name,
                "path": g.keep_candidate.path,
                "size_bytes": g.keep_candidate.size_bytes
            },
            "duplicates": [
                {
                    "name": d.name,
                    "path": d.path,
                    "size_bytes": d.size_bytes
                }
                for d in g.delete_candidates
            ]
        }

    return {
        "exact_duplicates": [serialize_group(g) for g in exact_groups],
        "semantic_duplicates": [serialize_group(g) for g in semantic_groups],
        "image_duplicates": [serialize_group(g) for g in image_groups]
    }


@app.post("/api/cleanup")
async def api_cleanup(req: CleanupRequest):
    coord = get_coord()
    proposal = coord.space_reclaimer.propose_reclaim(target_gb=req.target_gb)
    return {
        "target_gb": req.target_gb,
        "reclaimable_bytes": proposal.total_reclaimable_bytes,
        "files_count": len(proposal.candidates),
        "candidates": [
            {
                "path": c["path"],
                "name": Path(c["path"]).name,
                "size_bytes": c["size_bytes"],
                "reason": c["reason"],
                "category": c.get("category", "General")
            }
            for c in proposal.candidates
        ]
    }


@app.get("/api/trash")
async def api_trash_list():
    coord = get_coord()
    items = coord.trash_manager.list_trash()
    stats = coord.trash_manager.get_trash_stats()
    return {
        "stats": stats,
        "items": items
    }


@app.post("/api/trash")
async def api_move_to_trash(req: ActionRequest):
    coord = get_coord()
    res = coord.trash_manager.move_to_trash(req.file_path, reason="User requested quarantine via Obsidian Vault")
    if not res:
        raise HTTPException(status_code=400, detail="Failed to quarantine file")
    return {"status": "success", "message": f"Moved {Path(req.file_path).name} to quarantine sandbox"}


@app.post("/api/restore")
async def api_restore_from_trash(req: TrashRestoreRequest):
    coord = get_coord()
    res = coord.trash_manager.restore_from_trash(req.entry_id)
    if not res:
        raise HTTPException(status_code=400, detail="Failed to restore file from quarantine")
    return {"status": "success", "message": "File restored to original path successfully"}


@app.post("/api/open")
async def api_open_file(req: ActionRequest):
    success = open_file_in_os(req.file_path)
    if not success:
        raise HTTPException(status_code=400, detail="Could not open file in OS")
    return {"status": "success", "message": f"Opened {Path(req.file_path).name}"}


@app.post("/api/reveal")
async def api_reveal_file(req: ActionRequest):
    success = show_in_explorer(req.file_path)
    if not success:
        raise HTTPException(status_code=400, detail="Could not reveal file in File Explorer")
    return {"status": "success", "message": f"Revealed {Path(req.file_path).name} in Explorer"}


@app.post("/api/scan")
async def api_scan(req: ScanRequest, bg_tasks: BackgroundTasks):
    target = Path(req.folder_path).resolve()
    if not target.exists():
        raise HTTPException(status_code=400, detail="Directory path does not exist")

    def run_indexing(p: Path):
        coord = get_coord()
        files = find_supported_files(p, SUPPORTED_EXTENSIONS)
        logger.info(f"Background indexing started for {len(files)} files in {p}")
        for f in files:
            try:
                coord.search_engine.index_file(f)
            except Exception as e:
                logger.warning(f"Error indexing {f}: {e}")
        logger.info(f"Background indexing completed for {p}")

    bg_tasks.add_task(run_indexing, target)
    return {"status": "accepted", "message": f"Indexing queued for {target}"}


@app.get("/api/presets")
async def api_presets():
    return get_standard_user_folders()


@app.get("/api/models")
async def api_models():
    coord = get_coord()
    models = coord.llm_agent.get_available_models()
    return {
        "active_model": coord.llm_agent.model,
        "available_models": models
    }


@app.post("/api/set_model")
async def api_set_model(req: ModelSelectRequest):
    coord = get_coord()
    coord.llm_agent.set_active_model(req.model_name)
    return {
        "status": "success",
        "active_model": coord.llm_agent.model
    }
