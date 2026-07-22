import hashlib
import math
import asyncio
from typing import Any

import httpx

from app.config.settings import settings


class HashEmbeddingProvider:
    """Deterministic local embedding for the first Milvus integration pass."""

    def __init__(self, dim: int | None = None) -> None:
        self.dim = dim or settings.EMBEDDING_DIM

    def embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dim
        normalized = (text or "").strip().lower()
        if not normalized:
            return vector

        tokens = self._tokens(normalized)
        for token in tokens:
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dim
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[index] += sign

        norm = math.sqrt(sum(item * item for item in vector))
        if norm == 0:
            return vector
        return [item / norm for item in vector]

    def _tokens(self, text: str) -> list[str]:
        words = [item for item in text.replace("\n", " ").split(" ") if item]
        char_grams = [text[index : index + 2] for index in range(max(0, len(text) - 1))]
        return words + char_grams


class ArkEmbeddingProvider:
    def __init__(self) -> None:
        self.model = settings.EMBEDDING_MODEL
        self.url = settings.ARK_EMBEDDING_URL
        self.api_key = settings.ARK_API_KEY
        self.timeout = 60

    async def embed(self, text: str) -> list[float]:
        if not self.api_key:
            raise RuntimeError("ARK_API_KEY is not configured")
        payload = {
            "model": self.model,
            "input": [
                {
                    "type": "text",
                    "text": text or "",
                }
            ],
        }
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(self.url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
        embedding = self._extract_embedding(body)
        if len(embedding) != settings.EMBEDDING_DIM:
            raise RuntimeError(f"Embedding dimension mismatch: expected {settings.EMBEDDING_DIM}, got {len(embedding)}")
        return embedding

    def _extract_embedding(self, body: dict[str, Any]) -> list[float]:
        data = body.get("data")
        if isinstance(data, dict) and isinstance(data.get("embedding"), list):
            return [float(item) for item in data["embedding"]]
        if isinstance(data, list) and data and isinstance(data[0], dict) and isinstance(data[0].get("embedding"), list):
            return [float(item) for item in data[0]["embedding"]]
        raise RuntimeError("ARK embedding response does not contain data.embedding")


class EmbeddingProvider:
    def __init__(self) -> None:
        self.hash_provider = HashEmbeddingProvider()
        self.ark_provider = ArkEmbeddingProvider()

    async def embed(self, text: str) -> list[float]:
        if settings.EMBEDDING_PROVIDER.lower() == "ark":
            return await self.ark_provider.embed(text)
        return self.hash_provider.embed(text)

    async def embed_batch(
        self,
        texts: list[str],
        *,
        concurrency: int = 4,
        max_retries: int = 3,
    ) -> list[list[float] | Exception]:
        semaphore = asyncio.Semaphore(max(1, concurrency))

        async def run_one(text: str) -> list[float] | Exception:
            async with semaphore:
                for attempt in range(max_retries):
                    try:
                        return await self.embed(text)
                    except Exception as exc:
                        if attempt >= max_retries - 1:
                            return exc
                        await asyncio.sleep(2 ** (attempt + 1))
                return RuntimeError("Embedding retry exhausted")

        return list(await asyncio.gather(*(run_one(text) for text in texts)))


embedding_provider = EmbeddingProvider()
