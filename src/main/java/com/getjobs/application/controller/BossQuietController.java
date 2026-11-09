package com.getjobs.application.controller;

import com.getjobs.application.service.BossQuietService;
import com.getjobs.worker.service.BossJobService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/** Local controls of this application's browser only. No endpoint starts delivery. */
@RestController
@RequestMapping("/api/boss/quiet")
@CrossOrigin(origins = {"http://localhost:6866", "http://127.0.0.1:6866", "http://[::1]:6866",
        "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527"})
public class BossQuietController {
    private final BossQuietService quiet;
    private final BossJobService jobs;
    public BossQuietController(BossQuietService quiet, BossJobService jobs) { this.quiet = quiet; this.jobs = jobs; }

    @GetMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest request) { local(request); return quiet.settings(); }

    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        local(request);
        if (input.size() != 1 || !(input.get("quietEnabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("quietEnabled 必须为布尔值");
        }
        synchronized (jobs) {
            if (jobs.isRunning()) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务运行中不能修改静默模式");
            return quiet.saveSettings(enabled);
        }
    }

    @PostMapping("/show")
    public ResponseEntity<Map<String, Object>> show(HttpServletRequest request) {
        local(request); return ResponseEntity.accepted().body(quiet.request("show"));
    }

    @PostMapping("/background")
    public ResponseEntity<Map<String, Object>> background(HttpServletRequest request) {
        local(request); return ResponseEntity.accepted().body(quiet.request("background"));
    }

    private static void local(HttpServletRequest request) {
        boolean allowed = false;
        try {
            String address = request.getRemoteAddr();
            allowed = address != null && address.matches("[0-9a-fA-F:.%]+") && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) { }
        String origin = request.getHeader("Origin");
        if (!allowed || (origin != null && !Set.of("http://localhost:6866", "http://127.0.0.1:6866", "http://[::1]:6866",
                "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527").contains(origin))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "浏览器控制只允许从本机觅途页面操作");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", failure.getReason() == null ? "请求失败" : failure.getReason()));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception failure) {
        return ResponseEntity.badRequest().body(Map.of("message", "静默设置参数无效，请重新读取后保存"));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", failure.getMessage() == null ? "浏览器尚未就绪" : failure.getMessage()));
    }
}
