from __future__ import annotations

import asyncio
import hashlib
import json
import time
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Protocol

import httpx

from app.evaluation.loader import EvaluationDataset
from app.evaluation.models import EvaluationCase
from app.evaluation.fault_harness import isolated_fault_injection_harness
from app.guardrails import guardrail_service


TERMINAL_RESUME_STATUSES = {"SUCCESS", "UNSUPPORTED"}


@dataclass(frozen=True)
class EvaluationRunnerConfig:
    base_url: str = "http://127.0.0.1:8080"
    password: str = ""
    timeout_seconds: float = 240
    concurrency: int = 2
    output_path: Path = Path(".acceptance/evaluation-latest.json")
    resume: bool = False
    enable_fault_harness: bool = True

    def __post_init__(self) -> None:
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if self.concurrency < 1 or self.concurrency > 20:
            raise ValueError("concurrency must be between 1 and 20")


@dataclass
class TransportResponse:
    status_code: int
    raw_text: str
    events: list[dict[str, Any]]


class EvaluationTransport(Protocol):
    async def login(self, username: str, password: str) -> str: ...

    async def clear_session(self, session_id: str, token: str | None) -> None: ...

    async def chat(
        self,
        *,
        message: str,
        session_id: str,
        trace_id: str,
        page_context: dict[str, Any],
        token: str | None,
    ) -> TransportResponse: ...

    async def close(self) -> None: ...


class HttpEvaluationTransport:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        timeout = httpx.Timeout(timeout_seconds)
        self.base_url = base_url.rstrip("/")
        self.client = httpx.AsyncClient(timeout=timeout)

    async def login(self, username: str, password: str) -> str:
        response = await self.client.post(
            f"{self.base_url}/api/user/login",
            json={"username": username, "password": password},
        )
        response.raise_for_status()
        payload = response.json()
        token = str((payload.get("data") or {}).get("token") or "")
        if payload.get("code") != 0 or not token:
            raise RuntimeError(f"login failed for evaluation fixture {username}")
        return token

    async def clear_session(self, session_id: str, token: str | None) -> None:
        headers = {"token": token} if token else {}
        response = await self.client.post(
            f"{self.base_url}/api/ai/session/clear",
            json={"sessionId": session_id},
            headers=headers,
        )
        response.raise_for_status()

    async def chat(
        self,
        *,
        message: str,
        session_id: str,
        trace_id: str,
        page_context: dict[str, Any],
        token: str | None,
    ) -> TransportResponse:
        headers = {"token": token} if token else {}
        async with self.client.stream(
            "POST",
            f"{self.base_url}/api/ai/chat",
            json={
                "message": message,
                "sessionId": session_id,
                "traceId": trace_id,
                "pageContext": page_context,
            },
            headers=headers,
        ) as response:
            chunks = [chunk async for chunk in response.aiter_bytes()]
            raw = b"".join(chunks).decode("utf-8")
            return TransportResponse(
                status_code=response.status_code,
                raw_text=raw,
                events=parse_sse(raw),
            )

    async def close(self) -> None:
        await self.client.aclose()


