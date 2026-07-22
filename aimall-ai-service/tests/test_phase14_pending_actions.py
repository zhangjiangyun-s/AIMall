import asyncio
from contextlib import asynccontextmanager

import pytest

from app.actions.pending_store import (
    ActionAuditLogger,
    PendingActionError,
    PendingActionStore,
    RetryableActionError,
)
from app.agent.react_agent import ReActAgent
from app.schemas.chat_schema import AuthContext, ChatRequest, PendingActionRequest
from app.tools.executor import ToolExecutor
from app.tools.java_client import JavaClient
from app.tools.registry import get_tool_definition
from app.tools.tool_router import select_candidate_tool_names
from app.api.chat_api import collect_pending_actions, summarize_tool_result
from app.router.intent_router import detect_intent
from app.schemas.tool_schema import ToolCallRecord


def run(coro):
    return asyncio.run(coro)


def make_store(tmp_path, now=None):
    clock = (lambda: now[0]) if now is not None else None
    kwargs = {
        "ttl_seconds": 10,
        "audit_logger": ActionAuditLogger(str(tmp_path / "audit")),
    }
    if clock:
        kwargs["clock"] = clock
    return PendingActionStore(**kwargs)


async def create_action(store, token="token-a", session="session-a", quantity=1):
    return await store.create(
        action_type="ADD_TO_CART",
        arguments={"productId": 1103, "quantity": quantity},
        title="加入购物车",
        summary=f"轻羽 Air 14 x {quantity}",
        session_id=session,
        auth_token=token,
        trace_id="trace-action",
    )


def test_create_never_exposes_or_writes_raw_token(tmp_path):
    store = make_store(tmp_path)
    action = run(create_action(store, token="secret-token-value"))

    assert action["status"] == "PENDING"
    assert "owner_fingerprint" not in action
    assert "secret-token-value" not in str(action)
    audit = next((tmp_path / "audit").glob("*.jsonl")).read_text(encoding="utf-8")
    assert "secret-token-value" not in audit
    assert '"event": "CREATED"' in audit


def test_duplicate_pending_proposal_reuses_action_id(tmp_path):
    store = make_store(tmp_path)
    first = run(create_action(store))
    second = run(create_action(store))

    assert second["actionId"] == first["actionId"]
    assert second["reused"] is True


def test_confirm_executes_once_and_replays_success(tmp_path):
    store = make_store(tmp_path)
    action = run(create_action(store))
    calls = []

    async def execute(pending):
        calls.append(pending.action_id)
        return {"cartItemId": 9001, "quantity": 1}

    first = run(
        store.confirm(
            action["actionId"],
            session_id="session-a",
            auth_token="token-a",
            execute=execute,
        )
    )
    second = run(
        store.confirm(
            action["actionId"],
            session_id="session-a",
            auth_token="token-a",
            execute=execute,
        )
    )

    assert first["status"] == "SUCCEEDED"
    assert second["status"] == "SUCCEEDED"
    assert second["replayed"] is True
    assert second["result"]["cartItemId"] == 9001
    assert calls == [action["actionId"]]


