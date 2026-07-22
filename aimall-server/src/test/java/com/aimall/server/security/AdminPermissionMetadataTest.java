package com.aimall.server.security;

import com.aimall.server.admin.AdminAiActionRecoveryController;
import com.aimall.server.admin.AdminController;
import com.aimall.server.admin.AdminKnowledgeController;
import com.aimall.server.admin.AdminPaymentOperationsController;
import com.aimall.server.admin.AdminProductCatalogController;
import com.aimall.server.admin.AdminProductMetadataController;
import com.aimall.server.admin.AdminProductOperationsController;
import com.aimall.server.admin.AdminReturnController;
import com.aimall.server.admin.PaymentReconciliationController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminPermissionMetadataTest {
    @Test
    void everyAdminControllerEndpointHasExplicitPermissionMetadata() {
        List<Class<?>> controllers = List.of(
                AdminController.class,
                AdminKnowledgeController.class,
                AdminProductCatalogController.class,
                AdminProductMetadataController.class,
                AdminProductOperationsController.class,
                AdminReturnController.class,
                AdminAiActionRecoveryController.class,
                AdminPaymentOperationsController.class,
                PaymentReconciliationController.class
        );
        for (Class<?> controller : controllers) {
            RequireAdminPermission classPermission = AnnotatedElementUtils.findMergedAnnotation(
                    controller, RequireAdminPermission.class
            );
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
                RequireAdminPermission methodPermission = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequireAdminPermission.class
                );
                assertNotNull(methodPermission == null ? classPermission : methodPermission,
                        controller.getSimpleName() + "#" + method.getName() + " missing admin permission");
            }
        }
    }
}
