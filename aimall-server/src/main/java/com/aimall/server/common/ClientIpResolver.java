package com.aimall.server.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class ClientIpResolver {
    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(
            @Value("${aimall.security.trusted-proxy-cidrs:127.0.0.1/32,::1/128}") String configuredCidrs
    ) {
        trustedProxies = Arrays.stream(configuredCidrs.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(CidrBlock::parse)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (!isTrusted(remoteAddress)) {
            return remoteAddress;
        }
        List<String> chain = forwardedChain(request.getHeader("X-Forwarded-For"));
        if (chain.isEmpty()) {
            String realIp = normalize(request.getHeader("X-Real-IP"));
            return realIp.isEmpty() || !isNumericAddress(realIp) ? remoteAddress : realIp;
        }
        String current = remoteAddress;
        for (int index = chain.size() - 1; index >= 0; index--) {
            if (!isTrusted(current)) {
                break;
            }
            current = chain.get(index);
        }
        return current;
    }

    private List<String> forwardedChain(String header) {
        List<String> result = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return result;
        }
        for (String item : header.split(",")) {
            String address = normalize(item);
            if (!address.isEmpty() && isNumericAddress(address)) {
                result.add(address);
            }
        }
        return result;
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(block -> block.matches(address));
    }

    private static boolean isNumericAddress(String address) {
        return address.matches("[0-9.]+") || address.matches("[0-9a-fA-F:]+");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String address = value.trim();
        if (address.startsWith("[") && address.contains("]")) {
            return address.substring(1, address.indexOf(']'));
        }
        if (address.matches("[0-9.]+:[0-9]+")) {
            return address.substring(0, address.lastIndexOf(':'));
        }
        return address;
    }

    private record CidrBlock(byte[] network, int prefixLength) {
        private static CidrBlock parse(String value) {
            try {
                String[] parts = value.split("/", 2);
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException("非法可信代理 CIDR: " + value);
                }
                return new CidrBlock(address, prefix);
            } catch (Exception exception) {
                throw new IllegalArgumentException("非法可信代理 CIDR: " + value, exception);
            }
        }

        private boolean matches(String value) {
            try {
                if (!isNumericAddress(value)) return false;
                byte[] candidate = InetAddress.getByName(value).getAddress();
                if (candidate.length != network.length) return false;
                int wholeBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;
                for (int index = 0; index < wholeBytes; index++) {
                    if (candidate[index] != network[index]) return false;
                }
                if (remainingBits == 0) return true;
                int mask = 0xff << (8 - remainingBits);
                return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
