import argparse
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

if sys.platform == "win32":
    try:
        if hasattr(sys.stdin, "reconfigure"):
            sys.stdin.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError) as e_enc:
        _ = e_enc  # Fallback for non-interactive Windows consoles

from config import SUPPORTED_EXTENSIONS
from core.coordinator import EngineCoordinator
from core.system_ops import get_system_telemetry, open_file_in_os, show_in_explorer, get_standard_user_folders, find_supported_files
from ui.components import format_bytes

def main():
    parser = argparse.ArgumentParser(description="StorageSense CLI — Local Agentic Storage Intelligence (PC Port)")
    parser.add_argument("--scan", type=str, help="Scan and index files in the specified directory")
    parser.add_argument("--search", type=str, help="Run a natural language or keyword query")
    parser.add_argument("--disk", type=str, help="Target a specific disk or drive letter (e.g. D:, F:)")
    parser.add_argument("--mode", type=str, choices=["hybrid", "live", "indexed"], default="hybrid", help="Search mode: hybrid, live (bypasses index), or indexed")
    parser.add_argument("--allow-chunk", action="store_true", help="Allow automatic chunking of discovered unindexed files")
    parser.add_argument("--chunk", type=str, help="Chunk and index a specific file on-demand")
    parser.add_argument("--unchunk", type=str, help="Unchunk and remove a file from index")
    parser.add_argument("--dedup", action="store_true", help="Scan for duplicate files (exact, semantic, and image)")
    parser.add_argument("--clean", type=float, help="Recommend files to delete to free X GB")
    parser.add_argument("--yes", "-y", action="store_true", help="Automatically confirm safe trash movement for clean and dedup")
    parser.add_argument("--trash-list", action="store_true", help="List all quarantined files in trash")
    parser.add_argument("--restore", type=str, help="Restore a file from trash using entry ID")
    parser.add_argument("--telemetry", action="store_true", help="Display live system hardware and storage metrics")
    parser.add_argument("--models", action="store_true", help="List all auto-detected local Ollama models")
    parser.add_argument("--model", type=str, help="Specify active Ollama model for this execution")
    parser.add_argument("--open", type=str, help="Open file in its default OS application")
    parser.add_argument("--reveal", type=str, help="Reveal file in Windows File Explorer")
    parser.add_argument("--workspace", action="store_true", help="Display current StorageSense workspace and storage breakdown")
    parser.add_argument("--set-workspace", type=str, help="Configure and switch StorageSense workspace directory")

    args = parser.parse_args()

    coord = EngineCoordinator()
    search_engine = coord.search_engine
    llm = coord.llm_agent
    dedup = coord.dedup_detector
    reclaimer = coord.space_reclaimer
    actions = coord.action_engine

    # If custom model requested
    if args.model:
        if not llm.set_active_model(args.model):
            print(f"Warning: Requested model '{args.model}' not found in Ollama. Using auto-detected model: '{llm.model}'")

    if args.workspace:
        stats = coord.workspace_mgr.get_workspace_stats()
        print("=" * 60)
        print("⚙️ StorageSense Workspace & Storage Location")
        print("=" * 60)
        print(f"Workspace Path:    {stats['workspace_dir']}")
        print(f"Drive:             {stats['workspace_drive']} ({stats['drive_free_bytes']/(1024**3):.1f} GB free)")
        print(f"SQLite DB:         {stats['db_path']} ({format_bytes(stats['db_size_bytes'])})")
        print(f"ChromaDB Vectors:  {stats['chroma_dir']} ({format_bytes(stats['chroma_size_bytes'])})")
        print(f"Trash Sandbox:     {stats['trash_dir']} ({format_bytes(stats['trash_size_bytes'])})")
        print(f"Logs:              {stats['log_dir']} ({format_bytes(stats['log_size_bytes'])})")
        print(f"Total Footprint:   {format_bytes(stats['total_workspace_size_bytes'])}")
        print("=" * 60)

    elif args.set_workspace:
        target = args.set_workspace.strip()
        ok, msg = coord.workspace_mgr.set_workspace_dir(target, migrate_files=True)
        if ok:
            print(f"Success: {msg}")
        else:
            print(f"Error: {msg}")

    elif args.models:
        available = llm.get_available_models()
        print("=" * 60)
        print("⚡ Auto-Detected Local Ollama Models")
        print("=" * 60)
        if not available:
            print("No models detected or Ollama daemon is offline.")
        else:
            for m in available:
                active_mark = " (Active)" if m == llm.model else ""
                print(f"- {m}{active_mark}")
        print("=" * 60)

    elif args.telemetry:
        tel = get_system_telemetry()
        stats = coord.get_indexed_stats()
        llm_stat = llm.get_model_telemetry()
        print("=" * 60)
        print("🧠 StorageSense System Telemetry")
        print("=" * 60)
        print(f"System RAM:       {tel['ram_used_gb']} GB used / {tel['ram_total_gb']} GB total ({tel['ram_percent']}%)")
        print(f"Drive ({tel['drive_name']}):       {tel['drive_free_gb']} GB free / {tel['drive_total_gb']} GB")
        print(f"Active Ollama:    {llm_stat['active_model']} [{llm_stat['status'].upper()}] (Latency: {llm_stat['latency_ms']} ms)")
        print(f"Indexed Files:    {stats['total_files']} files ({format_bytes(stats['total_bytes'])})")
        print(f"Indexed Chunks:   {stats['total_chunks']} chunks in FTS5 & ChromaDB")
        print(f"Quarantined Trash:{stats['trashed_files']} files ({format_bytes(stats['trashed_bytes'])})")
        print("=" * 60)

    elif args.open:
        if open_file_in_os(args.open):
            print(f"Launched in default application: {args.open}")
        else:
            print(f"Failed to open file: {args.open}")

    elif args.reveal:
        if show_in_explorer(args.reveal):
            print(f"Revealed in Windows Explorer: {args.reveal}")
        else:
            print(f"Failed to reveal file: {args.reveal}")

    elif args.chunk:
        p = Path(args.chunk).resolve()
        if not p.exists() or not p.is_file():
            print(f"Error: File '{args.chunk}' does not exist.")
            return
        if coord.chunk_file(p):
            info = coord.is_file_chunked(p)
            print(f"[SUCCESS] Indexed and chunked: {p.name} ({info['chunk_count']} chunks in FTS5/ChromaDB)")
        else:
            print(f"[ERROR] Failed to chunk: {args.chunk}")

    elif args.unchunk:
        p = Path(args.unchunk).resolve()
        if coord.unchunk_file(p):
            print(f"[SUCCESS] Unchunked and removed from index: {p.name}")
        else:
            print(f"[ERROR] Failed to unchunk: {args.unchunk}")

    elif args.scan:
        target = Path(args.scan).resolve()
        if not target.exists():
            print(f"Error: Path {target} does not exist.")
            return

        print(f"Scanning directory: {target} ...")
        files = find_supported_files(target, SUPPORTED_EXTENSIONS)
        count = 0
        for f in files:
            if search_engine.index_file(f):
                count += 1
                print(f"[{count}/{len(files)}] Indexed: {f.name}")
        print(f"\nDone! Indexed {count} files.")

    elif args.search:
        print(f"\nSearching for: '{args.search}'")
        intent = llm.parse_intent(args.search)
        target_drive = args.disk or intent.get("target_drive")
        search_mode = "live_only" if args.mode == "live" else ("indexed_only" if args.mode == "indexed" else "hybrid")
        clean_q = intent.get("query", args.search)

        print(f"Intent Action: {intent.get('action')} | Query: {clean_q} (Target Disk: {target_drive or 'All Drives'} | Mode: {search_mode})")

        results, trace = search_engine.search_with_trace(
            clean_q,
            top_k=5,
            target_drive=target_drive,
            search_mode=search_mode,
            auto_chunk=args.allow_chunk
        )
        live_info = f" | Live Discovery hits={trace.get('live_scan_hits', 0)} ({trace.get('live_scan_time_ms', 0)}ms)" if trace.get("live_scan_hits") else ""
        print(f"\nObservable Agent Trace: BM25 hits={trace['bm25_hits']} ({trace['bm25_time_ms']}ms) | Vector hits={trace['vector_hits']} ({trace['vector_time_ms']}ms){live_info} | Total={trace['total_search_time_ms']}ms")

        if not results:
            print("No matching files found.")
            return

        print(f"\nTop {len(results)} Matches:")
        for idx, r in enumerate(results, 1):
            if r.get("is_chunked"):
                chunk_tag = f" [CHUNKED: {r.get('chunk_count', 0)} chunks]"
            else:
                chunk_tag = " [UNCHUNKED (LIVE SCAN)]"
            print(f"\n#{idx} {r['file_name']}{chunk_tag} (Score: {r['fused_score']:.4f})")
            print(f"    Path: {r['file_path']}")
            print(f"    Snippet: {r['best_snippet'][:120]}...")

        print(f"\nSynthesizing AI Summary ({llm.model}):")
        for token in llm.generate_rag_stream(args.search, results):
            sys.stdout.write(token)
            sys.stdout.flush()
        print()

    elif args.dedup:
        print("\nScanning for duplicate clusters (exact hashes, semantic centroids, and perceptual images)...")
        groups = dedup.find_all_duplicates()
        if not groups:
            print("No duplicates detected.")
            return

        total_reclaimable = sum(g.reclaimable_bytes for g in groups)
        print(f"Found {len(groups)} duplicate clusters (Total Reclaimable: {format_bytes(total_reclaimable)}):\n")
        all_del_candidates = []
        for idx, g in enumerate(groups, 1):
            print(f"Group #{idx} [{g.match_type}] (Confidence: {g.confidence*100:.0f}%):")
            print(f"  [KEEP]   {g.recommended_keep_path}")
            for c in g.candidates_to_delete:
                print(f"  [DELETE] {c}")
                all_del_candidates.append(c)
            print()

        if all_del_candidates:
            confirmed = args.yes
            if not confirmed:
                try:
                    ans = input(f"Quarantine older copies ({len(all_del_candidates)} files, {format_bytes(total_reclaimable)}) to safe trash? [y/N]: ").strip().lower()
                    confirmed = ans in ["y", "yes"]
                except (EOFError, KeyboardInterrupt):
                    confirmed = False

            if confirmed:
                prop = actions.create_proposal("DEDUPLICATE", f"Clean {len(all_del_candidates)} duplicates", all_del_candidates)
                res = actions.execute_proposal(prop)
                print(f"\n[SUCCESS] Moved {res['success_count']} duplicate files to safe trash! Freed {format_bytes(res['freed_bytes'])}")

    elif args.clean is not None:
        target_bytes = int(args.clean * 1024 * 1024 * 1024)
        print(f"\nAnalyzing cleanup recommendations to free ~{args.clean} GB...")
        candidates = reclaimer.recommend_cleanup(target_bytes=target_bytes)
        if not candidates:
            print("No safe cleanup candidates found.")
            return

        total_size = sum(c.file_size for c in candidates)
        print(f"Found {len(candidates)} candidates freeing {format_bytes(total_size)}:\n")
        for c in candidates:
            print(f"- {c.file_name} ({format_bytes(c.file_size)}): {c.reason}")

        confirmed = args.yes
        if not confirmed:
            try:
                ans = input(f"\nMove these {len(candidates)} candidate files ({format_bytes(total_size)}) to safe trash? [y/N]: ").strip().lower()
                confirmed = ans in ["y", "yes"]
            except (EOFError, KeyboardInterrupt):
                confirmed = False

        if confirmed:
            paths = [c.file_path for c in candidates]
            prop = actions.create_proposal("FREE_SPACE", f"Clean {format_bytes(total_size)}", paths)
            res = actions.execute_proposal(prop)
            print(f"\n[SUCCESS] Moved {res['success_count']} files to safe trash! Freed {format_bytes(res['freed_bytes'])}")

    elif args.trash_list:
        items = actions.trash_manager.list_trash()
        print(f"\nQuarantined Trash Items ({len(items)}):")
        for item in items:
            print(f"- ID: {item['id']} | {item['file_name']} ({format_bytes(item['file_size'])}) -> {item['original_path']}")

    elif args.restore:
        res = actions.undo_trash(args.restore)
        if res.get("success"):
            print(f"Successfully restored {res['file_name']} to {res['path']}")
        else:
            print(f"Failed to restore entry {args.restore}: {res.get('message')}")

    else:
        interactive_cli(coord)

