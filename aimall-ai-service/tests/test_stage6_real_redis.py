import asyncio
import os
import time
import uuid

import pytest

from app.actions.pending_store import ActionAuditLogger, PendingActionError, PendingActionStore
from app.config.settings import settings
from app.state.redis_backend import RedisStateBackend


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_REAL_REDIS_TESTS") != "1",
    reason="set RUN_REAL_REDIS_TESTS=1 to run Redis multi-instance acceptance",
)


def test_real_redis_multi_instance_lease_takeover_is_fenced(tmp_path, monkeypatch):
    async def scenario():
        prefix = f"aimall:test:stage6:{uuid.uuid4().hex}"
        monkeypatch.setattr(settings, "STATE_BACKEND", "redis")
        monkeypatch.setattr(settings, "STATE_KEY_PREFIX", prefix)
        backend = RedisStateBackend()
        first = PendingActionStore(
            execution_lease_seconds=1,
            result_ttl_seconds=30,
            backend=backend,
            audit_logger=ActionAuditLogger(str(tmp_path / "first")),
        )
        second = PendingActionStore(
            execution_lease_seconds=1,
            result_ttl_seconds=30,
            backend=backend,
            audit_logger=ActionAuditLogger(str(tmp_path / "second")),
        )
        created = await first.create(
            action_type="ADD_TO_CART",
            arguments={"productId": 1103, "quantity": 1},
            title="Add",
            summary="Add product",
            session_id="session-a",
            auth_token="token-a",
            trace_id="trace-stage6-redis",
            tenant_id="tenant-a",
            user_id=101,
        )
        started = asyncio.Event()
        release = asyncio.Event()

        async def slow_execution(_action):
            started.set()
            await release.wait()
            return {"writer": "stale"}

        async def current_execution(_action):
            return {"writer": "current"}

        stale_task = asyncio.create_task(first.confirm(
            created["actionId"],
            session_id="session-a",
            auth_token="token-a",
            execute=slow_execution,
            tenant_id="tenant-a",
            user_id=101,
        ))
        await started.wait()
        with pytest.raises(PendingActionError) as executing:
            await second.confirm(
                created["actionId"], session_id="session-a", auth_token="token-a",
                execute=current_execution, tenant_id="tenant-a", user_id=101,
            )
        assert executing.value.code == "ACTION_EXECUTING"

        await asyncio.sleep(1.1)
        current = await second.confirm(
            created["actionId"], session_id="session-a", auth_token="token-a",
            execute=current_execution, tenant_id="tenant-a", user_id=101,
        )
        release.set()
        stale = await stale_task
        final = await first.get(
            created["actionId"], session_id="session-a", auth_token="token-a",
            tenant_id="tenant-a", user_id=101,
        )

        assert current["result"] == {"writer": "current"}
        assert stale["result"] == {"writer": "current"}
        assert final["status"] == "SUCCEEDED"
        assert final["executionCount"] == 2
        await first.clear_session("session-a", "token-a")
        await backend.close()

    asyncio.run(scenario())
