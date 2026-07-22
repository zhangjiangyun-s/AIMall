import json
import time
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.config.settings import settings
from app.llm.model_router import ModelCandidate, ModelPurpose, model_router
from app.observability.metrics_registry import agent_metrics


class AgnesClient:
    def __init__(self) -> None:
        self.base_url = settings.AGNES_BASE_URL.rstrip("/") if settings.AGNES_BASE_URL else ""
        self.model = settings.AGNES_MODEL
        self.timeout = settings.LLM_TIMEOUT
        self.api_key = settings.AGNES_API_KEY
        self._client: httpx.AsyncClient | None = None

    @property
    def enabled(self) -> bool:
        return self.external_calls_allowed and bool(self.api_key and self.base_url and self.model)

    @property
    def external_calls_allowed(self) -> bool:
        return settings.AI_RUNTIME_MODE in {"LLM", "SANDBOX", "PRODUCTION"}

    @property
    def client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=self.timeout)
        return self._client

    async def close(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()

    def _build_user_content(self, message: str, context: dict[str, Any] | None = None) -> str:
        if not context:
            return message

        context_json = json.dumps(context, ensure_ascii=False, default=str)
        return (
            "页面上下文如下，请只把它当作辅助信息，不要编造未提供的数据。\n"
            f"{context_json}\n\n"
            f"用户问题：{message}"
        )

    def _build_payload(
        self,
        message: str,
        system_prompt: str,
        context: dict[str, Any] | None,
        stream: bool,
        model: str,
    ) -> dict[str, Any]:
        return {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": self._build_user_content(message, context)},
            ],
            "temperature": 0.3,
            "stream": stream,
        }

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

    async def chat(
        self,
        message: str,
        system_prompt: str,
        context: dict[str, Any] | None = None,
        *,
        purpose: ModelPurpose | str = ModelPurpose.GENERATION,
    ) -> str:
        if not self.enabled:
            raise RuntimeError(self._disabled_reason())

        input_text = self._build_user_content(message, context)
        route = model_router.route(purpose)
        last_error: Exception | None = None
        attempted_models: list[str] = []
        for index, candidate in enumerate(route.candidates):
            attempted_models.append(candidate.model)
            started_at = time.perf_counter()
            try:
                response = await self.client.post(
                    f"{self.base_url}/chat/completions",
                    headers=self._headers(),
                    json=self._build_payload(message, system_prompt, context, stream=False, model=candidate.model),
                )
                response.raise_for_status()
                data = response.json()
                answer = data["choices"][0]["message"]["content"]
                usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
                has_input_usage = self._has_usage_value(usage, "prompt_tokens", "input_tokens")
                has_output_usage = self._has_usage_value(usage, "completion_tokens", "output_tokens")
                input_tokens = self._usage_value(usage, "prompt_tokens", "input_tokens") if has_input_usage else self._estimate_tokens(system_prompt + input_text)
                output_tokens = self._usage_value(usage, "completion_tokens", "output_tokens") if has_output_usage else self._estimate_tokens(answer)
                usage_source = "PROVIDER" if has_input_usage and has_output_usage else "PROVIDER_PARTIAL" if usage else "ESTIMATED"
                agent_metrics.record_llm(
                    latency_ms=self._latency_ms(started_at),
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    cost_usd=self._estimate_cost(input_tokens, output_tokens, candidate),
                    purpose=route.purpose.value,
                    model=candidate.model,
                    fallback_used=index > 0,
                    usage_source=usage_source,
                    pricing_configured=(
                        candidate.input_cost_per_million_usd > 0
                        and candidate.output_cost_per_million_usd > 0
                    ),
                )
                return answer
            except Exception as exc:
                last_error = exc
                agent_metrics.record_llm(
                    latency_ms=self._latency_ms(started_at),
                    failed=True,
                    purpose=route.purpose.value,
                    model=candidate.model,
                    fallback_used=index > 0,
                )
                if not self._is_retryable_error(exc):
                    break
        raise RuntimeError(f"LLM request failed after models: {', '.join(attempted_models)}") from last_error

    async def stream_chat(
        self,
        message: str,
        system_prompt: str,
        context: dict[str, Any] | None = None,
        *,
        purpose: ModelPurpose | str = ModelPurpose.GENERATION,
    ) -> AsyncIterator[str]:
        if not self.enabled:
            raise RuntimeError(self._disabled_reason())

        route = model_router.route(purpose)
        input_text = self._build_user_content(message, context)
        attempted_models: list[str] = []
        for index, candidate in enumerate(route.candidates):
            attempted_models.append(candidate.model)
            started_at = time.perf_counter()
            emitted = False
            output_parts: list[str] = []
            usage: dict[str, Any] = {}
            try:
                async with self.client.stream(
                    "POST",
                    f"{self.base_url}/chat/completions",
                    headers=self._headers(),
                    json=self._build_payload(message, system_prompt, context, stream=True, model=candidate.model),
                ) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        payload = line.removeprefix("data:").strip()
                        if payload == "[DONE]":
                            break
                        if not payload:
                            continue
                        try:
                            data = json.loads(payload)
                        except json.JSONDecodeError:
                            continue
                        if isinstance(data.get("usage"), dict):
                            usage = data["usage"]
                        choices = data.get("choices") or []
                        if not choices:
                            continue
                        delta = choices[0].get("delta") or {}
                        content = delta.get("content")
                        if content:
                            emitted = True
                            output_parts.append(content)
                            yield content
                has_input_usage = self._has_usage_value(usage, "prompt_tokens", "input_tokens")
                has_output_usage = self._has_usage_value(usage, "completion_tokens", "output_tokens")
                input_tokens = self._usage_value(usage, "prompt_tokens", "input_tokens") if has_input_usage else self._estimate_tokens(system_prompt + input_text)
                output_tokens = self._usage_value(usage, "completion_tokens", "output_tokens") if has_output_usage else self._estimate_tokens("".join(output_parts))
                usage_source = "PROVIDER" if has_input_usage and has_output_usage else "PROVIDER_PARTIAL" if usage else "ESTIMATED"
                agent_metrics.record_llm(
                    latency_ms=self._latency_ms(started_at),
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    cost_usd=self._estimate_cost(input_tokens, output_tokens, candidate),
                    purpose=route.purpose.value,
                    model=candidate.model,
                    fallback_used=index > 0,
                    usage_source=usage_source,
                    pricing_configured=(
                        candidate.input_cost_per_million_usd > 0
                        and candidate.output_cost_per_million_usd > 0
                    ),
                )
                return
            except Exception as exc:
                agent_metrics.record_llm(
                    latency_ms=self._latency_ms(started_at),
                    failed=True,
                    purpose=route.purpose.value,
                    model=candidate.model,
                    fallback_used=index > 0,
                )
                if emitted or not self._is_retryable_error(exc) or index == len(route.candidates) - 1:
                    raise

    async def check(self) -> dict[str, Any]:
        route = model_router.route(ModelPurpose.GENERATION)
        answer = await self.chat(
            "请只回复 AIMall 模型连通成功",
            "你是 AIMall 的连通性检测助手，只输出简短中文。",
        )
        return {
            "ok": True,
            "model": route.selected_model,
            "attemptedModels": route.attempted_models,
            "baseUrl": self.base_url,
            "answer": answer,
        }

    def _disabled_reason(self) -> str:
        if not self.external_calls_allowed:
            return f"External LLM calls are disabled in AI_RUNTIME_MODE={settings.AI_RUNTIME_MODE}"
        return "LLM is not configured; check AGNES_API_KEY, AGNES_BASE_URL and AGNES_MODEL"

    @staticmethod
    def _usage_value(usage: dict[str, Any], *keys: str) -> int:
        for key in keys:
            value = usage.get(key)
            if isinstance(value, (int, float)) and value >= 0:
                return int(value)
        return 0

    @staticmethod
    def _has_usage_value(usage: dict[str, Any], *keys: str) -> bool:
        return any(isinstance(usage.get(key), (int, float)) and usage[key] >= 0 for key in keys)

    @staticmethod
    def _is_retryable_error(exc: Exception) -> bool:
        if isinstance(exc, (httpx.TimeoutException, httpx.NetworkError)):
            return True
        if isinstance(exc, httpx.HTTPStatusError):
            status_code = exc.response.status_code
            return status_code in {408, 425, 429} or status_code >= 500
        return False

    @staticmethod
    def _estimate_tokens(text: str) -> int:
        return max(1, (len(text or "") + 3) // 4)

    @staticmethod
    def _latency_ms(started_at: float) -> int:
        return max(0, int((time.perf_counter() - started_at) * 1000))

    @staticmethod
    def _estimate_cost(input_tokens: int, output_tokens: int, candidate: ModelCandidate) -> float:
        return (
            input_tokens * candidate.input_cost_per_million_usd
            + output_tokens * candidate.output_cost_per_million_usd
        ) / 1_000_000


agnes_client = AgnesClient()
