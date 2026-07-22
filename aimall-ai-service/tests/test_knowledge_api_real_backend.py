import asyncio

from app.api import knowledge_api


def test_rebuild_and_list_delegate_to_real_java_backend(monkeypatch):
    calls = []

    async def rebuild():
        calls.append(("rebuild",))
        return {"docCount": 3, "chunkCount": 12}

    async def list_docs(keyword=None, source_type=None, limit=100):
        calls.append(("list", keyword, source_type, limit))
        return [{"id": 9, "title": "真实政策"}]

    monkeypatch.setattr(knowledge_api.java_client, "rebuild_knowledge_chunks", rebuild)
    monkeypatch.setattr(knowledge_api.java_client, "list_knowledge_docs", list_docs)

    rebuilt = asyncio.run(knowledge_api.rebuild_knowledge())
    listed = asyncio.run(knowledge_api.list_knowledge_docs(keyword="退货", source_type="POLICY"))

    assert rebuilt["data"] == {"docCount": 3, "chunkCount": 12}
    assert listed["data"] == [{"id": 9, "title": "真实政策"}]
    assert calls == [("rebuild",), ("list", "退货", "POLICY", 100)]
