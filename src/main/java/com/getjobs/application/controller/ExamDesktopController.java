package com.getjobs.application.controller;

import com.getjobs.application.service.ExamDesktopService;
import com.getjobs.application.service.ExamLocalAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

@RestController
@RequestMapping("/api/exam")
public class ExamDesktopController {
    private final ExamDesktopService desktop;

    public ExamDesktopController(ExamDesktopService desktop) { this.desktop = desktop; }

    @GetMapping("/settings")
    public ExamDesktopService.DesktopSettings settings(HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return desktop.getSettings();
    }

    @PutMapping("/settings")
    public ExamDesktopService.DesktopSettings saveSettings(HttpServletRequest request,
            @RequestBody ExamDesktopService.DesktopSettings settings) {
        ExamLocalAccess.requireLocal(request);
        return desktop.saveSettings(settings);
    }

    @GetMapping("/desktop/status")
    public ExamDesktopService.DesktopStatus status(HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return desktop.getStatus();
    }

    @PostMapping("/desktop/start")
    public ExamDesktopService.DesktopStatus start(HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return desktop.start();
    }

    @PostMapping("/desktop/stop")
    public ExamDesktopService.DesktopStatus stop(HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return desktop.stop();
    }

    @PostMapping("/desktop/content")
    public ExamDesktopService.DesktopStatus content(HttpServletRequest request, @RequestBody Content content) {
        ExamLocalAccess.requireLocal(request);
        if (content == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供题目和解析内容。");
        return desktop.updateContent(content.question(), content.answer());
    }

    @PostMapping("/desktop/capture")
    public ExamDesktopService.CaptureResult capture(HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return desktop.capture();
    }

    public record Content(String question, String answer) { }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleProblem(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "操作失败，请重试。" : error.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "请求格式无效，请提供正确的 JSON。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected() {
        return ResponseEntity.internalServerError().body(Map.of("message", "桌面助手操作失败，请重试。"));
    }
}
