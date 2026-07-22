import json
import re
from typing import Any

from app.llm.agnes_client import agnes_client
from app.llm.chat_invoker import invoke_chat
from app.llm.model_router import ModelPurpose
from app.router.intent_router import detect_intent
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord
from app.tools.executor import tool_executor
from app.tools.registry import get_tool_definition
from app.tools.tool_router import build_tool_routing_info, list_candidate_tool_definitions, select_candidate_tool_names

MAX_REACT_STEPS = 4
MAX_TOOL_CALLS = 6

REACT_PLANNER_PROMPT = (
    "你是 AIMall Agent 的 ReAct 工具规划器，只负责判断下一步是否需要调用工具，不直接回答用户。"
    "你必须只输出 JSON，不要输出 Markdown，不要解释。"
    "JSON 格式："
    "{\"thought\":\"简短说明为什么需要或不需要工具\","
    "\"action\":\"工具名或 final\","
    "\"arguments\":{}}。"
    "如果已有工具观察结果足够回答用户，action 必须是 final。"
    "如果工具失败或查不到结果，不要重复调用同一个无效工具，应该 final。"
    "只能选择工具清单中的工具。"
    "商品推荐、预算筛选、轻薄本/手机/电脑等问题优先选择 search_products。"
    "如果用户输入的是商品名称，也应该选择 search_products，并把干净的商品名称作为 keyword。"
    "如果用户提到预算，例如 3000 元内，要把 maxPrice 填入 arguments。"
    "商品详情页的问题优先选择 get_product_detail。"
    "如果用户明确要求查询规格/SKU，且上下文有商品 ID，选择 get_product_skus。"
    "如果用户明确要求比较多个商品，且上下文或问题中有多个商品 ID，选择 compare_products。"
    "用户询问订单列表、最近订单、我的订单时选择 list_my_orders。"
    "用户在订单详情页，或明确提供订单 ID、订单号、AIM 开头订单编号时选择 get_my_order_detail。"
    "用户询问我的优惠券时选择 list_my_coupons，询问领券中心或可领取优惠券时选择 list_coupon_center。"
    "用户询问我的售后、退款、退货申请列表时选择 list_my_returns，明确提供售后申请 ID 时选择 get_return_detail。"
    "用户询问我的收货地址、默认地址、配送地址时选择 list_my_addresses。"
    "订单工具不能接收 userId 或 memberId，用户身份只能由登录 token 解析。"
)

STOPWORDS = (
    "帮我",
    "给我",
    "找一个",
    "找一台",
    "找一款",
    "找",
    "推荐",
    "买",
    "选",
    "有没有",
    "商品",
    "名称",
    "的",
    "吗",
    "呢",
    "一个",
    "一台",
    "一款",
    "以内",
    "以下",
    "内",
    "预算",
    "元",
    "块",
)


