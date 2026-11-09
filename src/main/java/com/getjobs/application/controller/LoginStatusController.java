package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Boss 登录状态推送，保留现有前端的 SSE 地址。 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/jobs/login-status")
@RequiredArgsConstructor
public class LoginStatusController {
    private final PlaywrightManager playwrightManager;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Consumer<PlaywrightManager.LoginStatusChange> listener = this::sendLoginStatus;

    @PostConstruct
    public void registerListener() {
        // 每个控制器只注册一次，避免浏览器重连 SSE 时重复推送。
        playwrightManager.addLoginStatusListener(listener);
    }

    @PreDestroy
    public void unregisterListener() {
        playwrightManager.removeLoginStatusListener(listener);
        for (SseEmitter emitter : emitters) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        emitters.clear();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "message", "已连接到 Boss 登录状态推送",
                    "bossLoggedIn", playwrightManager.isLoggedIn("boss")
            )));
        } catch (Exception e) {
            disconnect(emitter, e);
        }
        return emitter;
    }

    private void sendLoginStatus(PlaywrightManager.LoginStatusChange change) {
        if (!"boss".equals(change.platform())) return;
        send("login-status", Map.of(
                "platform", "boss", "isLoggedIn", change.isLoggedIn(), "timestamp", change.timestamp()
        ));
    }

    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        send("ping", "keep-alive");
    }

    private void send(String event, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                disconnect(emitter, e);
            }
        }
    }

    private void disconnect(SseEmitter emitter, Exception error) {
        emitters.remove(emitter);
        log.debug("Boss 登录状态订阅已断开: {}", error.getMessage());
        try { emitter.complete(); } catch (Exception ignored) {}
    }
}