def interactive_cli(coord):
    print("=" * 60)
    print("🧠 StorageSense Interactive CLI Assistant")
    print("=" * 60)
    print("Commands:")
    print("  • Type any topic or query to search (e.g. 'DBMS notes' or 'first-page text in D:')")
    print("  • 'open <#>'     - Open search result #1..N in default OS application")
    print("  • 'reveal <#>'   - Reveal search result in Windows File Explorer")
    print("  • 'chunk <#>'    - Allow & index/chunk search result #1..N on-demand")
    print("  • 'unchunk <#>'  - Remove search result #1..N chunks from database")
    print("  • 'disk <D:|all>'- Set active search disk (e.g. 'disk D:' or 'disk all')")
    print("  • 'mode <mode>'  - Set search mode ('hybrid', 'live', or 'indexed')")
    print("  • 'autochunk'    - Toggle auto-chunk allow/deny for live discovery")
    print("  • 'dedup'        - Scan for duplicate files and optionally quarantine")
    print("  • 'clean [GB]'   - Propose & execute safe cleanup to free space")
    print("  • 'trash'        - List quarantined files in trash")
    print("  • 'restore <id>' - Restore a file from quarantined trash")
    print("  • 'scan [path]'  - Index files in a folder (or 'scan all')")
    print("  • 'telemetry'    - View system RAM, drive space, and Ollama status")
    print("  • 'help'         - Show this command list")
    print("  • 'exit'/'q'     - Quit")
    print("=" * 60)

    last_search_results = []
    active_disk = None
    active_mode = "hybrid"
    auto_chunk_enabled = False

    while True:
        try:
            line = input("\nStorageSense> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye!")
            break

        if not line:
            continue
        cmd_lower = line.lower()
        if cmd_lower in ["exit", "quit", "q"]:
            print("Goodbye!")
            break
        elif cmd_lower in ["help", "?"]:
            print("Commands: Type a search query, or 'open <#>', 'reveal <#>', 'chunk <#>', 'unchunk <#>', 'disk <D:>', 'mode <live|hybrid>', 'autochunk', 'dedup', 'clean', 'trash', 'restore <id>', 'scan', 'telemetry', 'exit'")
        elif cmd_lower == "telemetry":
            tel = get_system_telemetry()
            stats = coord.get_indexed_stats()
            llm_stat = coord.llm_agent.get_model_telemetry()
            print("=" * 60)
            print(f"System RAM:       {tel['ram_used_gb']} GB / {tel['ram_total_gb']} GB ({tel['ram_percent']}%)")
            print(f"Drive ({tel['drive_name']}):       {tel['drive_free_gb']} GB free / {tel['drive_total_gb']} GB")
            print(f"Active Ollama:    {llm_stat['active_model']} [{llm_stat['status'].upper()}]")
            print(f"Indexed Files:    {stats['total_files']} files ({format_bytes(stats['total_bytes'])})")
            print(f"Quarantined Trash:{stats['trashed_files']} files ({format_bytes(stats['trashed_bytes'])})")
        elif cmd_lower.startswith("workspace"):
            parts = line.split(maxsplit=1)
            if len(parts) > 1 and parts[1].strip():
                new_ws = parts[1].strip().strip('"\'')
                ok, msg = coord.workspace_mgr.set_workspace_dir(new_ws, migrate_files=True)
                if ok:
                    print(f"Success: {msg}")
                else:
                    print(f"Error: {msg}")
            else:
                stats = coord.workspace_mgr.get_workspace_stats()
                print("=" * 60)
                print("⚙️ StorageSense Workspace & Storage Location")
                print("=" * 60)
                print(f"Workspace Path:    {stats['workspace_dir']}")
                print(f"Drive:             {stats['workspace_drive']} ({stats['drive_free_bytes']/(1024**3):.1f} GB free)")
                print(f"SQLite DB:         {stats['db_path']} ({format_bytes(stats['db_size_bytes'])})")
                print(f"ChromaDB Vectors:  {stats['chroma_dir']} ({format_bytes(stats['chroma_size_bytes'])})")
                print(f"Trash Sandbox:     {stats['trash_dir']} ({format_bytes(stats['trash_size_bytes'])})")
                print(f"Total Footprint:   {format_bytes(stats['total_workspace_size_bytes'])}")
                print("=" * 60)
        elif cmd_lower.startswith("open"):
            parts = line.split(maxsplit=1)
            target = parts[1].strip() if len(parts) > 1 else ""
            if target.isdigit():
                idx = int(target)
                if 1 <= idx <= len(last_search_results):
                    target_file = last_search_results[idx - 1]["file_path"]
                    if open_file_in_os(target_file):
                        print(f"Opened: {target_file}")
                    else:
                        print(f"Failed to open: {target_file}")
                else:
                    print(f"Result #{idx} not found. You have {len(last_search_results)} search results.")
            elif target:
                clean_target = target.strip('"\'')
                if open_file_in_os(clean_target):
                    print(f"Opened: {clean_target}")
                else:
                    print(f"Failed to open: {clean_target}")
            else:
                print("Usage: open <number> (e.g. 'open 1') or open <path>")
        elif cmd_lower.startswith("reveal"):
            parts = line.split(maxsplit=1)
            target = parts[1].strip() if len(parts) > 1 else ""
            if target.isdigit():
                idx = int(target)
                if 1 <= idx <= len(last_search_results):
                    target_file = last_search_results[idx - 1]["file_path"]
                    if show_in_explorer(target_file):
                        print(f"Revealed in Explorer: {target_file}")
                    else:
                        print(f"Failed to reveal: {target_file}")
                else:
                    print(f"Result #{idx} not found. You have {len(last_search_results)} search results.")
            elif target:
                clean_target = target.strip('"\'')
                if show_in_explorer(clean_target):
                    print(f"Revealed in Explorer: {clean_target}")
                else:
                    print(f"Failed to reveal: {clean_target}")
            else:
                print("Usage: reveal <number> (e.g. 'reveal 1') or reveal <path>")
        elif cmd_lower.startswith("chunk"):
            parts = line.split(maxsplit=1)
            target = parts[1].strip() if len(parts) > 1 else ""
            target_path = None
            if target.isdigit():
                idx = int(target)
                if 1 <= idx <= len(last_search_results):
                    target_path = last_search_results[idx - 1]["file_path"]
                else:
                    print(f"Result #{idx} not found.")
            elif target:
                target_path = target.strip('"\'')
            if not target_path:
                print("Usage: chunk <number> (e.g. 'chunk 1') or chunk <path>")
            else:
                p = Path(target_path).resolve()
                if coord.chunk_file(p):
                    info = coord.is_file_chunked(p)
                    print(f"[SUCCESS] Indexed and chunked: {p.name} ({info['chunk_count']} chunks in FTS5/ChromaDB)")
                else:
                    print(f"[ERROR] Failed to chunk: {target_path}")

        elif cmd_lower.startswith("unchunk"):
            parts = line.split(maxsplit=1)
            target = parts[1].strip() if len(parts) > 1 else ""
            target_path = None
            if target.isdigit():
                idx = int(target)
                if 1 <= idx <= len(last_search_results):
                    target_path = last_search_results[idx - 1]["file_path"]
                else:
                    print(f"Result #{idx} not found.")
            elif target:
                target_path = target.strip('"\'')
            if not target_path:
                print("Usage: unchunk <number> (e.g. 'unchunk 1') or unchunk <path>")
            else:
                p = Path(target_path).resolve()
                if coord.unchunk_file(p):
                    print(f"[SUCCESS] Unchunked and removed from index: {p.name}")
                else:
                    print(f"[ERROR] Failed to unchunk: {target_path}")

        elif cmd_lower.startswith("disk"):
            parts = line.split(maxsplit=1)
            sub = parts[1].strip().upper() if len(parts) > 1 else ""
            if not sub or sub in ["ALL", "NONE", "CLEAR"]:
                active_disk = None
                print("Target Disk Scope cleared (Scanning All Drives).")
            else:
                if not sub.endswith("\\"):
                    sub = f"{sub}:\\" if not sub.endswith(":") else f"{sub}\\"
                active_disk = sub
                print(f"Target Disk Scope set to: {active_disk}")

        elif cmd_lower.startswith("mode"):
            parts = line.split(maxsplit=1)
            sub = parts[1].strip().lower() if len(parts) > 1 else ""
            if sub in ["hybrid", "live", "indexed"]:
                active_mode = "live_only" if sub == "live" else ("indexed_only" if sub == "indexed" else "hybrid")
                print(f"Active search mode set to: {active_mode}")
            else:
                print("Usage: mode [hybrid | live | indexed]")

        elif cmd_lower.startswith("autochunk"):
            parts = line.split(maxsplit=1)
            sub = parts[1].strip().lower() if len(parts) > 1 else ""
            if sub in ["on", "enable", "allow", "yes", "true"]:
                auto_chunk_enabled = True
            elif sub in ["off", "disable", "deny", "no", "false"]:
                auto_chunk_enabled = False
            else:
                auto_chunk_enabled = not auto_chunk_enabled
            state_str = "ENABLED (Discovered files will be automatically indexed)" if auto_chunk_enabled else "DISABLED (Discovered files remain unchunked on disk)"
            print(f"Auto-Chunk Policy: {state_str}")

        elif cmd_lower.startswith("restore"):
            parts = line.split(maxsplit=1)
            entry_id = parts[1].strip() if len(parts) > 1 else ""
            if not entry_id:
                print("Usage: restore <trash_entry_id>")
            else:
                res = coord.action_engine.undo_trash(entry_id)
                if res.get("success"):
                    print(f"[SUCCESS] Restored {res['file_name']} to {res['path']}")
                else:
                    print(f"[ERROR] Failed to restore entry {entry_id}: {res.get('message')}")
        elif cmd_lower == "dedup":
            print("\nScanning for duplicate clusters...")
            groups = coord.dedup_detector.find_all_duplicates()
            if not groups:
                print("No duplicates detected.")
            else:
                total_reclaimable = sum(g.reclaimable_bytes for g in groups)
                print(f"Found {len(groups)} duplicate clusters (Reclaimable: {format_bytes(total_reclaimable)}):\n")
                all_del_candidates = []
                for idx, g in enumerate(groups, 1):
                    print(f"Group #{idx} [{g.match_type}] ({g.confidence*100:.0f}% confidence):")
                    print(f"  [KEEP]   {g.recommended_keep_path}")
                    for c in g.candidates_to_delete:
                        print(f"  [DELETE] {c}")
                        all_del_candidates.append(c)

                if all_del_candidates:
                    try:
                        ans = input(f"\nQuarantine older copies ({len(all_del_candidates)} files, {format_bytes(total_reclaimable)}) to safe trash? [y/N] (or enter group #): ").strip().lower()
                    except (EOFError, KeyboardInterrupt):
                        ans = "n"

                    target_files = []
                    if ans in ["y", "yes"]:
                        target_files = all_del_candidates
                    elif ans.isdigit() and 1 <= int(ans) <= len(groups):
                        target_files = groups[int(ans) - 1].candidates_to_delete

                    if target_files:
                        prop = coord.action_engine.create_proposal("DEDUPLICATE", f"Quarantine duplicates", target_files)
                        res = coord.action_engine.execute_proposal(prop)
                        print(f"[SUCCESS] Quarantined {res['success_count']} duplicate files to safe trash! Freed {format_bytes(res['freed_bytes'])}")
        elif cmd_lower.startswith("clean"):
            parts = line.split()
            target_gb = float(parts[1]) if len(parts) > 1 and parts[1].replace(".", "").isdigit() else 0.5
            target_bytes = int(target_gb * 1024 * 1024 * 1024)
            print(f"\nAnalyzing cleanup recommendations to free ~{target_gb} GB...")
            candidates = coord.space_reclaimer.recommend_cleanup(target_bytes=target_bytes)
            if not candidates:
                print("No safe cleanup candidates found.")
            else:
                total_size = sum(c.file_size for c in candidates)
                print(f"Found {len(candidates)} candidates freeing {format_bytes(total_size)}:\n")
                for c in candidates:
                    print(f"- {c.file_name} ({format_bytes(c.file_size)}): {c.reason}")

                try:
                    ans = input(f"\nMove these {len(candidates)} candidate files ({format_bytes(total_size)}) to safe trash? [y/N]: ").strip().lower()
                except (EOFError, KeyboardInterrupt):
                    ans = "n"

                if ans in ["y", "yes"]:
                    paths = [c.file_path for c in candidates]
                    prop = coord.action_engine.create_proposal("FREE_SPACE", f"Clean {format_bytes(total_size)}", paths)
                    res = coord.action_engine.execute_proposal(prop)
                    print(f"[SUCCESS] Moved {res['success_count']} files to safe trash! Freed {format_bytes(res['freed_bytes'])}")
        elif cmd_lower.startswith("scan"):
            parts = line.split(maxsplit=1)
            sub = parts[1].strip().strip('"\'') if len(parts) > 1 else ""
            presets = get_standard_user_folders()

            folders_to_scan = []
            if sub.lower() in ["all", "everything"]:
                folders_to_scan = [Path(p) for p in presets.values() if Path(p).exists()]
                print(f"Scanning all user locations: {list(presets.keys())} ...")
            elif sub.lower() in ["downloads", "dl"]:
                p = presets.get("Downloads (C:)")
                if p: folders_to_scan = [Path(p)]
            elif sub.lower() in ["documents", "docs", "doc"]:
                p = presets.get("Documents (C:)")
                if p: folders_to_scan = [Path(p)]
            elif sub.lower() in ["desktop"]:
                p = presets.get("Desktop (C:)")
                if p: folders_to_scan = [Path(p)]
            elif sub:
                target_p = Path(sub).resolve()
                if target_p.exists() and target_p.is_dir():
                    folders_to_scan = [target_p]
                else:
                    print(f"Invalid directory: {sub}")
            else:
                folders_to_scan = [Path(BASE_DIR.parent).resolve()]

            if folders_to_scan:
                total_indexed = 0
                for fld in folders_to_scan:
                    print(f"\n📂 Scanning: {fld} ...")
                    files = find_supported_files(fld, SUPPORTED_EXTENSIONS)
                    for idx, f in enumerate(files, 1):
                        if coord.search_engine.index_file(f):
                            total_indexed += 1
                            if idx % 10 == 0 or idx == len(files):
                                print(f"  Indexed [{idx}/{len(files)}] {f.name}")
                print(f"\nDone! Indexed {total_indexed} files across your drives.")
        elif cmd_lower == "trash":
            items = coord.trash_manager.list_trash()
            print(f"\nQuarantined Trash Items ({len(items)}):")
            for item in items:
                print(f"- ID: {item['id']} | {item['file_name']} ({format_bytes(item['file_size'])}) -> {item['original_path']}")
        else:
            # Query / Search
            intent = coord.llm_agent.parse_intent(line)
            clean_q = intent.get("query", line)
            query_drive = intent.get("target_drive")
            effective_drive = query_drive or active_disk

            scope_info = f"Target Disk: {effective_drive}" if effective_drive else "Target Disk: All Drives"
            print(f"[{scope_info} | Mode: {active_mode} | Auto-Chunk: {'ON' if auto_chunk_enabled else 'OFF'}]")

            results, trace = coord.search_engine.search_with_trace(
                clean_q,
                top_k=5,
                target_drive=effective_drive,
                search_mode=active_mode,
                auto_chunk=auto_chunk_enabled
            )
            last_search_results = results
            live_info = f" | Live Discovery hits={trace.get('live_scan_hits', 0)} ({trace.get('live_scan_time_ms', 0)}ms)" if trace.get("live_scan_hits") else ""
            print(f"\n🧠 Search Trace: BM25 hits={trace['bm25_hits']} ({trace['bm25_time_ms']}ms) | Vector hits={trace['vector_hits']} ({trace['vector_time_ms']}ms){live_info} | Total={trace['total_search_time_ms']}ms")
            if not results:
                print("No matching files found.")
            else:
                print(f"\nTop {len(results)} Matches:")
                for idx, r in enumerate(results, 1):
                    if r.get("is_chunked"):
                        chunk_tag = f" [CHUNKED: {r.get('chunk_count', 0)} chunks]"
                    else:
                        chunk_tag = " [UNCHUNKED (LIVE SCAN)]"
                    print(f"\n#{idx} {r['file_name']}{chunk_tag} (Score: {r['fused_score']:.4f})")
                    print(f"    Path: {r['file_path']}")
                    if r.get("best_snippet"):
                        print(f"    Snippet: {r['best_snippet'][:140]}...")

                print(f"\n[Tip: 'chunk <#>' to index | 'unchunk <#>' to remove | 'open <#>' to launch]")
                print(f"\nSynthesizing AI Summary ({coord.llm_agent.model}):")
                for token in coord.llm_agent.generate_rag_stream(clean_q, results):
                    sys.stdout.write(token)
                    sys.stdout.flush()
                print()

if __name__ == "__main__":
    main()
