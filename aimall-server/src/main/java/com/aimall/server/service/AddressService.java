package com.aimall.server.service;

import com.aimall.server.entity.UmsMemberAddress;

import java.util.List;

public interface AddressService {
    List<UmsMemberAddress> listByMember(Long memberId);
    UmsMemberAddress create(Long memberId, UmsMemberAddress address);
    UmsMemberAddress update(Long memberId, Long addressId, UmsMemberAddress address);
    void delete(Long memberId, Long addressId);
    UmsMemberAddress setDefault(Long memberId, Long addressId);
    UmsMemberAddress getDefaultAddress(Long memberId);
    UmsMemberAddress getOwnedAddress(Long memberId, Long addressId);
}
