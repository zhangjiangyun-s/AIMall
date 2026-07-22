from __future__ import annotations

from collections.abc import Iterable

from app.reflection.models import (
    ReflectionAction,
    ReflectionDecision,
    ReflectionFinding,
    ReflectionIssueType,
    ReflectionRequest,
    ReflectionSeverity,
    ReflectionStatus,
)


REFLECTION_POLICY_VERSION = "REFLECTION_V1"

HUMAN_REVIEW_ISSUES = {
    ReflectionIssueType.BUSINESS_FACT_CONFLICT,
    ReflectionIssueType.CONFIRMATION_STATE_ERROR,
    ReflectionIssueType.VALIDATOR_FAILURE,
}
CLARIFICATION_ISSUES = {ReflectionIssueType.USER_INTENT_AMBIGUOUS}
RETRIEVAL_ISSUES = {
    ReflectionIssueType.RETRIEVAL_FAILED,
    ReflectionIssueType.MISSING_EVIDENCE,
}
GENERATION_ISSUES = {
    ReflectionIssueType.EMPTY_ANSWER,
    ReflectionIssueType.MISSING_CITATION,
    ReflectionIssueType.INVALID_CITATION,
    ReflectionIssueType.UNSUPPORTED_FACT,
    ReflectionIssueType.EVIDENCE_CONTRADICTION,
    ReflectionIssueType.ANSWER_INCOMPLETE,
    ReflectionIssueType.FORMAT_VIOLATION,
}
TOOL_EXECUTION_ISSUES = {
    ReflectionIssueType.TOOL_FAILURE,
    ReflectionIssueType.TASK_INCOMPLETE,
}


class ReflectionService:
    def decide(
        self,
        request: ReflectionRequest,
        findings: Iterable[ReflectionFinding],
    ) -> ReflectionDecision:
        items = list(findings)
        if not items:
            return self._decision(request, True, ReflectionStatus.PASSED, ReflectionAction.ACCEPT, True, items)

        issue_types = {item.issueType for item in items}
        has_critical = any(item.severity == ReflectionSeverity.CRITICAL for item in items)
        if has_critical or issue_types & HUMAN_REVIEW_ISSUES:
            return self._decision(
                request,
                False,
                ReflectionStatus.HUMAN_REVIEW_REQUIRED,
                ReflectionAction.HANDOFF_HUMAN,
                True,
                items,
            )

        if issue_types & CLARIFICATION_ISSUES:
            return self._decision(
                request,
                False,
                ReflectionStatus.CLARIFICATION_REQUIRED,
                ReflectionAction.REQUEST_CLARIFICATION,
                True,
                items,
            )

        retry_available = request.attempt < request.maxAttempts
        if issue_types & RETRIEVAL_ISSUES:
            if retry_available and any(item.retryable for item in items if item.issueType in RETRIEVAL_ISSUES):
                return self._decision(
                    request,
                    False,
                    ReflectionStatus.RETRY_REQUIRED,
                    ReflectionAction.RETRY_RETRIEVAL,
                    False,
                    items,
                )
            return self._decision(
                request,
                False,
                ReflectionStatus.REFUSED,
                ReflectionAction.REFUSE,
                True,
                items,
            )

        if issue_types & TOOL_EXECUTION_ISSUES:
            if retry_available and any(item.retryable for item in items if item.issueType in TOOL_EXECUTION_ISSUES):
                return self._decision(
                    request,
                    False,
                    ReflectionStatus.RETRY_REQUIRED,
                    ReflectionAction.RETRY_TOOL_EXECUTION,
                    False,
                    items,
                )
            if request.hasEvidence or request.hasBusinessEvidence:
                return self._decision(
                    request,
                    False,
                    ReflectionStatus.DEGRADED,
                    ReflectionAction.RETURN_EVIDENCE_ONLY,
                    True,
                    items,
                )
            return self._decision(
                request,
                False,
                ReflectionStatus.REFUSED,
                ReflectionAction.REFUSE,
                True,
                items,
            )

        if issue_types & GENERATION_ISSUES:
            if retry_available and any(item.retryable for item in items):
                return self._decision(
                    request,
                    False,
                    ReflectionStatus.RETRY_REQUIRED,
                    ReflectionAction.RETRY_GENERATION,
                    False,
                    items,
                )
            if request.hasEvidence or request.hasBusinessEvidence:
                return self._decision(
                    request,
                    False,
                    ReflectionStatus.DEGRADED,
                    ReflectionAction.RETURN_EVIDENCE_ONLY,
                    True,
                    items,
                )
            return self._decision(
                request,
                False,
                ReflectionStatus.REFUSED,
                ReflectionAction.REFUSE,
                True,
                items,
            )

        return self._decision(
            request,
            False,
            ReflectionStatus.HUMAN_REVIEW_REQUIRED,
            ReflectionAction.HANDOFF_HUMAN,
            True,
            items,
        )

    def _decision(
        self,
        request: ReflectionRequest,
        passed: bool,
        status: ReflectionStatus,
        action: ReflectionAction,
        terminal: bool,
        findings: list[ReflectionFinding],
    ) -> ReflectionDecision:
        return ReflectionDecision(
            passed=passed,
            status=status,
            action=action,
            terminal=terminal,
            attempt=request.attempt,
            maxAttempts=request.maxAttempts,
            findings=findings,
            policyVersion=REFLECTION_POLICY_VERSION,
        )


reflection_service = ReflectionService()
