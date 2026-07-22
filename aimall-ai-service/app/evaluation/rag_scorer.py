from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

from app.evaluation.loader import EvaluationDataset
from app.evaluation.models import EvaluationCase, RagRelevantEvidence
from app.evaluation.rag_mode_quality_gate import rag_mode_quality_gate
from app.config.settings import settings
from app.reflection.validator import extract_fact_tokens


CITATION_FAITHFULNESS_METHOD = "FACT_TOKEN_AND_CHAR_BIGRAM_V1"


class RagEvaluationScorer:
    def score(self, dataset: EvaluationDataset, run: dict[str, Any]) -> dict[str, Any]:
        self._validate_run(dataset, run)
        case_by_id = {case.id: case for case in dataset.cases}
        scores = [
            self._score_case(case_by_id[item["caseId"]], item)
            for item in run.get("results") or []
            if case_by_id[item["caseId"]].expected.rag is not None
        ]
        summary = self._summary(scores)
        return {
            "schemaVersion": "AIMALL_RAG_EVAL_SCORE_V1",
            "runId": run.get("runId"),
            "datasetId": dataset.manifest.datasetId,
            "datasetVersion": dataset.manifest.version,
            "scoredAt": datetime.now().astimezone().isoformat(timespec="seconds"),
            "citationFaithfulnessMethod": CITATION_FAITHFULNESS_METHOD,
            "retrievalMode": settings.RAG_RETRIEVAL_MODE,
            "summary": summary,
            "qualityGate": rag_mode_quality_gate.evaluate(settings.RAG_RETRIEVAL_MODE, summary),
            "caseScores": scores,
        }

    def score_file(
        self,
        dataset: EvaluationDataset,
        run_path: str | Path,
        output_path: str | Path,
    ) -> dict[str, Any]:
        run = json.loads(Path(run_path).read_text(encoding="utf-8"))
        report = self.score(dataset, run)
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(output)
        return report

    def _validate_run(self, dataset: EvaluationDataset, run: dict[str, Any]) -> None:
        if run.get("datasetId") != dataset.manifest.datasetId:
            raise ValueError("evaluation run datasetId does not match manifest")
        if run.get("datasetVersion") != dataset.manifest.version:
            raise ValueError("evaluation run datasetVersion does not match manifest")
        case_ids = {case.id for case in dataset.cases}
        result_ids = [str(item.get("caseId") or "") for item in run.get("results") or []]
        unknown = sorted(set(result_ids) - case_ids)
        if unknown:
            raise ValueError(f"evaluation run contains unknown case ids: {unknown}")
        duplicates = sorted(item for item, count in Counter(result_ids).items() if count > 1)
        if duplicates:
            raise ValueError(f"evaluation run contains duplicate case ids: {duplicates}")

    def _score_case(self, case: EvaluationCase, result: dict[str, Any]) -> dict[str, Any]:
        rag = case.expected.rag
        assert rag is not None
        execution_status = str(result.get("status") or "ERROR")
        base = {
            "caseId": case.id,
            "category": case.category.value,
            "executionStatus": execution_status,
            "noMatchExpected": rag.noMatchExpected,
        }
        if execution_status == "UNSUPPORTED":
            return {
                **base,
                "scoreStatus": "NOT_EVALUATED",
                "passed": None,
                "checks": [],
                "metrics": {},
                "failureCodes": [str(result.get("errorType") or "UNSUPPORTED")],
            }
        if execution_status != "SUCCESS":
            return {
                **base,
                "scoreStatus": "FAILED",
                "passed": False,
                "checks": [self._check("execution", False, "SUCCESS", execution_status)],
                "metrics": self._empty_metrics(rag.noMatchExpected),
                "failureCodes": ["execution"],
            }

        done = result.get("done") if isinstance(result.get("done"), dict) else {}
        citations = [item for item in done.get("ragCitations") or [] if isinstance(item, dict)]
        retrieval_status = str(done.get("retrievalStatus") or "NO_MATCH")
        answer = str(result.get("answer") or "")
        if rag.noMatchExpected:
            status_ok = not case.expected.retrievalStatusAnyOf or retrieval_status in case.expected.retrievalStatusAnyOf
            no_citations = not citations
            refused = self._is_refusal(done, result.get("events") or [])
            checks = [
                self._check("no_match_status", status_ok, case.expected.retrievalStatusAnyOf, retrieval_status),
                self._check("no_match_has_no_citations", no_citations, True, bool(citations)),
                self._check("no_match_refusal", refused, True, refused),
            ]
            passed = all(item["passed"] for item in checks)
            return {
                **base,
                "scoreStatus": "PASSED" if passed else "FAILED",
                "passed": passed,
                "checks": checks,
                "metrics": {**self._empty_metrics(True), "noMatchCorrect": 1.0 if passed else 0.0},
                "failureCodes": [item["checkId"] for item in checks if not item["passed"]],
            }

        top_citations = citations[: rag.k]
        matches = [self._matching_evidence(item, rag.relevantEvidence) for item in top_citations]
        matched_ids = {match.id for row in matches for match in row}
        relevant_count = len(rag.relevantEvidence)
        recall_at_k = len(matched_ids) / relevant_count if relevant_count else 0.0
        first_relevant = next((index for index, row in enumerate(matches, start=1) if row), None)
        mrr = 1 / first_relevant if first_relevant else 0.0
        ndcg = self._ndcg(matches, rag.relevantEvidence, rag.k)
        context_precision = sum(bool(row) for row in matches) / len(top_citations) if top_citations else 0.0

        citation_by_id = {str(item.get("id")): item for item in citations if item.get("id") is not None}
        marker_ids = re.findall(r"\[(\d+)]", answer)
        unique_markers = list(dict.fromkeys(marker_ids))
        valid_markers = [item for item in unique_markers if item in citation_by_id]
        relevant_markers = [
            item
            for item in valid_markers
            if self._matching_evidence(citation_by_id[item], rag.relevantEvidence)
        ]
        citation_validity = len(valid_markers) / len(unique_markers) if unique_markers else 0.0
        citation_accuracy = len(relevant_markers) / len(unique_markers) if unique_markers else 0.0
        cited_relevant_ids = {
            match.id
            for marker in relevant_markers
            for match in self._matching_evidence(citation_by_id[marker], rag.relevantEvidence)
        }
        citation_coverage = len(cited_relevant_ids) / relevant_count if relevant_count else 0.0
        reflection = done.get("reflection") if isinstance(done.get("reflection"), dict) else {}
        faithfulness = self._citation_faithfulness(
            answer,
            citation_by_id,
            rag.faithfulnessMinOverlap,
            evidence_only=reflection.get("action") == "RETURN_EVIDENCE_ONLY",
        )
        citation_text = "\n".join(
            f"{item.get('title') or ''}\n{item.get('snippet') or ''}" for item in citations
        )
        citation_terms_ok = (
            not case.expected.citationTermsAnyOf
            or any(term in citation_text for term in case.expected.citationTermsAnyOf)
        )
        status_ok = not case.expected.retrievalStatusAnyOf or retrieval_status in case.expected.retrievalStatusAnyOf
        checks = [
            self._check("retrieval_status", status_ok, case.expected.retrievalStatusAnyOf, retrieval_status),
            self._check("recall_at_k", recall_at_k >= rag.minRecallAtK, rag.minRecallAtK, recall_at_k),
            self._check("mrr", mrr >= rag.minMrr, rag.minMrr, mrr),
            self._check("ndcg", ndcg >= rag.minNdcg, rag.minNdcg, ndcg),
            self._check("citation_terms", citation_terms_ok, case.expected.citationTermsAnyOf, citation_terms_ok),
            self._check(
                "citation_accuracy",
                citation_accuracy >= rag.minCitationAccuracy,
                rag.minCitationAccuracy,
                citation_accuracy,
            ),
            self._check(
                "citation_coverage",
                citation_coverage >= rag.minCitationCoverage,
                rag.minCitationCoverage,
                citation_coverage,
            ),
            self._check(
                "citation_faithfulness",
                faithfulness["value"] is not None and faithfulness["value"] >= rag.minCitationFaithfulness,
                rag.minCitationFaithfulness,
                faithfulness["value"],
            ),
        ]
        passed = all(item["passed"] for item in checks)
        return {
            **base,
            "scoreStatus": "PASSED" if passed else "FAILED",
            "passed": passed,
            "checks": checks,
            "metrics": {
                "k": rag.k,
                "retrievedCount": len(top_citations),
                "relevantEvidenceCount": relevant_count,
                "matchedRelevantEvidenceIds": sorted(matched_ids),
                "recallAtK": round(recall_at_k, 4),
                "mrr": round(mrr, 4),
                "ndcg": round(ndcg, 4),
                "contextPrecision": round(context_precision, 4),
                "citationValidity": round(citation_validity, 4),
                "citationAccuracy": round(citation_accuracy, 4),
                "citationCoverage": round(citation_coverage, 4),
                "citationFaithfulness": faithfulness,
                "noMatchCorrect": None,
            },
            "failureCodes": [item["checkId"] for item in checks if not item["passed"]],
        }

    def _matching_evidence(
        self,
        citation: dict[str, Any],
        relevant: list[RagRelevantEvidence],
    ) -> list[RagRelevantEvidence]:
        source = str(citation.get("source") or "")
        title = str(citation.get("title") or "")
        snippet = str(citation.get("snippet") or "")
        matches = []
        for item in relevant:
            identity_match = any(source.startswith(prefix) for prefix in item.sourcePrefixesAnyOf) or any(
                term in title for term in item.titleTermsAnyOf
            )
            snippet_match = not item.snippetTermsAnyOf or any(term in snippet for term in item.snippetTermsAnyOf)
            if identity_match and snippet_match:
                matches.append(item)
        return matches

    def _ndcg(
        self,
        matches: list[list[RagRelevantEvidence]],
        relevant: list[RagRelevantEvidence],
        k: int,
    ) -> float:
        claimed: set[str] = set()
        gains = []
        for row in matches[:k]:
            candidates = sorted((item for item in row if item.id not in claimed), key=lambda item: item.grade, reverse=True)
            if candidates:
                claimed.add(candidates[0].id)
                gains.append(candidates[0].grade)
            else:
                gains.append(0)
        dcg = sum((2**grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(gains, start=1))
        ideal = sorted((item.grade for item in relevant), reverse=True)[:k]
        idcg = sum((2**grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(ideal, start=1))
        return dcg / idcg if idcg else 0.0

    def _citation_faithfulness(
        self,
        answer: str,
        citation_by_id: dict[str, dict[str, Any]],
        min_overlap: float,
        *,
        evidence_only: bool = False,
    ) -> dict[str, Any]:
        if evidence_only:
            return self._evidence_only_faithfulness(answer, citation_by_id)
        claims = []
        for marker in re.finditer(r"\[(\d+)]", answer):
            citation_id = marker.group(1)
            claim_end = marker.start()
            while claim_end > 0 and answer[claim_end - 1] in "。！？；\n \t":
                claim_end -= 1
            start = max(answer.rfind(char, 0, claim_end) for char in "。！？；\n") + 1
            claim = answer[start:claim_end].strip(" -*:#")
            citation = citation_by_id.get(citation_id)
            snippet = str((citation or {}).get("snippet") or "")
            claim_facts = extract_fact_tokens(claim)
            evidence_facts = extract_fact_tokens(snippet)
            facts_supported = claim_facts.issubset(evidence_facts)
            overlap = self._bigram_overlap(claim, snippet)
            passed = citation is not None and bool(claim) and facts_supported and overlap >= min_overlap
            claims.append(
                {
                    "citationId": citation_id,
                    "passed": passed,
                    "factTokens": sorted(claim_facts),
                    "unsupportedFactTokens": sorted(claim_facts - evidence_facts),
                    "overlap": round(overlap, 4),
                }
            )
        value = sum(item["passed"] for item in claims) / len(claims) if claims else None
        return {
            "method": CITATION_FAITHFULNESS_METHOD,
            "claimCount": len(claims),
            "passedClaimCount": sum(item["passed"] for item in claims),
            "value": round(value, 4) if value is not None else None,
            "claims": claims,
        }

    def _evidence_only_faithfulness(
        self,
        answer: str,
        citation_by_id: dict[str, dict[str, Any]],
    ) -> dict[str, Any]:
        markers = list(re.finditer(r"\[(\d+)]", answer))
        claims = []
        for index, marker in enumerate(markers):
            citation_id = marker.group(1)
            citation = citation_by_id.get(citation_id)
            block_end = markers[index + 1].start() if index + 1 < len(markers) else len(answer)
            block = answer[marker.end():block_end]
            snippet = str((citation or {}).get("snippet") or "")
            normalized_block = self._normalize_evidence_text(block)
            normalized_snippet = self._normalize_evidence_text(snippet)
            passed = bool(citation and normalized_snippet and normalized_snippet in normalized_block)
            claims.append(
                {
                    "citationId": citation_id,
                    "passed": passed,
                    "factTokens": sorted(extract_fact_tokens(snippet)),
                    "unsupportedFactTokens": [],
                    "overlap": 1.0 if passed else 0.0,
                }
            )
        value = sum(item["passed"] for item in claims) / len(claims) if claims else None
        return {
            "method": CITATION_FAITHFULNESS_METHOD,
            "claimCount": len(claims),
            "passedClaimCount": sum(item["passed"] for item in claims),
            "value": round(value, 4) if value is not None else None,
            "claims": claims,
        }

    def _normalize_evidence_text(self, value: str) -> str:
        return re.sub(r"\s+", "", value).lower()

    def _bigram_overlap(self, claim: str, snippet: str) -> float:
        def bigrams(value: str) -> set[str]:
            normalized = re.sub(r"[\W_]+", "", value.lower(), flags=re.UNICODE)
            return {normalized[index : index + 2] for index in range(max(0, len(normalized) - 1))}

        claim_bigrams = bigrams(claim)
        evidence_bigrams = bigrams(snippet)
        return len(claim_bigrams & evidence_bigrams) / len(claim_bigrams) if claim_bigrams else 0.0

    def _is_refusal(self, done: dict[str, Any], events: list[dict[str, Any]]) -> bool:
        reflection = done.get("reflection") if isinstance(done.get("reflection"), dict) else {}
        blocked = any(
            isinstance(item, dict) and item.get("type") == "guardrail" and item.get("action") == "BLOCK"
            for item in [*(done.get("guardrails") or []), *events]
        )
        return bool(
            done.get("refusalReason")
            or done.get("intent") == "SAFETY_BLOCKED"
            or reflection.get("status") == "REFUSED"
            or blocked
        )

    def _summary(self, scores: list[dict[str, Any]]) -> dict[str, Any]:
        evaluated = [item for item in scores if item["passed"] is not None]
        positive = [item for item in evaluated if not item["noMatchExpected"]]
        no_match = [item for item in evaluated if item["noMatchExpected"]]
        metric_names = (
            "recallAtK",
            "mrr",
            "ndcg",
            "contextPrecision",
            "citationValidity",
            "citationAccuracy",
            "citationCoverage",
        )
        metrics = {
            name: self._average([item["metrics"].get(name) for item in positive])
            for name in metric_names
        }
        metrics["citationFaithfulness"] = self._average(
            [item["metrics"].get("citationFaithfulness", {}).get("value") for item in positive]
        )
        categories: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for item in scores:
            categories[item["category"]].append(item)
        return {
            "totalRagResults": len(scores),
            "evaluated": len(evaluated),
            "passed": sum(item["passed"] is True for item in evaluated),
            "failed": sum(item["passed"] is False for item in evaluated),
            "notEvaluated": sum(item["passed"] is None for item in scores),
            "passRate": self._ratio(sum(item["passed"] is True for item in evaluated), len(evaluated)),
            **metrics,
            "noMatchAccuracy": self._ratio(
                sum(item["metrics"].get("noMatchCorrect") == 1.0 for item in no_match),
                len(no_match),
            ),
            "categories": {
                category: {
                    "total": len(items),
                    "passed": sum(item["passed"] is True for item in items),
                    "failed": sum(item["passed"] is False for item in items),
                    "notEvaluated": sum(item["passed"] is None for item in items),
                }
                for category, items in sorted(categories.items())
            },
        }

    def _empty_metrics(self, no_match: bool) -> dict[str, Any]:
        return {
            "recallAtK": 0.0 if not no_match else None,
            "mrr": 0.0 if not no_match else None,
            "ndcg": 0.0 if not no_match else None,
            "contextPrecision": 0.0 if not no_match else None,
            "citationValidity": 0.0 if not no_match else None,
            "citationAccuracy": 0.0 if not no_match else None,
            "citationCoverage": 0.0 if not no_match else None,
            "citationFaithfulness": {"method": CITATION_FAITHFULNESS_METHOD, "value": 0.0} if not no_match else None,
            "noMatchCorrect": 0.0 if no_match else None,
        }

    def _check(self, check_id: str, passed: bool, expected: Any, actual: Any) -> dict[str, Any]:
        return {"checkId": check_id, "passed": bool(passed), "expected": expected, "actual": actual}

    def _average(self, values: list[Any]) -> dict[str, Any]:
        numeric = [float(value) for value in values if isinstance(value, (int, float))]
        return {"count": len(numeric), "value": round(sum(numeric) / len(numeric), 4) if numeric else None}

    def _ratio(self, numerator: int, denominator: int) -> dict[str, Any]:
        return {
            "numerator": numerator,
            "denominator": denominator,
            "value": round(numerator / denominator, 4) if denominator else None,
        }


rag_evaluation_scorer = RagEvaluationScorer()
