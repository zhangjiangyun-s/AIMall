class JavaClient:
    """Java 后端 HTTP 客户端骨架，本轮只返回 mock 数据。"""

    async def get_product_detail(self, product_id: int):
        return None

    async def get_current_user_orders(self):
        return []

    async def search_products(self, keyword: str):
        return []


java_client = JavaClient()
