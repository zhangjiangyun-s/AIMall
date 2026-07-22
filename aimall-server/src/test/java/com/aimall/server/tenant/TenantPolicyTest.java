package com.aimall.server.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantPolicyTest {
    @Test
    void singleTenantDefaultsAndRejectsForeignTenant() {
        TenantPolicy policy = new TenantPolicy("SINGLE_TENANT", "default");
        assertEquals("default", policy.resolve(null));
        assertEquals("default", policy.resolve("default"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("tenant-b"));
    }

    @Test
    void modeMustBeExplicitlySupported() {
        assertThrows(IllegalArgumentException.class, () -> new TenantPolicy("AUTO", "default"));
        assertThrows(IllegalStateException.class, () -> new TenantPolicy("MULTI_TENANT", "default"));
        assertThrows(IllegalStateException.class, () -> new TenantPolicy("SINGLE_TENANT", "custom"));
    }
}
