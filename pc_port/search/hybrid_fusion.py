import re
from typing import List, Dict, Any, Optional

def reciprocal_rank_fusion(
    bm25_results: List[Dict[str, Any]],
    vector_results: List[Dict[str, Any]],
    k: int = 60,
    top_k: int = 20,
    query: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Fuses lexical BM25 and semantic Vector rankings using Reciprocal Rank Fusion (RRF).
    Aggregates chunk-level matches to file-level relevance rankings.
    """
    chunk_scores: Dict[str, float] = {}
    chunk_data: Dict[str, Dict[str, Any]] = {}

    # Extract non-trivial query tokens for filename matching
    query_tokens = []
    if query:
        clean = re.sub(r'[^\w\s]', ' ', query).lower().strip()
        query_tokens = [t for t in clean.split() if len(t) > 2]

    # Accumulate RRF scores from BM25 (weighted 1.2x for exact keyword relevance)
    for rank, item in enumerate(bm25_results):
        cid = item["chunk_id"]
        chunk_scores[cid] = chunk_scores.get(cid, 0.0) + (1.2 / (k + rank + 1))
        if cid not in chunk_data:
            chunk_data[cid] = item

    # Accumulate RRF scores from Vector Search
    for rank, item in enumerate(vector_results):
        cid = item["chunk_id"]
        chunk_scores[cid] = chunk_scores.get(cid, 0.0) + (1.0 / (k + rank + 1))
        if cid not in chunk_data:
            chunk_data[cid] = item

    # Sort chunks by fused score
    sorted_chunks = sorted(chunk_scores.items(), key=lambda x: x[1], reverse=True)

    # Group into file-level results
    file_map: Dict[str, Dict[str, Any]] = {}
    for cid, fused_score in sorted_chunks:
        data = chunk_data[cid]
        fpath = data["file_path"]

        if fpath not in file_map:
            file_map[fpath] = {
                "file_path": fpath,
                "file_name": data["file_name"],
                "fused_score": fused_score,
                "best_snippet": data.get("snippet", ""),
                "matching_chunks": [data["text"][:300]]
            }
        else:
            # Saturated accumulation with diminishing returns to prevent multi-chunk books
            # from drowning out single-page certificates and ID documents
            file_map[fpath]["fused_score"] += (fused_score * 0.08)
            if len(file_map[fpath]["matching_chunks"]) < 3:
                file_map[fpath]["matching_chunks"].append(data["text"][:300])

    # Apply filename and snippet keyword/identity matching bonuses
    if query:
        from core.identity_expander import expand_identity_query
        expanded_list, _ = expand_identity_query(query)
        all_check_tokens = set(query_tokens) | set(expanded_list)
        for fpath, item in file_map.items():
            fname_lower = item["file_name"].lower()
            snip_lower = item.get("best_snippet", "").lower()

            fn_matches = sum(1 for token in all_check_tokens if token in fname_lower)
            if fn_matches > 0:
                item["fused_score"] += (0.025 * fn_matches)

            snip_matches = sum(1 for token in all_check_tokens if token in snip_lower)
            if snip_matches > 0:
                item["fused_score"] += min(0.045, 0.020 * snip_matches)

    # Rank files by overall fused score
    ranked_files = sorted(file_map.values(), key=lambda x: x["fused_score"], reverse=True)
    return ranked_files[:top_k]
