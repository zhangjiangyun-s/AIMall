import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  route: { name: "Home", params: {} as Record<string, string>, query: {} as Record<string, string> },
  router: { push: vi.fn(), replace: vi.fn() },
  login: vi.fn(),
  fetchCartList: vi.fn(),
  updateCartItem: vi.fn(),
  deleteCartItem: vi.fn(),
  fetchOrderById: vi.fn(),
  cancelOrder: vi.fn(),
  confirmReceiveOrder: vi.fn(),
  fetchPayStatus: vi.fn(),
  createAlipayPayment: vi.fn(),
  fetchLatestReturnByOrder: vi.fn(),
  applyReturn: vi.fn(),
  fetchReturnById: vi.fn(),
  cancelReturn: vi.fn(),
  sendAiMessageStream: vi.fn(),
  sendAiFeedback: vi.fn(),
  submitAiPendingAction: vi.fn(),
  getAiPendingActionStatus: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}));
vi.mock("../api/userApi", () => ({ login: mocks.login }));
vi.mock("../api/cartApi", () => ({
  fetchCartList: mocks.fetchCartList,
  updateCartItem: mocks.updateCartItem,
  deleteCartItem: mocks.deleteCartItem,
}));
vi.mock("../api/orderApi", () => ({
  fetchOrderById: mocks.fetchOrderById,
  cancelOrder: mocks.cancelOrder,
  confirmReceiveOrder: mocks.confirmReceiveOrder,
}));
vi.mock("../api/payApi", () => ({
  fetchPayStatus: mocks.fetchPayStatus,
  createAlipayPayment: mocks.createAlipayPayment,
}));
vi.mock("../api/returnApi", () => ({
  fetchLatestReturnByOrder: mocks.fetchLatestReturnByOrder,
  applyReturn: mocks.applyReturn,
  fetchReturnById: mocks.fetchReturnById,
  cancelReturn: mocks.cancelReturn,
}));
vi.mock("../api/aiApi", () => ({
  sendAiMessageStream: mocks.sendAiMessageStream,
  sendAiFeedback: mocks.sendAiFeedback,
  submitAiPendingAction: mocks.submitAiPendingAction,
  getAiPendingActionStatus: mocks.getAiPendingActionStatus,
}));

import LoginView from "../views/LoginView.vue";
import CartView from "../views/CartView.vue";
import OrderDetailView from "../views/OrderDetailView.vue";
import ReturnDetailView from "../views/ReturnDetailView.vue";
import AiAssistantDrawer from "../components/ai/AiAssistantDrawer.vue";

describe("stage 10 customer workflows", () => {
  beforeEach(() => {
    mocks.route.name = "Home";
    mocks.route.params = {};
    mocks.route.query = {};
    mocks.getAiPendingActionStatus.mockResolvedValue({ code: 0, data: {} });
  });

  it("stores a successful login session and redirects home", async () => {
    mocks.login.mockResolvedValue({
      data: { code: 0, data: { token: "token-1", userInfo: { id: 7, username: "buyer" } } },
    });
    const wrapper = mount(LoginView, {
      global: { stubs: { AuthLayout: { template: "<main><slot /></main>" } } },
    });

    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("buyer");
    await inputs[1].setValue("secret");
    await wrapper.find("form").trigger("submit");
    await flushPromises();

    expect(mocks.login).toHaveBeenCalledWith({ username: "buyer", password: "secret" });
    expect(localStorage.getItem("token")).toBe("token-1");
    expect(localStorage.getItem("aimall_login_session_id")).toMatch(/^login_/);
    expect(mocks.router.replace).toHaveBeenCalledWith("/");
  });

  it("loads cart data and persists a quantity increment", async () => {
    mocks.fetchCartList.mockResolvedValue({
      data: { code: 0, data: [{ id: 1, productId: 10, productName: "Laptop", productPrice: 99, quantity: 1 }] },
    });
    mocks.updateCartItem.mockResolvedValue({ data: { code: 0 } });
    const wrapper = mount(CartView);
    await flushPromises();

    expect(wrapper.findAll(".cart-item")).toHaveLength(1);
    await wrapper.findAll(".qty-control button")[1].trigger("click");
    await flushPromises();

    expect(mocks.updateCartItem).toHaveBeenCalledWith({ cartItemId: 1, quantity: 2 });
    expect(wrapper.find(".qty-control span").text()).toBe("2");
  });

  it("renders channel payment state independently from order state", async () => {
    mocks.route.name = "OrderDetail";
    mocks.route.params = { id: "15" };
    mocks.fetchOrderById.mockResolvedValue({
      data: { code: 0, data: { orderId: 15, orderNo: "AIM-15", status: "WAIT_SHIP", statusText: "Paid", totalAmount: 99, createTime: "2026-07-19 10:00:00", items: [] } },
    });
    mocks.fetchPayStatus.mockResolvedValue({
      data: { code: 0, data: { orderId: 15, payStatus: "PAID", transactionNo: "ALI-15" } },
    });
    mocks.fetchLatestReturnByOrder.mockResolvedValue({ data: { code: 0, data: null } });
    const wrapper = mount(OrderDetailView);
    await flushPromises();

    expect(wrapper.text()).toContain("PAID");
    expect(wrapper.text()).toContain("ALI-15");
  });

  it("renders the terminal return state returned by the backend", async () => {
    mocks.route.name = "ReturnDetail";
    mocks.route.params = { id: "21" };
    mocks.fetchReturnById.mockResolvedValue({
      data: { code: 0, data: { id: 21, orderId: 15, orderNo: "AIM-15", type: "REFUND", status: "REFUNDED", statusText: "Refunded", reason: "damaged", returnAmount: 99 } },
    });
    const wrapper = mount(ReturnDetailView);
    await flushPromises();

    expect(wrapper.find(".status-badge").classes()).toContain("REFUNDED");
    expect(wrapper.text()).toContain("Refunded");
  });

  it("aborts an in-flight SSE request when the drawer closes", async () => {
    let observedSignal: AbortSignal | undefined;
    mocks.sendAiMessageStream.mockImplementation((...args: unknown[]) => {
      observedSignal = args[6] as AbortSignal;
      return new Promise<void>((resolve) => observedSignal?.addEventListener("abort", () => resolve(), { once: true }));
    });
    const wrapper = mount(AiAssistantDrawer);
    await wrapper.find(".ai-trigger").trigger("click");
    await wrapper.find(".input-area input").setValue("status");
    await wrapper.find(".send-btn").trigger("click");
    await flushPromises();

    expect(observedSignal?.aborted).toBe(false);
    await wrapper.find(".close-btn").trigger("click");
    await flushPromises();
    expect(observedSignal?.aborted).toBe(true);
  });
});
