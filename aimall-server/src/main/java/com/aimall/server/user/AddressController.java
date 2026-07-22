package com.aimall.server.user;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.user.dto.AddressUpsertRequest;
import com.aimall.server.entity.UmsMemberAddress;
import com.aimall.server.service.AddressService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/user/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(addressService.listByMember(memberId).stream().map(this::toMap).toList());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody AddressUpsertRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(toMap(addressService.create(memberId, params.toEntity())));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody AddressUpsertRequest params
    ) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(toMap(addressService.update(memberId, id, params.toEntity())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        long memberId = StpUtil.getLoginIdAsLong();
        addressService.delete(memberId, id);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}/default")
    public ApiResponse<Map<String, Object>> setDefault(@PathVariable Long id) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(toMap(addressService.setDefault(memberId, id)));
    }

    private Map<String, Object> toMap(UmsMemberAddress address) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", address.getId());
        data.put("name", address.getName());
        data.put("phone", address.getPhone());
        data.put("province", address.getProvince());
        data.put("city", address.getCity());
        data.put("region", address.getRegion());
        data.put("detailAddress", address.getDetailAddress());
        data.put("defaultStatus", address.getDefaultStatus());
        data.put("fullAddress", address.getProvince() + address.getCity() + address.getRegion() + address.getDetailAddress());
        return data;
    }
}
