package com.getjobs.application.controller;

import com.getjobs.application.service.ExamAssistantService;
import com.getjobs.application.service.ExamLocalAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/exam")
@CrossOrigin(origins = {"http://localhost:6866", "http://127.0.0.1:6866", "http://[::1]:6866",
        "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527"})
public class ExamAssistantController {
    private final ExamAssistantService assistantService;

    public ExamAssistantController(ExamAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/recognize")
    public Map<String, String> recognize(@RequestPart("image") MultipartFile image, HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        if (image == null || image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传题目图片。");
        }
        if (image.getSize() > ExamAssistantService.MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5 MiB，请压缩或裁剪后重试。");
        }
        String contentType = image.getContentType();
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!"image/png".equals(normalizedType) && !"image/jpeg".equals(normalizedType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只支持 PNG 或 JPEG 图片。");
        }
        Path temporaryDirectory = null;
        Path temporaryImage = null;
        try {
            temporaryDirectory = Files.createTempDirectory("mitu-exam-upload-");
            temporaryImage = temporaryDirectory.resolve("image");
            image.transferTo(temporaryImage);
            return Map.of("text", assistantService.recognizeImage(temporaryImage));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取上传图片，请重新选择后重试。");
        } finally {
            if (temporaryImage != null) {
                try { Files.deleteIfExists(temporaryImage); } catch (IOException ignored) { }
            }
            if (temporaryDirectory != null) {
                try { Files.deleteIfExists(temporaryDirectory); } catch (IOException ignored) { }
            }
        }
    }

    public record SolveRequest(String question) { }

    @PostMapping("/solve")
    public Map<String, String> solve(@RequestBody SolveRequest body, HttpServletRequest request) {
        ExamLocalAccess.requireLocal(request);
        return Map.of("answer", assistantService.solve(body == null ? null : body.question()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleClientSafeFailure(ResponseStatusException exception) {
        String reason = exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", reason == null ? "请求无法完成，请检查输入后重试。" : reason));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleLargeUpload(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "图片不能超过 5 MiB，请压缩或裁剪后重试。"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleInvalidUpload(MultipartException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "图片上传格式无效，请重新选择图片。"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> handleMissingInput(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求内容无效，请填写题目或上传 image 图片。"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedContentType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("message", "文字解析使用 JSON；图片识别使用 PNG 或 JPEG 文件上传。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedFailure(Exception exception) {
        return ResponseEntity.internalServerError().body(Map.of("message", "笔试请求无法完成，请稍后重试。"));
    }
}
