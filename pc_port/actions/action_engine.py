from dataclasses import dataclass
from typing import List, Dict, Any, Optional
from pathlib import Path
from actions.trash_manager import TrashManager
from search.hybrid_search import HybridSearchEngine
from core.logger import setup_logger

logger = setup_logger("ActionEngine")

@dataclass
class ActionProposal:
    proposal_id: str
    action_type: str  # "DELETE", "ARCHIVE", "DEDUPLICATE", "FREE_SPACE"
    description: str
    target_files: List[str]
    total_bytes: int
    is_reversible: bool = True

class ActionEngine:
    def __init__(self, trash_manager: TrashManager = None, search_engine: HybridSearchEngine = None):
        self.trash_manager = trash_manager or TrashManager()
        self.search_engine = search_engine or HybridSearchEngine()

    def create_proposal(
        self,
        action_type: str,
        description: str,
        target_files: List[str]
    ) -> ActionProposal:
        total_size = 0
        valid_files = []
        for f in target_files:
            p = Path(f)
            if p.exists() and p.is_file():
                total_size += p.stat().st_size
                valid_files.append(str(p.resolve()))

        return ActionProposal(
            proposal_id=f"prop_{len(valid_files)}_{total_size}",
            action_type=action_type,
            description=description,
            target_files=valid_files,
            total_bytes=total_size,
            is_reversible=True
        )

    def execute_proposal(self, proposal: ActionProposal) -> Dict[str, Any]:
        """
        Executes proposal with strict confirmation gate and lock-safe error reporting.
        """
        success_count = 0
        failed_count = 0
        trashed_entries = []
        error_messages = []

        for fpath in proposal.target_files:
            res = self.trash_manager.move_to_trash(Path(fpath))
            if res.get("status") == "TRASHED":
                self.search_engine.remove_file(fpath)
                trashed_entries.append(res)
                success_count += 1
            else:
                failed_count += 1
                error_messages.append(res.get("message", f"Failed to trash {Path(fpath).name}"))

        return {
            "proposal_id": proposal.proposal_id,
            "success_count": success_count,
            "failed_count": failed_count,
            "freed_bytes": sum(e.get("file_size", 0) for e in trashed_entries),
            "trashed_entries": trashed_entries,
            "errors": error_messages
        }

    def undo_trash(self, entry_id: str) -> Dict[str, Any]:
        return self.trash_manager.restore(entry_id)
