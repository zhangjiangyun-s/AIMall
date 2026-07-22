from fastapi import APIRouter

from app.actions.pending_store import PendingActionError, pending_action_store
from app.schemas.chat_schema import PendingActionRequest
from app.tools.java_client import java_client
from app.runtime import RuntimeCapabilityError, runtime_capabilities


router = APIRouter(prefix="/ai/actions", tags=["Actions"])


@router.post("/{action_id}/confirm")
async def confirm_action(action_id: str, request: PendingActionRequest):
    token = request.authContext.token if request.authContext else None
    try:
        runtime_capabilities.assert_write_allowed()
        action = await pending_action_store.confirm(
            action_id,
            session_id=request.sessionId,
            auth_token=token,
            execute=lambda pending: _execute_action(pending, token or ""),
            tenant_id=request.tenantId,
            user_id=request.userId,
            action_version=request.actionVersion,
        )
    except (PendingActionError, RuntimeCapabilityError) as exc:
        return _error(exc.code, str(exc))
    if action["status"] != "SUCCEEDED":
        code = "ACTION_RETRYABLE" if action.get("retryable") else "ACTION_EXECUTION_FAILED"
        return _error(code, action.get("error") or "确认操作执行失败", action)
    return {"code": 0, "message": "OK", "data": action}


@router.post("/{action_id}/reject")
async def reject_action(action_id: str, request: PendingActionRequest):
    token = request.authContext.token if request.authContext else None
    try:
        action = await pending_action_store.reject(
            action_id,
            session_id=request.sessionId,
            auth_token=token,
            tenant_id=request.tenantId,
            user_id=request.userId,
            action_version=request.actionVersion,
        )
    except PendingActionError as exc:
        return _error(exc.code, str(exc))
    return {"code": 0, "message": "OK", "data": action}


@router.post("/{action_id}/status")
async def get_action_status(action_id: str, request: PendingActionRequest):
    token = request.authContext.token if request.authContext else None
    try:
        action = await pending_action_store.get(
            action_id,
            session_id=request.sessionId,
            auth_token=token,
            tenant_id=request.tenantId,
            user_id=request.userId,
            action_version=request.actionVersion,
        )
    except PendingActionError as exc:
        return _error(exc.code, str(exc))
    return {"code": 0, "message": "OK", "data": action}


def _error(code: str, message: str, data: dict | None = None) -> dict:
    payload = dict(data or {})
    payload.setdefault("success", False)
    payload["errorCode"] = code
    return {"code": 1, "message": message, "data": payload}


async def _execute_action(pending, token: str):
    if runtime_capabilities.values()["writeTools"] == "SANDBOX":
        return {
            "sandbox": True,
            "actionId": pending.action_id,
            "actionType": pending.action_type,
            "arguments": pending.arguments,
        }
    return await java_client.execute_confirmed_action(
        pending.action_id, pending.action_type, pending.arguments, token
    )