class EvaluationRunner:
    def __init__(
        self,
        config: EvaluationRunnerConfig,
        transport: EvaluationTransport | None = None,
    ) -> None:
        self.config = config
        self.transport = transport or HttpEvaluationTransport(config.base_url, config.timeout_seconds)
        self.run_id = f"EVAL_{datetime.now():%Y%m%d_%H%M%S}_{uuid.uuid4().hex[:8].upper()}"
        self._token_cache: dict[str, str] = {}
        self._token_lock = asyncio.Lock()
        self._write_lock = asyncio.Lock()
        self._results: dict[str, dict[str, Any]] = {}
        self._started_at = datetime.now().astimezone().isoformat(timespec="seconds")
        self._dataset: EvaluationDataset | None = None

    async def run(
        self,
        dataset: EvaluationDataset,
        *,
        case_ids: set[str] | None = None,
        categories: set[str] | None = None,
        limit: int | None = None,
    ) -> dict[str, Any]:
        self._dataset = dataset
        try:
            self._load_resume(dataset)
        except Exception:
            await self.transport.close()
            raise
        selected = [case for case in dataset.cases if case.enabled]
        if case_ids:
            unknown_case_ids = case_ids - {case.id for case in dataset.cases}
            if unknown_case_ids:
                await self.transport.close()
                raise ValueError(f"unknown evaluation case ids: {sorted(unknown_case_ids)}")
            selected = [case for case in selected if case.id in case_ids]
        if categories:
            unknown_categories = categories - {case.category.value for case in dataset.cases}
            if unknown_categories:
                await self.transport.close()
                raise ValueError(f"unknown evaluation categories: {sorted(unknown_categories)}")
            selected = [case for case in selected if case.category.value in categories]
        if limit is not None:
            if limit < 0:
                await self.transport.close()
                raise ValueError("evaluation limit cannot be negative")
            selected = selected[:limit]

        pending = [
            case
            for case in selected
            if (self._results.get(case.id) or {}).get("status") not in TERMINAL_RESUME_STATUSES
        ]
        semaphore = asyncio.Semaphore(self.config.concurrency)

        async def execute(case: EvaluationCase) -> None:
            async with semaphore:
                result = await self._execute_case(case)
                async with self._write_lock:
                    self._results[case.id] = result
                    self._write_report(selected)

        try:
            await asyncio.gather(*(execute(case) for case in pending))
        finally:
            await self.transport.close()
        return self._write_report(selected)

    async def _execute_case(self, case: EvaluationCase) -> dict[str, Any]:
        started_at = datetime.now().astimezone().isoformat(timespec="milliseconds")
        started = time.perf_counter()
        session_id = f"eval-{self.run_id.lower()}-{case.id.lower()}"
        trace_id = f"{self.run_id}-{case.id}"
        base = {
            "caseId": case.id,
            "category": case.category.value,
            "riskLevel": case.riskLevel.value,
            "sessionId": session_id,
            "traceId": trace_id,
            "startedAt": started_at,
        }
        if case.fixture.faultInjections:
            if self.config.enable_fault_harness:
                return await isolated_fault_injection_harness.execute(case, self.run_id)
            return {
                **base,
                "status": "UNSUPPORTED",
                "durationMs": 0,
                "errorType": "FAULT_INJECTION_NOT_CONFIGURED",
                "errorMessage": "Fault injection is reserved for Stage 17.5 isolated fixtures.",
                "historyRuns": [],
                "events": [],
            }

        token: str | None = None
        session_initialized = False
        try:
            token = await self._token_for(case)
            if token:
                await asyncio.wait_for(
                    self.transport.clear_session(session_id, token),
                    timeout=self.config.timeout_seconds,
                )
                session_initialized = True
            history_runs = []
            for index, turn in enumerate(case.fixture.history):
                if turn.role != "user":
                    continue
                history_trace = f"{trace_id}-H{index + 1}"
                history_response = await asyncio.wait_for(
                    self.transport.chat(
                        message=turn.content,
                        session_id=session_id,
                        trace_id=history_trace,
                        page_context=case.fixture.pageContext,
                        token=token,
                    ),
                    timeout=self.config.timeout_seconds,
                )
                history_done = find_done(history_response.events)
                if history_response.status_code >= 400 or not history_done:
                    raise RuntimeError(f"history replay failed at turn {index + 1}")
                history_runs.append(
                    {
                        "sourceTurn": index + 1,
                        "traceId": history_done.get("traceId") or history_trace,
                        "intent": history_done.get("intent"),
                        "memoryEntities": history_done.get("memoryEntities") or [],
                        "toolCalls": history_done.get("toolCalls") or [],
                    }
                )

            response = await asyncio.wait_for(
                self.transport.chat(
                    message=case.query,
                    session_id=session_id,
                    trace_id=trace_id,
                    page_context=case.fixture.pageContext,
                    token=token,
                ),
                timeout=self.config.timeout_seconds,
            )
            done = find_done(response.events)
            status = "SUCCESS" if response.status_code < 400 and done else "ERROR"
            sanitized_events = guardrail_service.sanitize_payload(response.events)
            answer = "".join(
                str(event.get("content") or "")
                for event in sanitized_events
                if event.get("type") == "delta"
            )
            return {
                **base,
                "status": status,
                "finishedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
                "durationMs": round((time.perf_counter() - started) * 1000),
                "httpStatus": response.status_code,
                "rawBytes": len(response.raw_text.encode("utf-8")),
                "rawSha256": hashlib.sha256(response.raw_text.encode("utf-8")).hexdigest(),
                "historyRuns": history_runs,
                "answer": answer,
                "done": find_done(sanitized_events),
                "events": sanitized_events,
                "errorType": None if status == "SUCCESS" else "MISSING_DONE_EVENT",
                "errorMessage": None if status == "SUCCESS" else "SSE stream did not finish with a done event.",
            }
        except asyncio.TimeoutError:
            return self._error_result(base, started, "TIMEOUT", "CASE_TIMEOUT", "Evaluation case timed out.")
        except Exception as exc:
            return self._error_result(base, started, "ERROR", type(exc).__name__, str(exc))
        finally:
            if session_initialized:
                try:
                    await asyncio.wait_for(
                        self.transport.clear_session(session_id, token),
                        timeout=min(self.config.timeout_seconds, 10),
                    )
                except Exception:
                    pass

    async def _token_for(self, case: EvaluationCase) -> str | None:
        if not case.fixture.authRequired:
            return None
        username = str(case.fixture.userFixture)
        if username in self._token_cache:
            return self._token_cache[username]
        async with self._token_lock:
            if username not in self._token_cache:
                self._token_cache[username] = await asyncio.wait_for(
                    self.transport.login(username, self.config.password),
                    timeout=self.config.timeout_seconds,
                )
        return self._token_cache[username]

    def _error_result(
        self,
        base: dict[str, Any],
        started: float,
        status: str,
        error_type: str,
        message: str,
    ) -> dict[str, Any]:
        return {
            **base,
            "status": status,
            "finishedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "durationMs": round((time.perf_counter() - started) * 1000),
            "errorType": error_type,
            "errorMessage": message[:1000],
            "historyRuns": [],
            "events": [],
        }

    def _load_resume(self, dataset: EvaluationDataset) -> None:
        if not self.config.resume or not self.config.output_path.exists():
            return
        payload = json.loads(self.config.output_path.read_text(encoding="utf-8"))
        if payload.get("datasetId") != dataset.manifest.datasetId:
            raise ValueError("resume report datasetId does not match")
        if payload.get("datasetVersion") != dataset.manifest.version:
            raise ValueError("resume report datasetVersion does not match")
        self.run_id = str(payload.get("runId") or self.run_id)
        self._started_at = str(payload.get("startedAt") or self._started_at)
        self._results = {
            str(item["caseId"]): item
            for item in payload.get("results") or []
            if isinstance(item, dict) and item.get("caseId")
        }

    def _write_report(self, selected: list[EvaluationCase]) -> dict[str, Any]:
        selected_ids = {case.id for case in selected}
        results = [self._results[case.id] for case in selected if case.id in self._results]
        counts = {
            status: sum(1 for item in results if item.get("status") == status)
            for status in ("SUCCESS", "ERROR", "TIMEOUT", "UNSUPPORTED")
        }
        report = {
            "schemaVersion": "AIMALL_EVAL_RUN_V1",
            "runId": self.run_id,
            "datasetId": self._dataset.manifest.datasetId if self._dataset else None,
            "datasetVersion": self._dataset.manifest.version if self._dataset else None,
            "startedAt": self._started_at,
            "updatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
            "baseUrl": self.config.base_url,
            "timeoutSeconds": self.config.timeout_seconds,
            "concurrency": self.config.concurrency,
            "faultHarnessEnabled": self.config.enable_fault_harness,
            "selectedCaseCount": len(selected_ids),
            "completedCaseCount": len(results),
            "counts": counts,
            "results": results,
        }
        path = self.config.output_path
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(f"{path.name}.{uuid.uuid4().hex}.tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        try:
            for attempt in range(20):
                try:
                    temporary.replace(path)
                    break
                except PermissionError:
                    if attempt == 19:
                        raise
                    time.sleep(0.05 * (attempt + 1))
        finally:
            temporary.unlink(missing_ok=True)
        return report


def parse_sse(raw: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    data_lines: list[str] = []
    for line in raw.replace("\r\n", "\n").split("\n"):
        if line == "":
            if data_lines:
                events.append(json.loads("\n".join(data_lines)))
                data_lines = []
            continue
        if line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    if data_lines:
        events.append(json.loads("\n".join(data_lines)))
    return events


def find_done(events: list[dict[str, Any]]) -> dict[str, Any]:
    return next((event for event in reversed(events) if event.get("type") == "done"), {})