def test_concurrent_confirm_only_starts_one_execution(tmp_path):
    async def scenario():
        store = make_store(tmp_path)
        action = await create_action(store)
        started = asyncio.Event()
        release = asyncio.Event()
        calls = 0

        async def execute(_):
            nonlocal calls
            calls += 1
            started.set()
            await release.wait()
            return {"ok": True}

        first_task = asyncio.create_task(
            store.confirm(
                action["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
            )
        )
        await started.wait()
        with pytest.raises(PendingActionError) as error:
            await store.confirm(
                action["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
            )
        release.set()
        result = await first_task
        return calls, error.value.code, result

    calls, code, result = run(scenario())
    assert calls == 1
    assert code == "ACTION_EXECUTING"
    assert result["status"] == "SUCCEEDED"


def test_action_isolated_by_token_and_session(tmp_path):
    store = make_store(tmp_path)
    action = run(create_action(store))

    async def execute(_):
        return {"ok": True}

    for token, session in (("token-b", "session-a"), ("token-a", "session-b")):
        with pytest.raises(PendingActionError) as error:
            run(
                store.confirm(
                    action["actionId"],
                    session_id=session,
                    auth_token=token,
                    execute=execute,
                )
            )
        assert error.value.code == "ACTION_FORBIDDEN"


def test_expired_or_rejected_action_cannot_execute(tmp_path):
    now = [1000.0]
    expired_store = make_store(tmp_path / "expired", now)
    expired = run(create_action(expired_store))
    now[0] = 1011.0
    calls = []

    async def execute(_):
        calls.append(1)
        return {"ok": True}

    with pytest.raises(PendingActionError) as expired_error:
        run(
            expired_store.confirm(
                expired["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
            )
        )
    assert expired_error.value.code == "ACTION_EXPIRED"

    rejected_store = make_store(tmp_path / "rejected")
    rejected = run(create_action(rejected_store))
    result = run(
        rejected_store.reject(
            rejected["actionId"],
            session_id="session-a",
            auth_token="token-a",
        )
    )
    assert result["status"] == "REJECTED"
    with pytest.raises(PendingActionError):
        run(
            rejected_store.confirm(
                rejected["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
            )
        )
    assert calls == []


def test_identity_fields_are_rejected_from_model_arguments(tmp_path):
    store = make_store(tmp_path)
    with pytest.raises(PendingActionError) as error:
        run(
            store.create(
                action_type="CLAIM_COUPON",
                arguments={"couponId": 1, "memberId": 999},
                title="领取优惠券",
                summary="领取优惠券",
                session_id="session-a",
                auth_token="token-a",
                trace_id="trace-unsafe",
            )
        )
    assert error.value.code == "UNSAFE_ARGUMENT"


def test_all_write_tools_are_high_risk_and_require_confirmation():
    for name in (
        "add_to_cart_confirmed",
        "claim_coupon_confirmed",
        "cancel_order_confirmed",
        "apply_return_confirmed",
    ):
        tool = get_tool_definition(name)
        assert tool is not None
        assert tool.risk == "HIGH"
        assert tool.requiresAuth is True
        assert tool.requiresConfirmation is True


def test_write_tool_creates_pending_action_without_calling_write_api(tmp_path, monkeypatch):
    from app.tools import executor as executor_module

    store = make_store(tmp_path)
    write_calls = []

    async def get_product_detail(product_id):
        return {"productId": product_id, "name": "轻羽 Air 14"}

    async def forbidden_write(*args, **kwargs):
        write_calls.append((args, kwargs))
        raise AssertionError("聊天阶段不允许调用写接口")

    monkeypatch.setattr(executor_module, "pending_action_store", store)
    monkeypatch.setattr(executor_module.java_client, "get_product_detail", get_product_detail)
    monkeypatch.setattr(executor_module.java_client, "add_to_cart", forbidden_write, raising=False)

    record = run(
        ToolExecutor().execute(
            "add_to_cart_confirmed",
            {"productId": 1103, "quantity": 2},
            "trace-pending-tool",
            auth_token="token-a",
            session_id="session-a",
        )
    )

    assert record.ok is True
    assert record.result["pendingAction"]["status"] == "PENDING"
    assert record.result["pendingAction"]["summary"] == "轻羽 Air 14 x 2"
    assert write_calls == []


def test_java_client_routes_confirmed_action_and_injects_action_id(monkeypatch):
    client = JavaClient()
    captured = {}

    async def post_data(path, body, headers=None):
        captured.update({"path": path, "body": body, "headers": headers})
        return {"ok": True}

    monkeypatch.setattr(client, "_post_data", post_data)
    result = run(
        client.execute_confirmed_action(
            "action-123",
            "CANCEL_ORDER",
            {"orderId": 88, "orderSn": "AIM88"},
            "token-a",
        )
    )

    assert result == {"ok": True}
    assert captured["path"] == "/internal/ai/actions/orders/cancel"
    assert captured["body"]["actionId"] == "action-123"
    assert captured["body"]["orderId"] == 88
    assert captured["headers"] == {"token": "token-a"}


def test_action_api_confirms_once_and_replays_without_second_java_call(tmp_path, monkeypatch):
    from app.api import action_api

    store = make_store(tmp_path)
    pending = run(create_action(store))
    calls = []

    async def execute(action_id, action_type, arguments, token):
        calls.append((action_id, action_type, arguments, token))
        return {"cartItemId": 901, "quantity": 1}

    monkeypatch.setattr(action_api, "pending_action_store", store)
    monkeypatch.setattr(action_api.java_client, "execute_confirmed_action", execute)
    request = PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-a"))

    first = run(action_api.confirm_action(pending["actionId"], request))
    second = run(action_api.confirm_action(pending["actionId"], request))

    assert first["code"] == 0
    assert first["data"]["status"] == "SUCCEEDED"
    assert second["code"] == 0
    assert second["data"]["replayed"] is True
    assert len(calls) == 1
    assert calls[0][3] == "token-a"


def test_action_api_rejects_wrong_owner_and_supports_user_rejection(tmp_path, monkeypatch):
    from app.api import action_api

    store = make_store(tmp_path)
    pending = run(create_action(store))
    monkeypatch.setattr(action_api, "pending_action_store", store)

    forbidden = run(
        action_api.confirm_action(
            pending["actionId"],
            PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-b")),
        )
    )
    rejected = run(
        action_api.reject_action(
            pending["actionId"],
            PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-a")),
        )
    )

    assert forbidden["code"] == 1
    assert forbidden["data"]["errorCode"] == "ACTION_FORBIDDEN"
    assert rejected["code"] == 0
    assert rejected["data"]["status"] == "REJECTED"


def test_transport_failure_keeps_action_retryable_with_same_action_id(tmp_path, monkeypatch):
    from app.api import action_api

    store = make_store(tmp_path)
    pending = run(create_action(store))
    attempts = [0]

    async def execute(action_id, action_type, arguments, token):
        attempts[0] += 1
        if attempts[0] == 1:
            raise RetryableActionError("response lost")
        return {"cartItemId": 902, "replayed": True}

    monkeypatch.setattr(action_api, "pending_action_store", store)
    monkeypatch.setattr(action_api.java_client, "execute_confirmed_action", execute)
    request = PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-a"))

    first = run(action_api.confirm_action(pending["actionId"], request))
    second = run(action_api.confirm_action(pending["actionId"], request))

    assert first["code"] == 1
    assert first["data"]["errorCode"] == "ACTION_RETRYABLE"
    assert first["data"]["status"] == "PENDING"
    assert second["code"] == 0
    assert second["data"]["status"] == "SUCCEEDED"
    assert attempts[0] == 2


def test_logout_session_clear_removes_owned_pending_actions(tmp_path):
    store = make_store(tmp_path)
    first = run(create_action(store, token="token-a", session="session-a", quantity=1))
    run(create_action(store, token="token-a", session="session-b", quantity=2))
    run(create_action(store, token="token-b", session="session-a", quantity=3))

    cleared = run(store.clear_session("session-a", "token-a"))

    assert cleared == 1
    with pytest.raises(PendingActionError) as error:
        run(store.get(first["actionId"], session_id="session-a", auth_token="token-a"))
    assert error.value.code == "ACTION_NOT_FOUND"


def test_business_failure_is_terminal_and_does_not_execute_twice(tmp_path):
    store = make_store(tmp_path)
    pending = run(create_action(store))
    calls = []

    async def execute(_):
        calls.append(1)
        raise RuntimeError("库存不足")

    first = run(
        store.confirm(
            pending["actionId"],
            session_id="session-a",
            auth_token="token-a",
            execute=execute,
        )
    )
    assert first["status"] == "FAILED"
    assert first["retryable"] is False
    with pytest.raises(PendingActionError) as error:
        run(
            store.confirm(
                pending["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
            )
        )
    assert error.value.code == "ACTION_FAILED"
    assert calls == [1]


def test_persistent_action_extends_execution_lease_and_retains_terminal_result(tmp_path):
    class FakePersistentBackend:
        enabled = True

        def __init__(self, now):
            self.now = now
            self.json_values = {}
            self.values = {}
            self.sets = {}
            self.last_json_ttl = 0

        @asynccontextmanager
        async def lock(self, _key, ttl_seconds=30):
            yield

        async def get_json(self, key):
            value = self.json_values.get(key)
            if not value or value[1] <= self.now[0]:
                return None
            return dict(value[0])

        async def set_json(self, key, value, ttl_seconds):
            self.last_json_ttl = ttl_seconds
            self.json_values[key] = (dict(value), self.now[0] + ttl_seconds)

        async def set_if_absent(self, key, value, ttl_seconds):
            if key in self.values and self.values[key][1] > self.now[0]:
                return False
            self.values[key] = (value, self.now[0] + ttl_seconds)
            return True

        async def get(self, key):
            value = self.values.get(key)
            return value[0] if value and value[1] > self.now[0] else None

        async def delete(self, *keys):
            for key in keys:
                self.json_values.pop(key, None)
                self.values.pop(key, None)
                self.sets.pop(key, None)
            return len(keys)

        async def add_to_set(self, key, value, ttl_seconds):
            members, _ = self.sets.get(key, (set(), 0))
            members.add(value)
            self.sets[key] = (members, self.now[0] + ttl_seconds)

        async def set_members(self, key):
            value = self.sets.get(key)
            return set(value[0]) if value and value[1] > self.now[0] else set()

    now = [100.0]
    backend = FakePersistentBackend(now)
    store = PendingActionStore(
        ttl_seconds=10,
        execution_lease_seconds=30,
        result_ttl_seconds=60,
        clock=lambda: now[0],
        audit_logger=ActionAuditLogger(str(tmp_path / "audit")),
        backend=backend,
    )
    pending = run(create_action(store))

    async def execute(_):
        now[0] += 20
        return {"ok": True}

    result = run(store.confirm(
        pending["actionId"], session_id="session-a", auth_token="token-a", execute=execute
    ))
    assert result["status"] == "SUCCEEDED"
    assert backend.last_json_ttl == 60
    now[0] += 59
    retained = run(store.get(pending["actionId"], session_id="session-a", auth_token="token-a"))
    assert retained["result"] == {"ok": True}
    now[0] += 2
    with pytest.raises(PendingActionError) as error:
        run(store.get(pending["actionId"], session_id="session-a", auth_token="token-a"))
    assert error.value.code == "ACTION_NOT_FOUND"


def test_persistent_action_uses_lease_and_fencing_token(tmp_path):
    class Backend:
        enabled = True

        def __init__(self):
            self.json_values = {}
            self.values = {}
            self.sets = {}

        @asynccontextmanager
        async def lock(self, _key, ttl_seconds=30):
            yield

        async def get_json(self, key):
            value = self.json_values.get(key)
            return dict(value) if value else None

        async def set_json(self, key, value, _ttl_seconds):
            self.json_values[key] = dict(value)

        async def set_if_absent(self, key, value, _ttl_seconds):
            if key in self.values:
                return False
            self.values[key] = value
            return True

        async def get(self, key):
            return self.values.get(key)

        async def delete(self, *keys):
            for key in keys:
                self.json_values.pop(key, None)
                self.values.pop(key, None)
                self.sets.pop(key, None)
            return len(keys)

        async def add_to_set(self, key, value, _ttl_seconds):
            self.sets.setdefault(key, set()).add(value)

        async def set_members(self, key):
            return set(self.sets.get(key, set()))

    async def scenario():
        now = [100.0]
        backend = Backend()
        store = PendingActionStore(
            ttl_seconds=30,
            execution_lease_seconds=10,
            result_ttl_seconds=60,
            clock=lambda: now[0],
            audit_logger=ActionAuditLogger(str(tmp_path / "audit-fencing")),
            backend=backend,
        )
        pending = await create_action(store)
        first_started = asyncio.Event()
        release_first = asyncio.Event()

        async def first_execute(_action):
            first_started.set()
            await release_first.wait()
            return {"winner": "first"}

        first_task = asyncio.create_task(store.confirm(
            pending["actionId"], session_id="session-a", auth_token="token-a", execute=first_execute
        ))
        await first_started.wait()
        now[0] += 9
        with pytest.raises(PendingActionError) as executing_error:
            await store.confirm(
                pending["actionId"], session_id="session-a", auth_token="token-a",
                execute=lambda _action: None,
            )
        assert executing_error.value.code == "ACTION_EXECUTING"

        now[0] += 2

        async def second_execute(_action):
            return {"winner": "second"}

        second_result = await store.confirm(
            pending["actionId"], session_id="session-a", auth_token="token-a", execute=second_execute
        )
        release_first.set()
        first_result = await first_task
        retained = await store.get(
            pending["actionId"], session_id="session-a", auth_token="token-a"
        )
        assert second_result["result"] == {"winner": "second"}
        assert first_result["result"] == {"winner": "second"}
        assert retained["result"] == {"winner": "second"}
        assert retained["executionCount"] == 2

        pending_cleared = await create_action(store)
        cleared_started = asyncio.Event()
        release_cleared = asyncio.Event()

        async def cleared_execute(_action):
            cleared_started.set()
            await release_cleared.wait()
            return {"mustNotPersist": True}

        cleared_task = asyncio.create_task(store.confirm(
            pending_cleared["actionId"], session_id="session-a", auth_token="token-a", execute=cleared_execute
        ))
        await cleared_started.wait()
        await store.clear_session("session-a", "token-a")
        release_cleared.set()
        cleared_result = await cleared_task
        assert cleared_result["status"] == "CLEARED"
        assert "execution_token" not in cleared_result
        with pytest.raises(PendingActionError) as cleared_error:
            await store.get(
                pending_cleared["actionId"], session_id="session-a", auth_token="token-a"
            )
        assert cleared_error.value.code == "ACTION_NOT_FOUND"

    run(scenario())


def test_pending_action_requires_login_and_public_payload_is_minimal(tmp_path):
    store = make_store(tmp_path)
    with pytest.raises(PendingActionError) as error:
        run(create_action(store, token=None))
    assert error.value.code == "AUTH_REQUIRED"

    pending = run(create_action(store))
    assert "arguments" not in pending
    assert "sessionId" not in pending
    assert "owner_fingerprint" not in pending


def test_pending_action_rejects_tenant_user_version_and_permission_snapshot_changes(tmp_path):
    store = make_store(tmp_path)
    pending = run(
        store.create(
            action_type="ADD_TO_CART",
            arguments={"productId": 1103, "quantity": 1},
            title="Add",
            summary="Add product",
            session_id="session-a",
            auth_token="token-a",
            trace_id="trace-context",
            tenant_id="tenant-a",
            user_id=101,
        )
    )

    async def execute(_action):
        return {"ok": True}

    for context in (
        {"tenant_id": "tenant-b", "user_id": 101, "action_version": 1},
        {"tenant_id": "tenant-a", "user_id": 202, "action_version": 1},
        {"tenant_id": "tenant-a", "user_id": 101, "action_version": 2},
    ):
        with pytest.raises(PendingActionError):
            run(
                store.confirm(
                    pending["actionId"],
                    session_id="session-a",
                    auth_token="token-a",
                    execute=execute,
                    **context,
                )
            )

    store._actions[pending["actionId"]].permission_snapshot = "tampered"
    with pytest.raises(PendingActionError) as error:
        run(
            store.confirm(
                pending["actionId"],
                session_id="session-a",
                auth_token="token-a",
                execute=execute,
                tenant_id="tenant-a",
                user_id=101,
            )
        )
    assert error.value.code == "ACTION_PERMISSION_CHANGED"


def test_chat_sse_collects_pending_action_and_labels_it_unexecuted():
    action = {
        "actionId": "action-sse",
        "actionType": "ADD_TO_CART",
        "title": "加入购物车",
        "summary": "轻羽 Air 14 x 1",
        "status": "PENDING",
        "expiresAt": 1000,
    }
    record = ToolCallRecord(
        name="add_to_cart_confirmed",
        arguments={"productId": 1103, "quantity": 1},
        ok=True,
        result={"pendingAction": action},
        traceId="trace-sse",
    )

    exposed = collect_pending_actions([record])
    assert exposed[0]["actionId"] == "action-sse"
    assert exposed[0]["summary"] == "轻羽 Air 14 x 1"
    assert "arguments" not in exposed[0]
    assert "sessionId" not in exposed[0]
    summary = summarize_tool_result(record)
    assert "待确认" in summary
    assert "尚未执行" in summary


def test_react_creates_cart_confirmation_draft_on_product_page():
    request = ChatRequest(
        message="把这款加入购物车 2 件",
        sessionId="session-write",
        pageContext={"pageType": "PRODUCT_DETAIL", "productId": 1103},
    )

    action = ReActAgent()._scripted_next_action(request, "PRODUCT_QA", [], {"entities": []})

    assert action["action"] == "add_to_cart_confirmed"
    assert action["arguments"] == {"productId": 1103, "quantity": 2}


def test_react_creates_order_and_return_drafts_with_required_context():
    agent = ReActAgent()
    cancel_request = ChatRequest(
        message="取消订单 AIM20260714000000000002",
        sessionId="session-write",
        pageContext={"pageType": "GENERAL"},
    )
    return_request = ChatRequest(
        message="订单号 AIM20260714000000000003 申请售后，原因是屏幕有坏点",
        sessionId="session-write",
        pageContext={"pageType": "GENERAL"},
    )

    cancel = agent._scripted_next_action(cancel_request, "ORDER_QA", [], {"entities": []})
    apply_return = agent._scripted_next_action(return_request, "RETURN_QA", [], {"entities": []})

    assert cancel["action"] == "cancel_order_confirmed"
    assert cancel["arguments"]["orderSn"] == "AIM20260714000000000002"
    assert apply_return["action"] == "apply_return_confirmed"
    assert apply_return["arguments"]["reason"] == "屏幕有坏点"


def test_react_does_not_create_return_draft_without_reason():
    request = ChatRequest(
        message="订单号 AIM20260714000000000003 申请售后",
        sessionId="session-write",
        pageContext={"pageType": "GENERAL"},
    )

    action = ReActAgent()._scripted_write_action(request, None)

    assert action is None


def test_action_status_api_recovers_server_state_and_enforces_owner(tmp_path, monkeypatch):
    from app.api import action_api

    store = make_store(tmp_path)
    pending = run(create_action(store))
    monkeypatch.setattr(action_api, "pending_action_store", store)

    owned = run(
        action_api.get_action_status(
            pending["actionId"],
            PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-a")),
        )
    )
    forbidden = run(
        action_api.get_action_status(
            pending["actionId"],
            PendingActionRequest(sessionId="session-a", authContext=AuthContext(token="token-b")),
        )
    )

    assert owned["code"] == 0
    assert owned["data"]["status"] == "PENDING"
    assert forbidden["code"] == 1
    assert forbidden["data"]["errorCode"] == "ACTION_FORBIDDEN"


def test_claim_coupon_phrase_routes_to_coupon_tools():
    assert detect_intent("领取优惠券 1", "GENERAL") == "COUPON_QA"


def test_cancel_order_with_order_sn_routes_to_order_hitl_tools():
    message = "取消订单 AIM20260714000000000001"

    assert detect_intent(message, "GENERAL") == "ORDER_QA"
    assert "cancel_order_confirmed" in select_candidate_tool_names("ORDER_QA", None)


def test_cancel_order_policy_question_stays_in_policy_domain():
    assert detect_intent("取消订单需要满足什么条件", "GENERAL") == "POLICY_QA"
