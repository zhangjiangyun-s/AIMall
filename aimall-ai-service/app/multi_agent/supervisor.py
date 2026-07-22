from __future__ import annotations

from app.agent.react_agent import ReActAgent
from app.config.settings import settings
from app.multi_agent.contracts import Delegation, DelegationPlan, SpecialistId
from app.schemas.chat_schema import ChatRequest, PageContext
from app.multi_agent.specialists import specialist_allowed_tools


class MultiAgentSupervisor:
    """Produces a bounded specialist plan; it never executes tools itself."""

    def plan(self, *, intent: str, message: str, page_context: PageContext | None = None) -> DelegationPlan:
        if not settings.MULTI_AGENT_ENABLED:
            return DelegationPlan(intent=intent, fallbackToLegacy=True, fallbackReason="MULTI_AGENT_DISABLED")

        specialists = self._specialists_for(intent, message, page_context)
        if not specialists or len(specialists) > settings.MULTI_AGENT_MAX_DELEGATIONS:
            return DelegationPlan(intent=intent, fallbackToLegacy=True, fallbackReason="NO_SAFE_DELEGATION_PLAN")

        delegations = [
            Delegation(
                specialist=specialist,
                sequence=index,
                reason=self._reason(specialist, intent),
                allowedTools=list(self._tools_for(specialist)),
            )
            for index, specialist in enumerate(specialists, start=1)
        ]
        return DelegationPlan(intent=intent, delegations=delegations)

    def _specialists_for(
        self,
        intent: str,
        message: str,
        page_context: PageContext | None,
    ) -> list[SpecialistId]:
        write_action = ReActAgent()._scripted_write_action(
            ChatRequest(message=message, sessionId="supervisor-plan", pageContext=page_context),
            None,
        )
        if write_action:
            return [
                SpecialistId.PRODUCT
                if write_action.get("action") == "add_to_cart_confirmed"
                else SpecialistId.ORDER
            ]
        policy_follow_up = self._needs_policy(message)
        if intent in {"RECOMMENDATION", "PRODUCT_QA"}:
            return [SpecialistId.PRODUCT, SpecialistId.POLICY] if policy_follow_up else [SpecialistId.PRODUCT]
        if intent in {"ORDER_QA", "RETURN_QA"}:
            return [SpecialistId.ORDER, SpecialistId.POLICY] if policy_follow_up else [SpecialistId.ORDER]
        if intent in {"COUPON_QA", "ADDRESS_QA"}:
            return [SpecialistId.ORDER]
        if intent == "POLICY_QA":
            return [SpecialistId.POLICY]
        if page_context and page_context.pageType == "PRODUCT_DETAIL":
            return [SpecialistId.PRODUCT]
        if page_context and page_context.pageType == "ORDER_DETAIL":
            return [SpecialistId.ORDER]
        return []

    def _needs_policy(self, message: str) -> bool:
        return ReActAgent()._needs_policy_followup(message)

    def _tools_for(self, specialist: SpecialistId) -> tuple[str, ...]:
        return specialist_allowed_tools(specialist)

    @staticmethod
    def _reason(specialist: SpecialistId, intent: str) -> str:
        reasons = {
            SpecialistId.PRODUCT: "需要获取商品实时信息。",
            SpecialistId.ORDER: "需要获取当前登录用户的业务信息。",
            SpecialistId.POLICY: "需要检索可引用的商城政策依据。",
        }
        return f"{reasons[specialist]}（意图：{intent}）"


multi_agent_supervisor = MultiAgentSupervisor()
