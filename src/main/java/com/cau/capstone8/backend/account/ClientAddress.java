package com.cau.capstone8.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Resolve from the raw socket peer, walking only explicitly trusted proxy hops. */
@Component
public final class ClientAddress {
    private final List<IpAddressMatcher> trusted;
    public ClientAddress(@Value("${app.auth.trusted-proxy-cidrs:}") String configuration) {
        var matchers = new ArrayList<IpAddressMatcher>();
        for (String entry : configuration.split(",")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.strip().split("/", -1);
            String address = numeric(parts[0]);
            if (address == null || parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            int maximum = address.contains(":") ? 128 : 32;
            int bits;
            try { bits = parts.length == 1 ? maximum : Integer.parseInt(parts[1]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid trusted proxy CIDR"); }
            if (bits < 1 || bits > maximum) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            matchers.add(new IpAddressMatcher(address + "/" + bits));
        }
        trusted = List.copyOf(matchers);
    }
    public String resolve(HttpServletRequest request) {
        String peer = numeric(request.getRemoteAddr());
        if (peer == null) return "unknown-peer";
        if (!isTrusted(peer)) return peer;
        var headers = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (headers.isEmpty()) return peer;
        String header = String.join(",", headers);
        if (header.length() > 2048) return peer;
        String[] hops = header.split(",", -1);
        if (hops.length > 16) return peer;
        for (int i=0; i<hops.length; i++) {
            hops[i] = numeric(hops[i].strip());
            if (hops[i] == null) return peer;
        }
        String current = peer;
        for (int i=hops.length-1; i>=0 && isTrusted(current); i--) current=hops[i];
        return current;
    }
    private boolean isTrusted(String address) { return trusted.stream().anyMatch(m -> m.matches(address)); }
    private static String numeric(String address) {
        if (address == null || address.length() > 45) return null;
        // Validate numeric syntax before InetAddress: hostnames never trigger DNS lookups.
        if (!address.contains(":")) {
            if (!address.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return null;
        } else if (!address.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(address).getHostAddress(); }
        catch (UnknownHostException e) { return null; }
    }
}
