import argparse
import asyncio
import json
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.actions.pending_store import ActionAuditLogger, PendingActionError, PendingActionStore
from app.config.settings import settings
from app.state.redis_backend import RedisStateBackend


def store(prefix: str, audit_dir: Path) -> tuple[RedisStateBackend, PendingActionStore]:
    settings.STATE_BACKEND = "redis"
    settings.STATE_KEY_PREFIX = prefix
    backend = RedisStateBackend()
    return backend, PendingActionStore(
        execution_lease_seconds=10,
        result_ttl_seconds=60,
        backend=backend,
        audit_logger=ActionAuditLogger(str(audit_dir)),
    )


async def prepare(context_path: Path) -> None:
    prefix = f"aimall:test:stage10:restart:{uuid.uuid4().hex}"
    backend, pending = store(prefix, context_path.parent / "audit-prepare")
    try:
        action = await pending.create(
            action_type="ADD_TO_CART",
            arguments={"productId": 1103, "quantity": 1},
            title="Stage 10 Redis restart",
            summary="Persist across Redis restart",
            session_id="stage10-restart-session",
            auth_token="stage10-restart-token",
            trace_id="trace-stage10-redis-restart",
            tenant_id="stage10",
            user_id=101,
        )
        context_path.write_text(
            json.dumps({"prefix": prefix, "actionId": action["actionId"]}), encoding="utf-8"
        )
    finally:
        await backend.close()


async def wait_ready(timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        backend = RedisStateBackend()
        try:
            await backend.get("stage10:restart:health")
            await backend.close()
            return
        except Exception:
            await backend.close()
            await asyncio.sleep(0.25)
    raise RuntimeError("Redis did not recover before the drill timeout")


async def verify(context_path: Path, result_path: Path) -> None:
    context = json.loads(context_path.read_text(encoding="utf-8"))
    first_backend, first = store(context["prefix"], result_path.parent / "audit-first")
    second_backend, second = store(context["prefix"], result_path.parent / "audit-second")
    execution_count = 0
    execution_lock = asyncio.Lock()

    async def execute(_action):
        nonlocal execution_count
        async with execution_lock:
            execution_count += 1
        await asyncio.sleep(0.2)
        return {"writer": "current"}

    async def confirm(instance):
        try:
            return await instance.confirm(
                context["actionId"],
                session_id="stage10-restart-session",
                auth_token="stage10-restart-token",
                execute=execute,
                tenant_id="stage10",
                user_id=101,
            )
        except PendingActionError as error:
            if error.code != "ACTION_EXECUTING":
                raise
            return {"status": "EXECUTING"}

    try:
        results = await asyncio.gather(confirm(first), confirm(second))
        final = await first.get(
            context["actionId"],
            session_id="stage10-restart-session",
            auth_token="stage10-restart-token",
            tenant_id="stage10",
            user_id=101,
        )
        if execution_count != 1 or final["status"] != "SUCCEEDED" or final["executionCount"] != 1:
            raise AssertionError(
                f"restart fencing failed: callbacks={execution_count}, final={final['status']}, "
                f"executionCount={final['executionCount']}"
            )
        await first.clear_session("stage10-restart-session", "stage10-restart-token")
        result_path.write_text(
            json.dumps(
                {
                    "passed": True,
                    "actionPersistedAcrossRestart": True,
                    "executionCallbacks": execution_count,
                    "finalStatus": final["status"],
                    "executionCount": final["executionCount"],
                    "confirmResults": [item.get("status") for item in results],
                },
                indent=2,
            ),
            encoding="utf-8",
        )
    finally:
        await first_backend.close()
        await second_backend.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("prepare", "wait", "verify"))
    parser.add_argument("--context", type=Path, required=True)
    parser.add_argument("--result", type=Path)
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()
    if args.mode == "prepare":
        asyncio.run(prepare(args.context))
    elif args.mode == "wait":
        asyncio.run(wait_ready(args.timeout))
    else:
        if args.result is None:
            parser.error("--result is required for verify")
        asyncio.run(verify(args.context, args.result))


if __name__ == "__main__":
    main()
