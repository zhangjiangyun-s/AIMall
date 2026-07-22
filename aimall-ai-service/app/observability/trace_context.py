from __future__ import annotations

import re
import uuid
from contextvars import ContextVar


_TRACE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_trace_id: ContextVar[str] = ContextVar("aimall_trace_id", default="")
_client_trace_id: ContextVar[str] = ContextVar("aimall_client_trace_id", default="")


def new_trace_id() -> str:
    return str(uuid.uuid4())


def resolve_client_trace_id(candidate: str | None) -> str:
    return candidate if candidate and _TRACE_PATTERN.fullmatch(candidate) else ""


def set_trace_id(trace_id: str):
    return _trace_id.set(trace_id)


def reset_trace_id(token) -> None:
    _trace_id.reset(token)


def current_trace_id() -> str:
    return _trace_id.get()


def set_client_trace_id(trace_id: str):
    return _client_trace_id.set(trace_id)


def reset_client_trace_id(token) -> None:
    _client_trace_id.reset(token)


def current_client_trace_id() -> str:
    return _client_trace_id.get()
