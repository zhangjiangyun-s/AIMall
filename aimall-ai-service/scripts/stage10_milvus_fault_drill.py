import argparse
import json
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pymilvus import MilvusClient

from app.config.settings import settings
from app.rag.milvus_store import MilvusVectorStore


DOC_ID = 910001
VERSION_ID = 920001
STALE_TOKEN = "stage10-stale-attempt"
CURRENT_TOKEN = "stage10-current-attempt"


def make_store(collection: str) -> MilvusVectorStore:
    result = MilvusVectorStore()
    result.collection = collection
    result.dim = 4
    return result


def chunk(index: int, token: str, status: str = "READY") -> dict:
    return {
        "id": index,
        "docId": DOC_ID,
        "docVersionId": VERSION_ID,
        "chunkKey": f"stage10-{index}",
        "contentHash": f"hash-{index}",
        "executionToken": token,
        "attemptNo": 1 if token == STALE_TOKEN else 2,
        "tenantId": "stage10",
        "visibilityScope": "PUBLIC_USER",
        "roleScope": "PUBLIC",
        "status": status,
    }


def prepare(context: Path) -> None:
    collection = f"aimall_stage10_fault_{uuid.uuid4().hex}"
    store = make_store(collection)
    stale = [(chunk(index, STALE_TOKEN), [1.0, 0.0, 0.0, 0.0]) for index in range(1, 251)]
    current = [(chunk(index, CURRENT_TOKEN), [0.9, 0.1, 0.0, 0.0]) for index in range(251, 271)]
    try:
        for existing in store.client.list_collections():
            if existing.startswith("aimall_stage10_fault_"):
                store.client.drop_collection(existing)
        stale_ids = store.upsert_chunks(stale)
        current_ids = store.upsert_chunks(current)
        store.flush()
        first_updated = store.update_doc_version_status_for_execution(
            DOC_ID, VERSION_ID, STALE_TOKEN, "ACTIVE", False
        )
        partial = store.get_doc_version_status(DOC_ID, VERSION_ID)
        if first_updated != 250 or partial["allActive"] or not partial["anyActive"]:
            raise AssertionError(f"partial activation was not detected: {partial}")
        second_updated = store.update_doc_version_status_for_execution(
            DOC_ID, VERSION_ID, CURRENT_TOKEN, "ACTIVE", False
        )
        complete = store.get_doc_version_status(DOC_ID, VERSION_ID)
        if second_updated != 20 or not complete["allActive"] or complete["vectorCount"] != 270:
            raise AssertionError(f"complete activation mismatch: {complete}")
        context.write_text(
            json.dumps(
                {
                    "collection": collection,
                    "staleIds": stale_ids,
                    "currentIds": current_ids,
                    "partialActivationDetected": True,
                    "vectorCount": complete["vectorCount"],
                }
            ),
            encoding="utf-8",
        )
    except Exception:
        if store.client.has_collection(collection):
            store.client.drop_collection(collection)
        raise


def expect_outage(context: Path) -> None:
    collection = json.loads(context.read_text(encoding="utf-8"))["collection"]
    try:
        client = MilvusClient(uri=settings.MILVUS_URI, timeout=2)
        client.has_collection(collection, timeout=2)
    except Exception:
        return
    raise AssertionError("Milvus request unexpectedly succeeded while the container was paused")


def verify(context: Path, result: Path) -> None:
    data = json.loads(context.read_text(encoding="utf-8"))
    store = make_store(data["collection"])
    try:
        before = store.get_doc_version_status(DOC_ID, VERSION_ID)
        stale_ids = store.get_doc_version_embeddings_for_execution(DOC_ID, VERSION_ID, STALE_TOKEN)
        if len(stale_ids) != 250:
            raise AssertionError(f"expected 250 stale vectors after recovery, got {len(stale_ids)}")
        deleted = store.delete_embeddings_for_execution(stale_ids, STALE_TOKEN)
        remaining_stale = store.get_doc_version_embeddings_for_execution(DOC_ID, VERSION_ID, STALE_TOKEN)
        remaining_current = store.get_doc_version_embeddings_for_execution(DOC_ID, VERSION_ID, CURRENT_TOKEN)
        if remaining_stale or len(remaining_current) != 20:
            raise AssertionError(
                f"delete fencing mismatch: stale={len(remaining_stale)}, current={len(remaining_current)}"
            )
        result.write_text(
            json.dumps(
                {
                    "passed": True,
                    "outageDetected": True,
                    "recovered": True,
                    "partialActivationDetected": data["partialActivationDetected"],
                    "vectorsBeforeOutage": before["vectorCount"],
                    "deleteBacklog": len(stale_ids),
                    "deletedStaleVectors": deleted,
                    "currentVectorsPreserved": len(remaining_current),
                },
                indent=2,
            ),
            encoding="utf-8",
        )
    finally:
        if store.client.has_collection(data["collection"]):
            store.client.drop_collection(data["collection"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("prepare", "outage", "verify"))
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--result", type=Path)
    args = parser.parse_args()
    if args.mode == "prepare":
        prepare(args.context)
    elif args.mode == "outage":
        expect_outage(args.context)
    else:
        if args.result is None:
            parser.error("--result is required for verify")
        verify(args.context, args.result)


if __name__ == "__main__":
    main()
