package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*; import java.util.*;
public record ConfirmedReturnApplyRequest(@NotBlank @Size(max=64) String actionId,@NotNull @Positive Long orderId,@NotBlank @Size(max=255) String reason,@Size(max=2000) String description) implements ConfirmedActionRequest{public Map<String,Object>toPayload(){Map<String,Object>v=new LinkedHashMap<>();v.put("actionId",actionId);v.put("orderId",orderId);v.put("reason",reason);if(description!=null)v.put("description",description);return v;}}
