package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "管理端 Mock")
@RestController
@RequestMapping("/api/admin")
public class AdminMockController {

    @Operation(summary = "管理端商品列表")
    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> products() {
        List<Map<String, Object>> products = List.of(
                Map.of(
                        "id", 1001,
                        "name", "学习平板 A1",
                        "category", "平板电脑",
                        "price", 2999,
                        "status", "上架"
                ),
                Map.of(
                        "id", 1002,
                        "name", "轻薄笔记本 B2",
                        "category", "笔记本电脑",
                        "price", 3999,
                        "status", "上架"
                ),
                Map.of(
                        "id", 1003,
                        "name", "无线蓝牙耳机 C3",
                        "category", "耳机",
                        "price", 399,
                        "status", "上架"
                )
        );
        return ApiResponse.success(products);
    }

    @Operation(summary = "管理端知识库文档列表")
    @GetMapping("/knowledge-docs")
    public ApiResponse<List<Map<String, Object>>> knowledgeDocs() {
        List<Map<String, Object>> docs = List.of(
                Map.of(
                        "id", 1,
                        "title", "退货规则",
                        "sourceType", "POLICY",
                        "content", "消费者有权在收货后 7 天内无理由退货",
                        "status", "已启用",
                        "version", 1,
                        "updatedAt", "2026-06-01 10:00:00"
                ),
                Map.of(
                        "id", 2,
                        "title", "发货规则",
                        "sourceType", "POLICY",
                        "content", "商家需在 48 小时内完成发货",
                        "status", "已启用",
                        "version", 1,
                        "updatedAt", "2026-06-01 10:00:00"
                )
        );
        return ApiResponse.success(docs);
    }

    @Operation(summary = "触发知识库重建")
    @PostMapping("/knowledge-docs/rebuild")
    public ApiResponse<Map<String, Object>> rebuildKnowledgeDocs() {
        return ApiResponse.success(Map.of(
                "success", true,
                "message", "已触发知识库重建 mock"
        ));
    }

    @Operation(summary = "管理端仪表盘数据")
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.success(Map.of(
                "productCount", 3,
                "docCount", 2,
                "pendingOrderCount", 1
        ));
    }
}
