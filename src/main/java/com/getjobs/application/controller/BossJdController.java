package com.getjobs.application.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossJdAnalysisService;
import com.getjobs.application.service.BossJdStore;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

/** JD APIs never start browser automation or communication with recruiters. */
@RestController
@RequestMapping("/api/boss/jd")
@CrossOrigin(origins = {"http://localhost:6866", "http://127.0.0.1:6866", "http://[::1]:6866",
        "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527"})
public class BossJdController {
    private static final Set<String> ORIGINS = Set.of("http://localhost:6866", "http://127.0.0.1:6866",
            "http://[::1]:6866", "http://localhost:9527", "http://127.0.0.1:9527", "http://[::1]:9527");
    private final BossJdStore store;
    private final BossJdAnalysisService analysis;
    private final ObjectMapper json;
    private final ConcurrentMap<String, Map<String, Object>> imports = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor importWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "boss-jd-history-import");
                thread.setDaemon(true);
                return thread;
            });

    public BossJdController(BossJdStore store, BossJdAnalysisService analysis, ObjectMapper json) {
        this.store = store;
        this.analysis = analysis;
        this.json = json;
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs(@RequestParam Map<String, String> params, HttpServletRequest request) {
        requireLocal(request);
        return store.list(scope(params), integer(params.get("page"), 1, 1, 1_000_000),
                integer(params.get("size"), 20, 1, 100));
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam Map<String, String> params, HttpServletRequest request) {
        requireLocal(request);
        return store.overview(scope(params));
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable long id, HttpServletRequest request) {
        requireLocal(request);
        Map<String, Object> result = store.detail(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "岗位不存在");
        return result;
    }

    @PatchMapping("/jobs/{id}")
    public Map<String, Object> include(@PathVariable long id, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        requireLocal(request);
        if (!(body.get("included") instanceof Boolean included)) {
            throw new IllegalArgumentException("included 必须为布尔值");
        }
        store.setIncluded(id, included);
        return Map.of("jobId", id, "included", included);
    }

    @PostMapping("/history-imports")
    public synchronized ResponseEntity<Map<String, Object>> importHistory(HttpServletRequest request) {
        requireLocal(request);
        for (Map<String, Object> task : imports.values()) {
            if (Set.of("queued", "running").contains(task.get("status"))) {
                return ResponseEntity.accepted().body(task);
            }
        }
        String id = UUID.randomUUID().toString();
        Map<String, Object> task = new ConcurrentHashMap<>();
        task.put("taskId", id);
        task.put("status", "queued");
        task.put("createdAt", java.time.Instant.now().toString());
        if (imports.size() >= 50) imports.entrySet().removeIf(e -> !Set.of("queued", "running").contains(e.getValue().get("status")));
        imports.put(id, task);
        importWorker.execute(() -> {
            task.put("status", "running");
            try {
                task.put("result", store.importHistory());
                task.put("status", "completed");
            } catch (Exception failure) {
                task.put("error", "历史导入失败，已完成的记录仍保留，可重试");
                task.put("status", "failed");
            }
        });
        return ResponseEntity.accepted().body(task);
    }

    @GetMapping("/history-imports/{id}")
    public Map<String, Object> importStatus(@PathVariable String id, HttpServletRequest request) {
        requireLocal(request);
        Map<String, Object> task = imports.get(id);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入任务不存在或服务已重启；可重新导入，已有数据会去重");
        return task;
    }

    @PostMapping("/analyses")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireLocal(request);
        return ResponseEntity.accepted().body(analysis.submit(scope(body)));
    }

    @GetMapping("/analyses/{id}")
    public Map<String, Object> task(@PathVariable String id, HttpServletRequest request) {
        requireLocal(request);
        return requireReport(analysis.task(id));
    }

    @PostMapping("/analyses/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest request) {
        requireLocal(request);
        return requireReport(analysis.cancel(id));
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports(@RequestParam Map<String, String> params, HttpServletRequest request) {
        requireLocal(request);
        return analysis.reports(scope(params));
    }

    @GetMapping("/reports/{id}")
    public Map<String, Object> report(@PathVariable String id, HttpServletRequest request) {
        requireLocal(request);
        return requireReport(analysis.report(id));
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest request) {
        requireLocal(request);
        return analysis.settings();
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireLocal(request);
        return analysis.saveSettings(body);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> params, HttpServletRequest request)
            throws JsonProcessingException {
        requireLocal(request);
        String format = params.getOrDefault("format", "json");
        if (!Set.of("json", "markdown").contains(format)) throw new IllegalArgumentException("仅支持 json 或 markdown 导出");
        String reportId = params.get("reportId");
        Object payload;
        String markdown;
        if (reportId != null && !reportId.isBlank()) {
            Map<String, Object> report = requireReport(analysis.report(reportId));
            payload = report;
            markdown = reportMarkdown(report);
        } else {
            List<Map<String, Object>> samples = store.samples(scope(params));
            if (samples.size() > 1000) throw new IllegalArgumentException("单次最多导出1000个岗位，请缩小筛选范围后重试");
            payload = Map.of("exportedAt", java.time.Instant.now().toString(), "count", samples.size(), "items", samples);
            StringBuilder text = new StringBuilder("# 觅途 JD 岗位库\n\n");
            for (Map<String, Object> sample : samples) {
                text.append("## ").append(sample.getOrDefault("jobName", "未命名岗位")).append("\n\n")
                        .append("公司：").append(sample.getOrDefault("companyName", "")).append("\n\n")
                        .append("城市：").append(sample.getOrDefault("city", "")).append("\n\n")
                        .append("来源：").append(sample.getOrDefault("jobUrl", "")).append("\n\n")
                        .append("岗位/版本：").append(sample.get("jobId")).append(" / ").append(sample.get("versionId")).append("\n\n")
                        .append(sample.getOrDefault("description", "")).append("\n\n---\n\n");
            }
            markdown = text.toString();
        }
        byte[] bytes = (format.equals("json") ? json.writerWithDefaultPrettyPrinter().writeValueAsString(payload) : markdown)
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalArgumentException("导出文件超过16MiB，请缩小筛选范围");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=mitu-jd." + (format.equals("json") ? "json" : "md"))
                .contentType(MediaType.parseMediaType(format.equals("json") ? "application/json;charset=UTF-8" : "text/markdown;charset=UTF-8"))
                .body(bytes);
    }

    private static Map<String, Object> requireReport(Map<String, Object> report) {
        if (report == null || report.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析任务或报告不存在");
        return report;
    }

    private static String reportMarkdown(Map<String, Object> report) {
        StringBuilder text = new StringBuilder("# 觅途 JD 需求总结\n\n");
        text.append("报告：").append(report.get("id")).append("\n\n生成时间：").append(report.get("createdAt"))
                .append("\n\n分析覆盖：").append(report.get("analyzedCount")).append(" / ").append(report.get("sampleCount"))
                .append("；状态：").append(report.get("status")).append("；已过期：").append(report.get("stale")).append("\n\n");
        if (report.get("requirements") instanceof List<?> items) for (Object item : items) {
            if (!(item instanceof Map<?, ?> requirement)) continue;
            text.append("## ").append(requirement.get("term")).append("\n\n分类：").append(requirement.get("category"))
                    .append("；提及岗位：").append(requirement.get("count")).append(" / ").append(requirement.get("total")).append("\n\n");
            if (requirement.get("evidence") instanceof List<?> evidence) for (Object source : evidence) {
                if (!(source instanceof Map<?, ?> quote)) continue;
                text.append("岗位/版本：").append(quote.get("jobId")).append(" / ").append(quote.get("versionId")).append("\n\n> ")
                        .append(String.valueOf(quote.get("quote")).replace("\n", "\n> ")).append("\n\n");
            }
        }
        if (report.get("recommendations") instanceof List<?> recommendations) {
            text.append("## 简历准备建议\n\n");
            for (Object recommendation : recommendations) text.append("- ")
                    .append(recommendation instanceof Map<?, ?> value ? value.get("text") : recommendation).append("\n");
        }
        if (report.get("manifest") instanceof List<?> samples) {
            text.append("\n## 样本版本清单\n\n");
            for (Object sample : samples) if (sample instanceof Map<?, ?> value) text.append("- 岗位 ")
                    .append(value.get("jobId")).append(" / 版本 ").append(value.get("versionId"))
                    .append("：").append(value.get("jobName")).append("\n");
        }
        text.append("\n统计仅代表所选岗位样本；简历调整需有真实经历支持。\n");
        return text.toString();
    }

    public static BossJdStore.Scope scope(Map<String, ?> params) {
        String keyword = text(params.get("keyword"), 100);
        String city = text(params.get("city"), 100);
        String type = text(params.get("jobType"), 30);
        if (!type.isEmpty() && !Set.of("0", "1901", "1902", "1903", "unknown").contains(type)) {
            throw new IllegalArgumentException("职位类型无效");
        }
        String from = date(params.get("from"));
        String to = date(params.get("to"));
        if (!from.isEmpty() && !to.isEmpty() && from.compareTo(to) > 0) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        Object includeValue = params.get("includeHistory");
        boolean history = false;
        if (includeValue != null) {
            if (!(includeValue instanceof Boolean) && !Set.of("true", "false").contains(includeValue)) {
                throw new IllegalArgumentException("includeHistory 必须为布尔值");
            }
            history = Boolean.parseBoolean(includeValue.toString());
        }
        List<Long> ids = new ArrayList<>();
        Object rawIds = params.get("jobIds");
        if (rawIds instanceof String csv) {
            if (csv.length() > 20_000 || !csv.matches("[0-9]+(,[0-9]+)*")) throw new IllegalArgumentException("岗位ID列表无效");
            List<Long> parsed = new ArrayList<>();
            for (String value : csv.split(",")) {
                try { parsed.add(Long.parseLong(value)); }
                catch (NumberFormatException failure) { throw new IllegalArgumentException("岗位ID超出范围"); }
            }
            rawIds = parsed;
        }
        if (rawIds != null) {
            if (!(rawIds instanceof List<?> values) || values.size() > 1000) throw new IllegalArgumentException("最多选择1000个岗位");
            for (Object value : values) {
                if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0) {
                    throw new IllegalArgumentException("岗位ID必须为正整数");
                }
                ids.add(((Number) value).longValue());
            }
        }
        return new BossJdStore.Scope(keyword, city, type, from, to, history, List.copyOf(ids));
    }

    private static String text(Object value, int limit) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > limit || text.indexOf('\0') >= 0) throw new IllegalArgumentException("筛选条件无效或过长");
        return text.trim();
    }

    private static String date(Object value) {
        String valueText = text(value, 10);
        if (!valueText.isEmpty()) {
            try { LocalDate.parse(valueText); }
            catch (DateTimeParseException failure) { throw new IllegalArgumentException("日期应为YYYY-MM-DD"); }
        }
        return valueText;
    }

    private static int integer(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            int result = Integer.parseInt(value);
            if (result >= min && result <= max) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("分页参数超出范围");
    }

    private static void requireLocal(HttpServletRequest request) {
        boolean local = false;
        try {
            String address = request.getRemoteAddr();
            local = address != null && address.matches("[0-9a-fA-F:.%]+") && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) { }
        String origin = request.getHeader("Origin");
        if (!local || (origin != null && !ORIGINS.contains(origin))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "JD岗位库只允许从本机觅途页面操作");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> clientError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(exception.getReason(), "请求失败")));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception instanceof IllegalArgumentException
                ? Objects.requireNonNullElse(exception.getMessage(), "参数无效") : "请求JSON无效"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "JD模块暂不可用，请检查本地服务日志或AI配置后重试"));
    }

    @PreDestroy
    public void close() { importWorker.shutdownNow(); }
}