class ReActAgent:
    async def run_tools(
        self,
        request: ChatRequest,
        trace_id: str,
        memory_context: dict[str, Any] | None = None,
        allowed_tool_names: list[str] | None = None,
        strict_allowed_tools: bool = False,
        intent_override: str | None = None,
        excluded_tool_call_keys: set[str] | None = None,
        max_tool_calls: int = MAX_TOOL_CALLS,
    ) -> tuple[str, list[ToolCallRecord], list[dict[str, Any]]]:
        page_type = request.pageContext.pageType if request.pageContext else None
        intent = detect_intent(request.message, page_type)
        memory_context = memory_context or {"recentTurns": [], "entities": []}
        memory_entity = self._resolve_memory_entity(request.message, memory_context)
        if memory_entity and intent in ("GENERAL_QA", "POLICY_QA", "RECOMMENDATION"):
            intent = {
                "product": "PRODUCT_QA",
                "order": "ORDER_QA",
                "return": "RETURN_QA",
            }.get(str(memory_entity.get("kind")), intent)
        if intent_override:
            intent = intent_override
        auth_token = request.authContext.token if request.authContext else None
        candidate_tools = list(dict.fromkeys(allowed_tool_names)) if allowed_tool_names is not None else select_candidate_tool_names(intent, request.pageContext)
        steps: list[dict[str, Any]] = []
        tool_calls: list[ToolCallRecord] = []
        excluded_tool_call_keys = excluded_tool_call_keys or set()
        tool_call_limit = max(0, min(MAX_TOOL_CALLS, max_tool_calls))

        for step_index in range(MAX_REACT_STEPS):
            if len(tool_calls) >= tool_call_limit:
                steps.append(
                    {
                        "step": step_index + 1,
                        "thought": "工具调用次数已达到上限，停止继续调用工具。",
                        "action": "final",
                        "arguments": {},
                    }
                )
                break

            action = await self._plan_action(request, intent, steps, candidate_tools, memory_context)
            action["step"] = step_index + 1
            action["candidateTools"] = candidate_tools
            action = self._normalize_action(action, request)
            if strict_allowed_tools and action.get("action") not in {"final", *candidate_tools}:
                action = {
                    "step": step_index + 1,
                    "thought": "当前专业 Agent 的授权工具已足够，后续工作交由其他专业 Agent 处理。",
                    "action": "final",
                    "arguments": {},
                    "candidateTools": candidate_tools,
                }
            steps.append(action)

            if action.get("action") == "final":
                break

            tool_name = str(action.get("action") or "")
            arguments = action.get("arguments") if isinstance(action.get("arguments"), dict) else {}
            guard_error = self._guard_tool_call(tool_name, arguments, request, steps, tool_calls, candidate_tools)
            if guard_error:
                record = ToolCallRecord(
                    name=tool_name or "unknown",
                    arguments=arguments,
                    ok=False,
                    error=guard_error,
                    traceId=trace_id,
                )
                tool_calls.append(record)
                action["observation"] = record.model_dump()
                break

            call_key = self.tool_call_key(tool_name, arguments)
            if call_key in excluded_tool_call_keys:
                action["duplicateCallPrevented"] = True
                action["observation"] = {"ok": True, "deduplicated": True}
                steps.append(
                    {
                        "step": step_index + 1,
                        "thought": "等价工具调用已由前序专业 Agent 完成，复用已有证据。",
                        "action": "final",
                        "arguments": {},
                    }
                )
                break

            record = await tool_executor.execute(
                tool_name,
                arguments,
                trace_id,
                auth_token=auth_token,
                page_context=request.pageContext.model_dump() if request.pageContext else None,
                session_id=request.sessionId,
                user_id=request.userId,
                tenant_id=request.tenantId,
            )
            tool_calls.append(record)
            action["observation"] = self._compact_observation(record)

            if self._should_stop_after_tool(tool_name, record):
                steps.append(
                    {
                        "step": step_index + 1,
                        "thought": "工具观察结果已经足够生成回复。",
                        "action": "final",
                        "arguments": {},
                    }
                )
                break

        return intent, tool_calls, steps

    @staticmethod
    def tool_call_key(tool_name: str, arguments: dict[str, Any]) -> str:
        return f"{tool_name}:{json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(',', ':'), default=str)}"

    async def _plan_action(
        self,
        request: ChatRequest,
        intent: str,
        steps: list[dict[str, Any]],
        candidate_tools: list[str] | None = None,
        memory_context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        candidate_tools = candidate_tools or select_candidate_tool_names(intent, request.pageContext)
        scripted_action = self._scripted_next_action(
            request,
            intent,
            steps,
            memory_context,
            candidate_tools,
        )
        if scripted_action:
            return scripted_action

        context = {
            "intent": intent,
            "pageContext": request.pageContext.model_dump() if request.pageContext else None,
            "toolRouting": build_tool_routing_info(intent, request.pageContext),
            "availableTools": list_candidate_tool_definitions(intent, request.pageContext),
            "previousSteps": steps,
            "sessionMemory": memory_context or {"recentTurns": [], "entities": []},
        }

        try:
            raw = await invoke_chat(request.message, REACT_PLANNER_PROMPT, context, purpose=ModelPurpose.PLANNING)
            parsed = self._parse_json(raw)
            if parsed:
                return parsed
        except Exception:
            pass

        return self._fallback_action(request, intent, steps)

    def _scripted_next_action(
        self,
        request: ChatRequest,
        intent: str,
        steps: list[dict[str, Any]],
        memory_context: dict[str, Any] | None = None,
        allowed_tool_names: list[str] | None = None,
    ) -> dict[str, Any] | None:
        if not steps:
            page_context = request.pageContext
            memory_entity = self._resolve_memory_entity(request.message, memory_context or {})
            write_action = self._scripted_write_action(request, memory_entity)
            if write_action and self._is_allowed_scripted_action(write_action, allowed_tool_names):
                return write_action
            if memory_entity:
                entity_kind = str(memory_entity.get("kind") or "")
                entity_id = str(memory_entity.get("entity_id") or "")
                if (
                    entity_kind == "product"
                    and entity_id.isdigit()
                    and self._is_allowed_tool("get_product_detail", allowed_tool_names)
                ):
                    return {
                        "thought": "用户使用了多轮指代，先重新读取记忆中对应商品的实时详情。",
                        "action": "get_product_detail",
                        "arguments": {"productId": int(entity_id)},
                    }
                if (
                    entity_kind == "order"
                    and entity_id
                    and self._is_allowed_tool("get_my_order_detail", allowed_tool_names)
                ):
                    arguments = {"orderSn": entity_id} if entity_id.upper().startswith("AIM") else {"orderId": int(entity_id)}
                    return {
                        "thought": "用户使用了多轮指代，先用当前登录身份重新查询记忆中的订单。",
                        "action": "get_my_order_detail",
                        "arguments": arguments,
                    }
                if (
                    entity_kind == "return"
                    and entity_id.isdigit()
                    and self._is_allowed_tool("get_return_detail", allowed_tool_names)
                ):
                    return {
                        "thought": "用户使用了多轮指代，先用当前登录身份重新查询记忆中的售后申请。",
                        "action": "get_return_detail",
                        "arguments": {"returnId": int(entity_id)},
                    }
            if intent == "ORDER_QA":
                order_sn = self._extract_order_sn(request.message)
                if order_sn:
                    return {
                        "thought": "混合订单问题必须先读取当前用户的真实订单状态，再决定是否检索政策。",
                        "action": "get_my_order_detail",
                        "arguments": {"orderSn": order_sn},
                    }
                if page_context and page_context.orderId:
                    return {
                        "thought": "混合订单问题必须先读取当前订单的真实状态，再决定是否检索政策。",
                        "action": "get_my_order_detail",
                        "arguments": {"orderId": page_context.orderId},
                    }
                return {
                    "thought": "订单问题先读取当前登录用户的订单列表，再判断是否需要补充政策依据。",
                    "action": "list_my_orders",
                    "arguments": {"limit": 5},
                }
            if intent == "PRODUCT_QA" and page_context and page_context.productId:
                return {
                    "thought": "商品与政策问题必须先读取当前商品详情和类目，再检索适用政策。",
                    "action": "get_product_detail",
                    "arguments": {"productId": page_context.productId},
                }
            if intent == "RETURN_QA":
                return_id = self._extract_return_id(request.message)
                if return_id:
                    return {
                        "thought": "售后与政策问题必须先读取当前用户的真实售后状态，再决定是否检索政策。",
                        "action": "get_return_detail",
                        "arguments": {"returnId": return_id},
                    }
            if intent == "POLICY_QA":
                return {
                    "thought": "政策问题必须先检索可引用的商城政策依据。",
                    "action": "search_policy_kb",
                    "arguments": {"keyword": request.message, "topK": 5},
                }
            return None

        last_step = steps[-1]
        last_action = last_step.get("action")
        observation = last_step.get("observation")
        if not isinstance(observation, dict):
            return None

        if last_action == "search_products" and intent in ("RECOMMENDATION", "GENERAL_QA", "PRODUCT_QA"):
            product_id = self._first_product_id_from_observation(observation)
            if product_id and not self._has_called_tool(steps, "get_product_detail", {"productId": product_id}):
                return {
                    "thought": "商品搜索已经返回候选商品，继续读取首个候选商品详情，用于生成更可靠的推荐理由。",
                    "action": "get_product_detail",
                    "arguments": {"productId": product_id},
                }
            return {
                "thought": "商品搜索没有可继续读取详情的候选商品，结束工具调用。",
                "action": "final",
                "arguments": {},
            }

        if last_action in ("list_my_orders", "get_my_order_detail", "get_order_detail") and intent == "ORDER_QA":
            if self._needs_policy_followup(request.message):
                if not self._has_called_tool(steps, "search_policy_kb", {"keyword": request.message, "topK": 5}):
                    return {
                        "thought": "订单事实已经读取完成，但用户还在询问取消、退款或售后资格，需要继续检索适用的商城政策。",
                        "action": "search_policy_kb",
                        "arguments": {"keyword": request.message, "topK": 5},
                    }
            return {
                "thought": "订单业务事实已经足够回答当前问题。",
                "action": "final",
                "arguments": {},
            }

        if last_action == "get_product_detail" and intent in ("PRODUCT_QA", "RECOMMENDATION", "GENERAL_QA"):
            if self._needs_policy_followup(request.message):
                if not self._has_called_tool(steps, "search_policy_kb", {"keyword": request.message, "topK": 5}):
                    return {
                        "thought": "商品详情已经读取完成，用户还在询问退换货、配送或其他规则，需要继续检索商品适用政策。",
                        "action": "search_policy_kb",
                        "arguments": {"keyword": request.message, "topK": 5},
                    }
            return {
                "thought": "商品事实已经足够回答当前问题。",
                "action": "final",
                "arguments": {},
            }

        if last_action in ("list_my_returns", "get_return_detail") and intent == "RETURN_QA":
            if self._needs_policy_followup(request.message):
                if not self._has_called_tool(steps, "search_policy_kb", {"keyword": request.message, "topK": 5}):
                    return {
                        "thought": "售后申请事实已经读取完成，继续检索退款、退货或运费政策以判断适用规则。",
                        "action": "search_policy_kb",
                        "arguments": {"keyword": request.message, "topK": 5},
                    }
            return {
                "thought": "售后业务事实已经足够回答当前问题。",
                "action": "final",
                "arguments": {},
            }

        if last_action in (
            "get_product_skus",
            "compare_products",
            "search_policy_kb",
            "list_my_coupons",
            "list_coupon_center",
            "list_my_addresses",
        ):
            return {
                "thought": "已获得足够的工具观察结果，可以生成最终回复。",
                "action": "final",
                "arguments": {},
            }

        return None

    @staticmethod
    def _is_allowed_tool(tool_name: str, allowed_tool_names: list[str] | None) -> bool:
        return allowed_tool_names is None or tool_name in allowed_tool_names

    def _is_allowed_scripted_action(
        self,
        action: dict[str, Any],
        allowed_tool_names: list[str] | None,
    ) -> bool:
        action_name = str(action.get("action") or "")
        return action_name == "final" or self._is_allowed_tool(action_name, allowed_tool_names)

    def _scripted_write_action(
        self,
        request: ChatRequest,
        memory_entity: dict[str, Any] | None,
    ) -> dict[str, Any] | None:
        message = (request.message or "").strip()
        page_context = request.pageContext
        if any(term in message for term in ("加入购物车", "放入购物车", "加到购物车", "加购")):
            product_id = page_context.productId if page_context and page_context.productId else None
            if product_id is None and memory_entity and memory_entity.get("kind") == "product":
                entity_id = str(memory_entity.get("entity_id") or "")
                product_id = int(entity_id) if entity_id.isdigit() else None
            explicit_product = re.search(r"商品\s*(?:ID|编号)?\s*[：:]?\s*(\d+)", message, re.IGNORECASE)
            if product_id is None and explicit_product:
                product_id = int(explicit_product.group(1))
            if product_id is not None:
                quantity_match = re.search(r"(\d+)\s*(?:件|个|台|份)", message)
                quantity = int(quantity_match.group(1)) if quantity_match else 1
                return {
                    "thought": "这是写操作，只创建加入购物车待确认草案，等待用户明确确认。",
                    "action": "add_to_cart_confirmed",
                    "arguments": {"productId": product_id, "quantity": quantity},
                }

        if "领券" in message or "领取优惠券" in message:
            coupon_match = re.search(r"(?:优惠券|券)\s*(?:ID|编号)?\s*[：:]?\s*(\d+)", message, re.IGNORECASE)
            if coupon_match:
                return {
                    "thought": "这是写操作，只创建领券待确认草案，等待用户明确确认。",
                    "action": "claim_coupon_confirmed",
                    "arguments": {"couponId": int(coupon_match.group(1))},
                }

        order_arguments: dict[str, Any] = {}
        order_sn = self._extract_order_sn(message)
        if order_sn:
            order_arguments["orderSn"] = order_sn
        elif page_context and page_context.orderId:
            order_arguments["orderId"] = page_context.orderId
        elif memory_entity and memory_entity.get("kind") == "order":
            entity_id = str(memory_entity.get("entity_id") or "")
            if entity_id.upper().startswith("AIM"):
                order_arguments["orderSn"] = entity_id
            elif entity_id.isdigit():
                order_arguments["orderId"] = int(entity_id)

        if order_arguments and any(term in message for term in ("取消订单", "取消这单", "不要这单")):
            return {
                "thought": "这是写操作，只创建取消订单待确认草案，等待用户明确确认。",
                "action": "cancel_order_confirmed",
                "arguments": order_arguments,
            }

        if order_arguments and any(term in message for term in ("申请售后", "申请退款", "我要退货", "我要退款")):
            reason_match = re.search(r"(?:原因(?:是|为)?|因为)\s*[：:]?\s*(.+)$", message)
            if reason_match and reason_match.group(1).strip():
                arguments = dict(order_arguments)
                arguments["reason"] = reason_match.group(1).strip()
                return {
                    "thought": "这是写操作，只创建申请售后待确认草案，等待用户明确确认。",
                    "action": "apply_return_confirmed",
                    "arguments": arguments,
                }
        return None

    def _resolve_memory_entity(self, message: str, memory_context: dict[str, Any]) -> dict[str, Any] | None:
        entities = memory_context.get("entities")
        if not isinstance(entities, list) or not entities:
            return None
        normalized = (message or "").strip()
        ordinal_match = re.search(
            r"第\s*([一二三四五六七八九十\d]+)\s*(个|款|台|件|条)?",
            normalized,
        )
        entity_cues = (
            "商品", "产品", "订单", "售后", "推荐", "候选", "详情", "价格", "库存", "那个", "怎么样",
        )
        unit = ordinal_match.group(2) if ordinal_match else None
        is_entity_ordinal = bool(
            ordinal_match
            and (unit in ("个", "款", "台", "件") or any(cue in normalized for cue in entity_cues))
        )
        if ordinal_match and is_entity_ordinal:
            ordinal = self._parse_ordinal(ordinal_match.group(1))
            if ordinal:
                for entity in entities:
                    if isinstance(entity, dict) and int(entity.get("ordinal") or 0) == ordinal:
                        return entity
        reference_terms = (
            "它", "这个", "那个", "刚才", "上一个", "上一款", "该商品", "这款",
            "该订单", "这个订单", "这单", "该售后", "这个售后",
        )
        if any(term in normalized for term in reference_terms):
            return next((entity for entity in entities if isinstance(entity, dict)), None)
        return None

    def _parse_ordinal(self, value: str) -> int | None:
        if value.isdigit():
            return int(value)
        mapping = {
            "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
            "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
        }
        return mapping.get(value)

    def _needs_policy_followup(self, message: str) -> bool:
        policy_terms = (
            "能退",
            "可以退",
            "能退款",
            "可以退款",
            "怎么退",
            "退货",
            "退款",
            "换货",
            "售后政策",
            "售后规则",
            "售后资格",
            "能取消",
            "可以取消",
            "取消订单",
            "运费",
            "多久",
            "什么时候",
            "时效",
            "自动关闭",
            "发货",
            "物流",
            "长时间没更新",
            "恢复",
            "到账",
            "审核通过后",
            "规则",
            "政策",
        )
        normalized = (message or "").strip()
        return any(term in normalized for term in policy_terms)

    def _parse_json(self, raw: str) -> dict[str, Any] | None:
        text = raw.strip()
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if match:
            text = match.group(0)
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            return None

        action = payload.get("action")
        if not isinstance(action, str):
            return None
        arguments = payload.get("arguments")
        if not isinstance(arguments, dict):
            payload["arguments"] = {}
        if "thought" not in payload:
            payload["thought"] = ""
        return payload

    def _normalize_action(self, action: dict[str, Any], request: ChatRequest) -> dict[str, Any]:
        if action.get("action") == "search_products":
            arguments = action.setdefault("arguments", {})
            keyword = str(arguments.get("keyword") or "").strip()
            arguments["keyword"] = self._clean_product_keyword(keyword or request.message)
            if arguments.get("maxPrice") is None:
                arguments["maxPrice"] = self._extract_budget(request.message)
            arguments.setdefault("inStock", True)
            arguments.setdefault("limit", 10)

        if action.get("action") in ("get_product_detail", "get_product_skus"):
            arguments = action.setdefault("arguments", {})
            if not arguments.get("productId") and request.pageContext and request.pageContext.productId:
                arguments["productId"] = request.pageContext.productId

        if action.get("action") in ("get_my_order_detail", "get_order_detail"):
            arguments = action.setdefault("arguments", {})
            raw_order_id = arguments.get("orderId")
            if raw_order_id is not None and not str(raw_order_id).strip().isdigit() and not arguments.get("orderSn"):
                arguments["orderSn"] = str(raw_order_id).strip()
                arguments.pop("orderId", None)
            if not arguments.get("orderId") and request.pageContext and request.pageContext.orderId:
                arguments["orderId"] = request.pageContext.orderId
            if not arguments.get("orderId") and not arguments.get("orderSn"):
                order_sn = self._extract_order_sn(request.message)
                if order_sn:
                    arguments["orderSn"] = order_sn

        if action.get("action") == "list_my_orders":
            arguments = action.setdefault("arguments", {})
            arguments.setdefault("limit", 5)

        if action.get("action") == "get_return_detail":
            arguments = action.setdefault("arguments", {})
            if not arguments.get("returnId"):
                return_id = self._extract_return_id(request.message)
                if return_id:
                    arguments["returnId"] = return_id

        return action

    def _fallback_action(self, request: ChatRequest, intent: str, steps: list[dict[str, Any]]) -> dict[str, Any]:
        if steps:
            return {"thought": "已有工具观察结果，结束工具调用。", "action": "final", "arguments": {}}

        page_context = request.pageContext
        compare_ids = self._extract_product_ids(request.message)
        if len(compare_ids) >= 2 and self._looks_like_compare(request.message):
            return {
                "thought": "用户需要比较多个商品，先读取多个商品详情。",
                "action": "compare_products",
                "arguments": {"productIds": compare_ids[:5]},
            }
        if intent in ("RECOMMENDATION", "GENERAL_QA"):
            return {
                "thought": "用户可能在查找商品，先用商品搜索工具查询真实商品。",
                "action": "search_products",
                "arguments": {
                    "keyword": self._clean_product_keyword(request.message),
                    "maxPrice": self._extract_budget(request.message),
                    "inStock": True,
                    "limit": 10,
                },
            }
        if intent == "PRODUCT_QA" and page_context and page_context.productId:
            return {
                "thought": "用户在商品详情页提问，先读取当前商品详情。",
                "action": "get_product_detail",
                "arguments": {"productId": page_context.productId},
            }
        if intent == "POLICY_QA":
            return {
                "thought": "用户询问平台规则，先检索知识库。",
                "action": "search_policy_kb",
                "arguments": {"keyword": request.message, "topK": 5},
            }
        if intent == "ORDER_QA" and page_context and page_context.orderId:
            return {
                "thought": "用户在订单详情页提问，先读取当前订单详情。",
                "action": "get_my_order_detail",
                "arguments": {"orderId": page_context.orderId},
            }
        order_sn = self._extract_order_sn(request.message)
        if intent == "ORDER_QA" and order_sn:
            return {
                "thought": "用户提供了明确的订单号，先按订单号读取当前登录用户的订单详情。",
                "action": "get_my_order_detail",
                "arguments": {"orderSn": order_sn},
            }
        if intent == "ORDER_QA":
            return {
                "thought": "用户在询问自己的订单，先读取当前登录用户的订单列表。",
                "action": "list_my_orders",
                "arguments": {"limit": 5},
            }
        if intent == "COUPON_QA":
            if any(keyword in request.message for keyword in ("领券", "可领取", "领券中心", "券中心")):
                return {
                    "thought": "用户在询问可领取优惠券，先读取领券中心列表。",
                    "action": "list_coupon_center",
                    "arguments": {},
                }
            return {
                "thought": "用户在询问自己的优惠券，先读取当前登录用户的优惠券列表。",
                "action": "list_my_coupons",
                "arguments": {},
            }
        if intent == "RETURN_QA":
            return_id = self._extract_return_id(request.message)
            if return_id:
                return {
                    "thought": "用户提供了售后申请 ID，先读取售后详情。",
                    "action": "get_return_detail",
                    "arguments": {"returnId": return_id},
                }
            return {
                "thought": "用户在询问自己的售后申请，先读取当前登录用户的售后列表。",
                "action": "list_my_returns",
                "arguments": {},
            }
        if intent == "ADDRESS_QA":
            return {
                "thought": "用户在询问自己的收货地址，先读取当前登录用户的地址列表。",
                "action": "list_my_addresses",
                "arguments": {},
            }
        return {"thought": "当前问题不需要工具。", "action": "final", "arguments": {}}

    def _compact_observation(self, record: ToolCallRecord) -> dict[str, Any]:
        payload = record.model_dump()
        result = payload.get("result")
        if isinstance(result, list):
            payload["resultCount"] = len(result)
            payload["resultPreview"] = result[:3]
            payload.pop("result", None)
        return payload

    def _should_stop_after_tool(self, tool_name: str, record: ToolCallRecord) -> bool:
        if not record.ok:
            return True
        if tool_name == "search_products":
            result = record.result
            return not isinstance(result, list) or len(result) == 0
        if tool_name in (
            "get_product_skus",
            "compare_products",
            "search_policy_kb",
            "list_my_coupons",
            "list_coupon_center",
            "list_my_addresses",
            "add_to_cart_confirmed",
            "claim_coupon_confirmed",
            "cancel_order_confirmed",
            "apply_return_confirmed",
        ):
            return True
        return False

    def _first_product_id_from_observation(self, observation: dict[str, Any]) -> int | None:
        preview = observation.get("resultPreview")
        if not isinstance(preview, list):
            return None
        for item in preview:
            if not isinstance(item, dict):
                continue
            product_id = item.get("productId") or item.get("id")
            if product_id is not None:
                return int(product_id)
        return None

    def _has_called_tool(self, steps: list[dict[str, Any]], tool_name: str, arguments: dict[str, Any]) -> bool:
        for step in steps:
            if step.get("action") != tool_name:
                continue
            step_args = step.get("arguments")
            if not isinstance(step_args, dict):
                continue
            if self._canonical_arguments(step_args) == self._canonical_arguments(arguments):
                return True
        return False

    def _canonical_arguments(self, arguments: dict[str, Any]) -> str:
        return json.dumps(arguments, ensure_ascii=False, sort_keys=True, default=str)

    def _clean_product_keyword(self, message: str) -> str:
        keyword = re.sub(r"\d+(?:\.\d+)?\s*(?:元|块)?\s*(?:以内|以下|预算)?", "", message)
        for word in STOPWORDS:
            keyword = keyword.replace(word, " ")
        keyword = re.sub(r"[,，。！？.!?：:；;（）()【】\[\]\"']", " ", keyword)
        keyword = re.sub(r"\s+", " ", keyword).strip()
        return keyword or message.strip()

    def _extract_budget(self, message: str) -> float | None:
        match = re.search(r"(\d+(?:\.\d+)?)\s*(?:元|块)?\s*(?:以内|以下|预算)?", message)
        if not match:
            return None
        return float(match.group(1))

    def _extract_product_ids(self, message: str) -> list[int]:
        ids = [int(item) for item in re.findall(r"(?:商品|productId|id|ID)?\s*#?(\d{1,10})", message)]
        result: list[int] = []
        for product_id in ids:
            if product_id not in result:
                result.append(product_id)
        return result

    def _looks_like_compare(self, message: str) -> bool:
        return any(keyword in message for keyword in ("对比", "比较", "哪个更好", "哪款更好", "区别"))

    def _extract_order_sn(self, message: str) -> str | None:
        match = re.search(r"\bAIM\d{8,}\b", message, flags=re.IGNORECASE)
        if not match:
            return None
        return match.group(0).upper()

    def _extract_return_id(self, message: str) -> int | None:
        match = re.search(
            r"(?:售后(?:单|申请)?|退款(?:单|申请)?|退货(?:单|申请)?|returnId|ID|id)\s*#?(\d{1,10})",
            message,
        )
        if not match:
            return None
        return int(match.group(1))

    def _guard_tool_call(
        self,
        tool_name: str,
        arguments: dict[str, Any],
        request: ChatRequest,
        steps: list[dict[str, Any]],
        tool_calls: list[ToolCallRecord],
        candidate_tools: list[str] | None = None,
    ) -> str | None:
        if tool_name == "final":
            return None
        tool = get_tool_definition(tool_name)
        if tool is None:
            return "工具不存在或未注册"
        if candidate_tools is not None and tool_name not in candidate_tools:
            return f"工具 {tool_name} 不在当前意图的候选工具列表中"
        if len(tool_calls) >= MAX_TOOL_CALLS:
            return f"工具调用次数超过上限 {MAX_TOOL_CALLS}"
        if self._has_called_tool(steps[:-1], tool_name, arguments):
            return f"检测到重复工具调用，已阻止：{tool_name}"
        if "userId" in arguments or "memberId" in arguments:
            return "订单工具不能接收模型传入的用户 ID"
        if tool_name == "compare_products":
            product_ids = arguments.get("productIds")
            if not isinstance(product_ids, list) or len(product_ids) < 2:
                return "商品对比至少需要 2 个商品 ID"
            if len(product_ids) > 5:
                return "商品对比最多允许 5 个商品 ID"
        if tool_name in ("get_my_order_detail", "get_order_detail"):
            page_context = request.pageContext
            order_id = arguments.get("orderId")
            order_sn = arguments.get("orderSn")
            if not order_id and not order_sn:
                return "订单详情工具必须提供 orderId 或 orderSn"
            if page_context and page_context.orderId and order_id and int(order_id) != int(page_context.orderId):
                return "订单详情工具只能查询当前页面订单"
        if tool_name == "get_return_detail" and not arguments.get("returnId"):
            return "售后详情工具必须提供 returnId"
        if tool_name == "add_to_cart_confirmed" and not arguments.get("productId"):
            return "加入购物车确认工具必须提供 productId"
        if tool_name == "claim_coupon_confirmed" and not arguments.get("couponId"):
            return "领券确认工具必须提供 couponId"
        if tool_name in ("cancel_order_confirmed", "apply_return_confirmed"):
            if not arguments.get("orderId") and not arguments.get("orderSn"):
                return "订单写操作必须提供 orderId 或 orderSn"
        if tool_name == "apply_return_confirmed" and not str(arguments.get("reason") or "").strip():
            return "申请售后确认工具必须提供 reason"
        return None


react_agent = ReActAgent()
