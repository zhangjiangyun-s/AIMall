package com.aimall.server.service.impl;

import com.aimall.server.entity.UmsMemberAddress;
import com.aimall.server.mapper.UmsMemberAddressMapper;
import com.aimall.server.service.AddressService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressServiceImpl implements AddressService {

    private final UmsMemberAddressMapper addressMapper;

    public AddressServiceImpl(UmsMemberAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Override
    public List<UmsMemberAddress> listByMember(Long memberId) {
        return addressMapper.selectList(
                new LambdaQueryWrapper<UmsMemberAddress>()
                        .eq(UmsMemberAddress::getMemberId, memberId)
                        .orderByDesc(UmsMemberAddress::getDefaultStatus)
                        .orderByDesc(UmsMemberAddress::getId)
        );
    }

    @Override
    @Transactional
    public UmsMemberAddress create(Long memberId, UmsMemberAddress address) {
        address.setId(null);
        address.setMemberId(memberId);
        if (address.getDefaultStatus() == null) {
            address.setDefaultStatus(listByMember(memberId).isEmpty() ? 1 : 0);
        }
        if (address.getDefaultStatus() == 1) {
            clearDefault(memberId);
        }
        addressMapper.insert(address);
        return address;
    }

    @Override
    @Transactional
    public UmsMemberAddress update(Long memberId, Long addressId, UmsMemberAddress address) {
        UmsMemberAddress existing = getOwnedAddress(memberId, addressId);
        existing.setName(address.getName());
        existing.setPhone(address.getPhone());
        existing.setProvince(address.getProvince());
        existing.setCity(address.getCity());
        existing.setRegion(address.getRegion());
        existing.setDetailAddress(address.getDetailAddress());
        if (address.getDefaultStatus() != null) {
            existing.setDefaultStatus(address.getDefaultStatus());
        }
        if (existing.getDefaultStatus() != null && existing.getDefaultStatus() == 1) {
            clearDefault(memberId);
            existing.setDefaultStatus(1);
        }
        addressMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional
    public void delete(Long memberId, Long addressId) {
        UmsMemberAddress existing = getOwnedAddress(memberId, addressId);
        addressMapper.deleteById(existing.getId());
        if (existing.getDefaultStatus() != null && existing.getDefaultStatus() == 1) {
            UmsMemberAddress next = addressMapper.selectOne(
                    new LambdaQueryWrapper<UmsMemberAddress>()
                            .eq(UmsMemberAddress::getMemberId, memberId)
                            .orderByDesc(UmsMemberAddress::getId)
                            .last("limit 1")
            );
            if (next != null) {
                next.setDefaultStatus(1);
                addressMapper.updateById(next);
            }
        }
    }

    @Override
    @Transactional
    public UmsMemberAddress setDefault(Long memberId, Long addressId) {
        UmsMemberAddress address = getOwnedAddress(memberId, addressId);
        clearDefault(memberId);
        address.setDefaultStatus(1);
        addressMapper.updateById(address);
        return address;
    }

    @Override
    public UmsMemberAddress getDefaultAddress(Long memberId) {
        UmsMemberAddress address = addressMapper.selectOne(
                new LambdaQueryWrapper<UmsMemberAddress>()
                        .eq(UmsMemberAddress::getMemberId, memberId)
                        .eq(UmsMemberAddress::getDefaultStatus, 1)
                        .last("limit 1")
        );
        if (address != null) {
            return address;
        }
        return addressMapper.selectOne(
                new LambdaQueryWrapper<UmsMemberAddress>()
                        .eq(UmsMemberAddress::getMemberId, memberId)
                        .orderByDesc(UmsMemberAddress::getId)
                        .last("limit 1")
        );
    }

    @Override
    public UmsMemberAddress getOwnedAddress(Long memberId, Long addressId) {
        UmsMemberAddress address = addressMapper.selectById(addressId);
        if (address == null || !memberId.equals(address.getMemberId())) {
            throw new RuntimeException("地址不存在");
        }
        return address;
    }

    private void clearDefault(Long memberId) {
        List<UmsMemberAddress> addresses = addressMapper.selectList(
                new LambdaQueryWrapper<UmsMemberAddress>()
                        .eq(UmsMemberAddress::getMemberId, memberId)
                        .eq(UmsMemberAddress::getDefaultStatus, 1)
        );
        for (UmsMemberAddress item : addresses) {
            item.setDefaultStatus(0);
            addressMapper.updateById(item);
        }
    }
}
