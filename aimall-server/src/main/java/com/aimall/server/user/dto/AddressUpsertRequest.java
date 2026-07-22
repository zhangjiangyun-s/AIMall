package com.aimall.server.user.dto;

import com.aimall.server.entity.UmsMemberAddress;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressUpsertRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Pattern(regexp = "[0-9+() -]{6,32}") String phone,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String region,
        @NotBlank @Size(max = 255) String detailAddress,
        @Min(0) @Max(1) Integer defaultStatus
) {
    public UmsMemberAddress toEntity() {
        UmsMemberAddress address = new UmsMemberAddress();
        address.setName(name.trim());
        address.setPhone(phone.trim());
        address.setProvince(province.trim());
        address.setCity(city.trim());
        address.setRegion(region.trim());
        address.setDetailAddress(detailAddress.trim());
        address.setDefaultStatus(defaultStatus);
        return address;
    }
}
