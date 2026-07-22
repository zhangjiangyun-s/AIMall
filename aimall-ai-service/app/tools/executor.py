import asyncio
import time
from typing import Any

from app.actions.pending_store import pending_action_store
from app.guardrails import GuardrailAction, guardrail_service
from app.schemas.tool_schema import ToolCallRecord
from app.tools.java_client import java_client
from app.tools.registry import get_tool_definition
from app.runtime import runtime_capabilities
from app.state.redis_backend import AiStateUnavailableError


class ToolExecutor:
    async def execute(
        self,
        name: str,
        arguments: dict[str, Any],
        trace_id: str,
        auth_token: str | None = None,
        page_context: dict[str, Any] | None = None,
        session_id: str = "",
        user_id: int | None = None,
        tenant_id: str = "default",
    ) -> ToolCallRecord:
        tool = get_tool_definition(name)
        start = time.perf_counter()
        tool_guardrail = guardrail_service.evaluate_tool_call(tool, arguments, auth_token)
        if tool_guardrail.action == GuardrailAction.BLOCK:
            return ToolCallRecord(
                name=name,
                arguments=tool_guardrail.sanitizedArguments,
                ok=False,
                error=self._guardrail_error(tool_guardrail),
                traceId=trace_id,
                guardrail=tool_guardrail.public_summary(),
            )
        arguments = tool_guardrail.sanitizedArguments

        try:
            if tool.requiresConfirmation:
                runtime_capabilities.assert_write_allowed()
                result = await self._create_pending_action(
                    name,
                    arguments,
                    auth_token,
                    session_id,
                    trace_id,
                    user_id,
                    tenant_id,
                )
            else:
                simulated = runtime_capabilities.simulated_read_result(name, arguments)
                result = simulated if simulated is not None else await asyncio.wait_for(
                    self._dispatch(name, arguments, auth_token, page_context or {}), timeout=tool.timeoutSeconds
                )
            return ToolCallRecord(
                name=name,
                arguments=arguments,
                ok=True,
                result=result,
                latencyMs=self._latency_ms(start),
                traceId=trace_id,
                guardrail=tool_guardrail.public_summary() if tool_guardrail.findings else None,
            )
        except AiStateUnavailableError:
            raise
        except Exception as exc:
            return ToolCallRecord(
                name=name,
                arguments=arguments,
                ok=False,
                error=str(exc),
                latencyMs=self._latency_ms(start),
                traceId=trace_id,
                guardrail=tool_guardrail.public_summary() if tool_guardrail.findings else None,
            )

    def _guardrail_error(self, decision) -> str:
        messages = [finding.message for finding in decision.findings]
        return messages[0] if messages else "工具调用未通过安全检查"

    async def _create_pending_action(
        self,
        name: str,
        arguments: dict[str, Any],
        auth_token: str | None,
        session_id: str,
        trace_id: str,
        user_id: int | None,
        tenant_id: str,
    ) -> dict[str, Any]:
        if not auth_token:
            raise PermissionError("确认式写操作需要登录 token")
        if not session_id:
            raise ValueError("确认式写操作缺少 sessionId")
        action_type: str
        normalized: dict[str, Any]
        title: str
        summary: str
        if name == "add_to_cart_confirmed":
            product_id = self._required_int(arguments, "productId")
            quantity = self._optional_int(arguments, "quantity", default=1) or 1
            if quantity < 1 or quantity > 99:
                raise ValueError("加入购物车数量必须在 1 到 99 之间")
            product = await java_client.get_product_detail(product_id)
            if not product:
                raise ValueError("商品不存在或已下架")
            normalized = {"productId": product_id, "quantity": quantity}
            sku_id = self._optional_int(arguments, "productSkuId")
            if sku_id is not None:
                normalized["productSkuId"] = sku_id
            action_type, title = "ADD_TO_CART", "加入购物车"
            summary = f"{product.get('name') or f'商品 {product_id}'} x {quantity}"
        elif name == "claim_coupon_confirmed":
            coupon_id = self._required_int(arguments, "couponId")
            coupons = await java_client.list_coupon_center(auth_token)
            coupon = next((item for item in coupons if int(item.get("couponId") or 0) == coupon_id), None)
            if coupon is None:
                raise ValueError("优惠券不存在或当前不可见")
            if coupon.get("claimed"):
                raise ValueError("该优惠券已经领取")
            if coupon.get("active") is False:
                raise ValueError("该优惠券当前不可领取")
            normalized = {"couponId": coupon_id}
            action_type, title = "CLAIM_COUPON", "领取优惠券"
            summary = str(coupon.get("name") or f"优惠券 {coupon_id}")
        elif name in ("cancel_order_confirmed", "apply_return_confirmed"):
            order = await java_client.get_order_detail(self._required_order_ref(arguments), auth_token)
            if not order:
                raise ValueError("订单不存在或不属于当前用户")
            order_id = int(order.get("orderId") or order.get("id") or 0)
            if order_id <= 0:
                raise ValueError("订单详情缺少 orderId")
            normalized = {"orderId": order_id, "orderSn": str(order.get("orderSn") or "")}
            if name == "cancel_order_confirmed":
                action_type, title = "CANCEL_ORDER", "取消订单"
                summary = f"取消订单 {normalized['orderSn'] or order_id}"
            else:
                reason = str(arguments.get("reason") or "").strip()
                if not reason:
                    raise ValueError("申请售后必须填写原因")
                normalized["reason"] = reason[:200]
                description = str(arguments.get("description") or "").strip()
                if description:
                    normalized["description"] = description[:500]
                action_type, title = "APPLY_RETURN", "申请售后"
                summary = f"订单 {normalized['orderSn'] or order_id}：{reason[:60]}"
        else:
            raise RuntimeError("确认工具未实现")
        return {
            "pendingAction": await pending_action_store.create(
                action_type=action_type,
                arguments=normalized,
                title=title,
                summary=summary,
                session_id=session_id,
                auth_token=auth_token,
                trace_id=trace_id,
                user_id=user_id,
                tenant_id=tenant_id,
            )
        }

    async def _dispatch(
        self,
        name: str,
        arguments: dict[str, Any],
        auth_token: str | None,
        page_context: dict[str, Any],
    ) -> Any:
        if name == "search_products":
            return await java_client.search_products(
                keyword=self._optional_str(arguments, "keyword"),
                category_id=self._optional_int(arguments, "categoryId"),
                min_price=self._optional_float(arguments, "minPrice"),
                max_price=self._optional_float(arguments, "maxPrice"),
                in_stock=self._optional_bool(arguments, "inStock", default=True),
                limit=self._optional_int(arguments, "limit", default=10),
            )
        if name == "get_product_detail":
            return await java_client.get_product_detail(self._required_int(arguments, "productId"))
        if name == "get_product_skus":
            return await java_client.get_product_skus(self._required_int(arguments, "productId"))
        if name == "compare_products":
            return await java_client.compare_products(
                self._required_int_list(arguments, "productIds", min_size=2, max_size=5)
            )
        if name == "search_policy_kb":
            return await java_client.search_policy_kb(
                keyword=self._optional_str(arguments, "keyword"),
                top_k=self._optional_int(arguments, "topK", default=5),
                source_type=self._optional_str(arguments, "sourceType"),
                auth_token=auth_token,
                category_id=self._context_int(page_context, "categoryId"),
            )
        if name == "list_my_orders":
            if not auth_token:
                raise PermissionError("订单列表工具需要登录 token")
            return await java_client.list_my_orders(
                auth_token,
                status=self._optional_int(arguments, "status"),
                limit=self._optional_int(arguments, "limit", default=5),
            )
        if name in ("get_my_order_detail", "get_order_detail"):
            if not auth_token:
                raise PermissionError("订单工具需要登录 token")
            return await java_client.get_order_detail(self._required_order_ref(arguments), auth_token)
        if name == "list_my_coupons":
            if not auth_token:
                raise PermissionError("优惠券工具需要登录 token")
            return await java_client.list_my_coupons(auth_token)
        if name == "list_coupon_center":
            if not auth_token:
                raise PermissionError("领券中心工具需要登录 token")
            return await java_client.list_coupon_center(auth_token)
        if name == "list_my_returns":
            if not auth_token:
                raise PermissionError("售后工具需要登录 token")
            return await java_client.list_my_returns(auth_token)
        if name == "get_return_detail":
            if not auth_token:
                raise PermissionError("售后详情工具需要登录 token")
            return await java_client.get_return_detail(self._required_int(arguments, "returnId"), auth_token)
        if name == "list_my_addresses":
            if not auth_token:
                raise PermissionError("地址工具需要登录 token")
            return await java_client.list_my_addresses(auth_token)
        raise RuntimeError("工具未实现")

    def _required_int(self, arguments: dict[str, Any], key: str) -> int:
        value = arguments.get(key)
        if value is None:
            raise ValueError(f"缺少必填参数：{key}")
        return int(value)

    def _required_order_ref(self, arguments: dict[str, Any]) -> int | str:
        order_id = arguments.get("orderId")
        if order_id is not None and str(order_id).strip():
            return int(order_id)
        order_sn = arguments.get("orderSn")
        if order_sn is not None and str(order_sn).strip():
            return str(order_sn).strip()
        raise ValueError("缺少必填参数：orderId 或 orderSn")

    def _required_int_list(self, arguments: dict[str, Any], key: str, min_size: int, max_size: int) -> list[int]:
        value = arguments.get(key)
        if not isinstance(value, list):
            raise ValueError(f"缺少必填数组参数：{key}")
        result = [int(item) for item in value]
        if len(result) < min_size:
            raise ValueError(f"{key} 至少需要 {min_size} 个 ID")
        if len(result) > max_size:
            raise ValueError(f"{key} 最多允许 {max_size} 个 ID")
        return result

    def _optional_int(self, arguments: dict[str, Any], key: str, default: int | None = None) -> int | None:
        value = arguments.get(key)
        return default if value is None else int(value)

    def _optional_float(self, arguments: dict[str, Any], key: str) -> float | None:
        value = arguments.get(key)
        return None if value is None else float(value)

    def _optional_bool(self, arguments: dict[str, Any], key: str, default: bool) -> bool:
        value = arguments.get(key)
        if value is None:
            return default
        if isinstance(value, bool):
            return value
        return str(value).strip().lower() in ("1", "true", "yes", "y", "是")

    def _optional_str(self, arguments: dict[str, Any], key: str) -> str:
        value = arguments.get(key)
        return "" if value is None else str(value).strip()

    def _context_int(self, context: dict[str, Any], key: str) -> int | None:
        value = context.get(key)
        return None if value is None else int(value)

    def _latency_ms(self, start: float) -> int:
        return int((time.perf_counter() - start) * 1000)


tool_executor = ToolExecutor()
