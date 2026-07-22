from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def test_java_task_detail_contract_exposes_execution_fence():
    source = (ROOT / "aimall-server/src/main/java/com/aimall/server/ai/InternalKnowledgeTaskController.java").read_text(
        encoding="utf-8"
    )

    assert 'data.put("executionToken", task.getExecutionToken())' in source
    assert 'data.put("attemptNo", task.getAttemptNo())' in source


def test_java_vector_callback_contract_requires_task_and_token():
    source = (ROOT / "aimall-server/src/main/java/com/aimall/server/ai/InternalAiController.java").read_text(
        encoding="utf-8"
    )

    assert "params.executionTaskId()" in source
    assert "params.executionToken()" in source
    assert "knowledgeTaskExecutionGuard.lockActive" in source


def test_ai_client_contract_injects_bound_execution_context():
    source = (ROOT / "aimall-ai-service/app/tools/java_client.py").read_text(encoding="utf-8")

    assert 'request_body["executionTaskId"]' in source
    assert 'request_body["executionToken"]' in source
