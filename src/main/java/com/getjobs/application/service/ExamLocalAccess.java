package com.getjobs.application.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.Set;

/** The desktop and question APIs execute locally on the user's computer. */
public final class ExamLocalAccess {
    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:6866", "http://127.0.0.1:6866", "http://[::1]:6866",
            "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527");

    private ExamLocalAccess() {}

    public static void requireLocal(HttpServletRequest request) {
        boolean loopback = false;
        try {
            // RemoteAddr comes from the socket, not forwarded headers.
            String address = request.getRemoteAddr();
            if (address != null && address.matches("[0-9a-fA-F:.%]+")) {
                loopback = InetAddress.getByName(address).isLoopbackAddress();
            }
        } catch (Exception ignored) {
            // Fail closed for an unrecognised socket address.
        }
        String origin = request.getHeader("Origin");
        if (!loopback || (origin != null && !ALLOWED_ORIGINS.contains(origin))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "笔试助手只允许从本机觅途页面操作");
        }
    }
}
