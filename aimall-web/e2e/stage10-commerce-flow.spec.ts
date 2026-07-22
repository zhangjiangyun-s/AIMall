import { expect, test, type Page, type Route } from "@playwright/test";

type WorkflowState = {
  orderStatus: "WAIT_PAY" | "WAIT_SHIP" | "SHIPPED" | "COMPLETED";
  returnStatus: null | "REFUNDING" | "REFUNDED";
};

const ok = (data: unknown) => ({ code: 0, message: "success", data });

async function installMockBackend(page: Page, state: WorkflowState) {
  const cart = [{ id: 31, productId: 1001, productName: "Stage 10 Laptop", productPrice: 99, quantity: 1 }];
  await page.route("http://127.0.0.1:4175/api/**", async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    let body: unknown = null;

    if (path === "/api/user/email/code" || path === "/api/user/register") body = ok({ cooldownSeconds: 60 });
    else if (path === "/api/user/login") body = ok({ token: "e2e-token", userInfo: { id: 7, username: "stage10" } });
    else if (path === "/api/products/1001") body = ok({ productId: 1001, name: "Stage 10 Laptop", price: 99, stock: 5, category: "Digital", skuStocks: [] });
    else if (path === "/api/cart/add") body = ok(null);
    else if (path === "/api/cart/list") body = ok(cart);
    else if (path === "/api/user/addresses") body = ok([{ id: 1, name: "Buyer", phone: "13800000000", fullAddress: "Shanghai", defaultStatus: 1 }]);
    else if (path === "/api/user/coupons/available") body = ok([]);
    else if (path === "/api/orders/preview") body = ok({ goodsAmount: 99, freightAmount: 0, couponAmount: 0, discountAmount: 0, payAmount: 99, availableCoupons: [] });
    else if (path === "/api/orders/create") body = ok({ orderId: 15, orderSn: "AIM-E2E-15" });
    else if (path === "/api/orders/15/confirm-receive") {
      state.orderStatus = "COMPLETED";
      body = ok({ message: "received" });
    } else if (path === "/api/orders/15") {
      body = ok({
        orderId: 15,
        orderNo: "AIM-E2E-15",
        status: state.orderStatus,
        statusText: state.orderStatus,
        totalAmount: 99,
        payAmount: 99,
        createTime: "2026-07-19 10:00:00",
        paymentTime: state.orderStatus === "WAIT_PAY" ? null : "2026-07-19 10:01:00",
        deliveryTime: ["SHIPPED", "COMPLETED"].includes(state.orderStatus) ? "2026-07-19 10:02:00" : null,
        receiveTime: state.orderStatus === "COMPLETED" ? "2026-07-19 10:03:00" : null,
        items: [{ productId: 1001, productName: "Stage 10 Laptop", quantity: 1, price: 99 }],
      });
    } else if (path === "/api/pay/status/15") {
      body = ok({ orderId: 15, payStatus: state.orderStatus === "WAIT_PAY" ? "PENDING" : "PAID", transactionNo: state.orderStatus === "WAIT_PAY" ? "" : "ALI-E2E-15" });
    } else if (path === "/api/pay/alipay/create") {
      body = ok({ orderId: 15, form: '<form action="/mock-alipay" method="get"></form>' });
    } else if (path === "/api/returns/order/15") {
      body = ok(state.returnStatus ? { id: 21, orderId: 15, orderNo: "AIM-E2E-15", type: "REFUND", status: state.returnStatus, statusText: state.returnStatus, reason: "damaged", returnAmount: 99 } : null);
    } else if (path === "/api/returns/apply") {
      state.returnStatus = "REFUNDING";
      body = ok({ id: 21, orderId: 15, orderNo: "AIM-E2E-15", type: "REFUND", status: "REFUNDING", statusText: "REFUNDING", reason: "damaged", returnAmount: 99 });
    } else if (path === "/api/returns/21") {
      body = ok({ id: 21, orderId: 15, orderNo: "AIM-E2E-15", type: "REFUND", status: state.returnStatus, statusText: state.returnStatus, reason: "damaged", returnAmount: 99 });
    } else body = ok([]);

    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.route("**/mock-alipay", async (route) => {
    state.orderStatus = "WAIT_SHIP";
    await route.fulfill({ status: 200, contentType: "text/html", body: "<h1>Sandbox payment accepted</h1>" });
  });
}

test("register to refund workflow remains coherent across external transitions", async ({ page }) => {
  const state: WorkflowState = { orderStatus: "WAIT_PAY", returnStatus: null };
  await installMockBackend(page, state);

  await page.goto("/register");
  const inputs = page.locator(".register-form input");
  await inputs.nth(0).fill("stage10");
  await inputs.nth(1).fill("Stage 10 Buyer");
  await inputs.nth(2).fill("stage10@example.com");
  await inputs.nth(3).fill("123456");
  await inputs.nth(4).fill("StrongPass!1");
  await inputs.nth(5).fill("StrongPass!1");
  await inputs.nth(6).check();
  await page.locator(".register-form form").evaluate((form: HTMLFormElement) => form.requestSubmit());
  await expect(page).toHaveURL(/\/$/);

  await page.goto("/products/1001");
  await expect(page.locator(".product-name")).toHaveText("Stage 10 Laptop");
  await page.locator(".btn-cart").click();
  await page.goto("/cart");
  await expect(page.locator(".cart-item")).toHaveCount(1);
  await page.locator(".checkout-btn").click();
  await expect(page).toHaveURL(/\/checkout$/);
  await page.locator(".submit-btn").click();
  await expect(page).toHaveURL(/\/orders\/15$/);

  await page.locator(".action-row .primary").click();
  await expect(page).toHaveURL(/\/mock-alipay$/);
  state.orderStatus = "WAIT_SHIP";
  await page.goto("/orders/15");
  await expect(page.locator("body")).toContainText("ALI-E2E-15");

  state.orderStatus = "SHIPPED";
  await page.reload();
  await page.locator(".action-row .primary").click();
  await expect(page.locator(".order-status")).toContainText("COMPLETED");

  await page.locator(".return-form textarea").fill("package damaged");
  await page.locator(".return-form button").click();
  await expect(page.locator(".return-summary")).toContainText("REFUNDING");

  state.returnStatus = "REFUNDED";
  await page.goto("/returns/21");
  await expect(page.locator(".status-badge")).toContainText("REFUNDED");
});
