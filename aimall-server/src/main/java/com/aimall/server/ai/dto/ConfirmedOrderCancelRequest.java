package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*; import java.util.*;
public record ConfirmedOrderCancelRequest(@NotBlank @Size(max=64) String actionId,@NotNull @Positive Long orderId,@Size(max=64) String orderSn) implements ConfirmedActionRequest{public Map<String,Object>toPayload(){Map<String,Object>v=new LinkedHashMap<>();v.put("actionId",actionId);v.put("orderId",orderId);if(orderSn!=null)v.put("orderSn",orderSn);return v;}}
