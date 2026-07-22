package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*; import java.util.Map;
public record ConfirmedCouponClaimRequest(@NotBlank @Size(max=64) String actionId,@NotNull @Positive Long couponId) implements ConfirmedActionRequest{public Map<String,Object>toPayload(){return Map.of("actionId",actionId,"couponId",couponId);}}
