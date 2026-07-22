from app.rag import milvus_store as module


class _BrokenClient:
    def __init__(self):
        self.closed = False

    def has_collection(self, _collection: str) -> bool:
        raise RuntimeError("closed channel")

    def close(self) -> None:
        self.closed = True


class _HealthyClient:
    def has_collection(self, _collection: str) -> bool:
        return False


def test_health_reconnects_after_cached_channel_breaks(monkeypatch):
    broken = _BrokenClient()
    clients = iter([broken, _HealthyClient()])
    client_options = []

    def create_client(**kwargs):
        client_options.append(kwargs)
        return next(clients)

    monkeypatch.setattr(module, "MilvusClient", create_client)
    store = module.MilvusVectorStore()

    result = store.health()

    assert result["status"] == "UP"
    assert result["collectionExists"] is False
    assert isinstance(store.client, _HealthyClient)
    assert broken.closed is True
    assert all(options["dedicated"] is True for options in client_options)
    assert all(options["timeout"] == module.settings.MILVUS_CONNECT_TIMEOUT_SECONDS for options in client_options)


def test_health_stays_down_when_reconnect_also_fails(monkeypatch):
    monkeypatch.setattr(module, "MilvusClient", lambda **_kwargs: _BrokenClient())
    store = module.MilvusVectorStore()

    result = store.health()

    assert result["status"] == "DOWN"
    assert "closed channel" in result["message"]
