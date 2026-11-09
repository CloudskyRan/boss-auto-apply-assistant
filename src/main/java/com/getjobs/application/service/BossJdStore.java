package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, versioned JD archive. It never starts browser or model operations. */
@Service
public class BossJdStore {
    public record Scope(String keyword, String city, String jobType, String from, String to,
                        boolean includeHistory, List<Long> jobIds) {}
    public record Capture(String taskId, String keyword, String searchCity, String configJson,
                          String encryptId, String jobName, String companyName, String city,
                          String salary, String experience, String degree, String jobType,
                          String description, String source) {}
    public record CaptureResult(String outcome, long jobId, long versionId) {}

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private volatile boolean available;
    private volatile String initializationError;
    private static final String RULE_VERSION = "jd-match-v1";
    private static final int MAX_DESCRIPTION = 200_000;
    private static final Scope ALL = new Scope(null, null, null, null, null, false, List.of());
    private static final Map<String, Pattern> TERMS = terms();

    public BossJdStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public synchronized void initialize() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("""
                CREATE TABLE IF NOT EXISTS boss_jd_job (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL DEFAULT 'boss',
                  encrypt_id TEXT NOT NULL, current_version_id INTEGER,
                  job_name TEXT NOT NULL, company_name TEXT NOT NULL DEFAULT '', city TEXT NOT NULL DEFAULT '',
                  first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                  seen_count INTEGER NOT NULL DEFAULT 0, included INTEGER NOT NULL DEFAULT -1,
                  observation_order INTEGER NOT NULL DEFAULT 0,
                  UNIQUE(platform, encrypt_id))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS boss_jd_version (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL,
                  content_hash TEXT NOT NULL, description TEXT NOT NULL, metadata_json TEXT NOT NULL,
                  captured_at INTEGER NOT NULL, UNIQUE(job_id,content_hash))
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS boss_jd_match (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, version_id INTEGER NOT NULL,
                  task_id TEXT NOT NULL, keyword TEXT NOT NULL DEFAULT '', search_city TEXT NOT NULL DEFAULT '',
                  actual_city TEXT NOT NULL DEFAULT '', job_type TEXT NOT NULL DEFAULT '',
                  config_json TEXT NOT NULL, metadata_json TEXT NOT NULL,
                  source TEXT NOT NULL, matched INTEGER NOT NULL, rules_version TEXT NOT NULL,
                  first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, observation_order INTEGER NOT NULL,
                  UNIQUE(task_id,job_id,version_id,keyword,search_city,job_type,source))
                """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_boss_jd_match_job ON boss_jd_match(job_id,last_seen)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_boss_jd_match_task ON boss_jd_match(task_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_boss_jd_match_scope ON boss_jd_match(keyword,actual_city,job_type,last_seen)");
            available = true;
            initializationError = null;
        } catch (Exception e) {
            available = false;
            initializationError = "JD 岗位库初始化失败，请检查本地数据库和日志。";
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, initializationError, e);
        }
    }

    public boolean isAvailable() { return available; }
    public String getInitializationError() { return initializationError; }

    private void requireAvailable() {
        if (!available) throw new IllegalStateException(initializationError == null ? "JD 岗位库尚未初始化" : initializationError);
    }

    /** A complete, filtered detail is persisted in a short transaction before communication. */
    public synchronized CaptureResult capture(Capture input) {
        requireAvailable();
        if (input == null) throw new IllegalArgumentException("缺少 JD 数据");
        String encryptId = text(input.encryptId());
        if (!encryptId.matches("[A-Za-z0-9_-]{1,200}") || Set.of("null", "undefined").contains(encryptId))
            throw new IllegalArgumentException("岗位标识缺失或无效");
        if (text(input.jobName()).isBlank()) throw new IllegalArgumentException("岗位名称为空");
        String description = input.description() == null ? "" : input.description();
        if (description.isBlank()) throw new IllegalArgumentException("岗位 JD 为空");
        if (description.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("岗位 JD 超过本地保存长度上限");
        if (text(input.taskId()).isBlank() || input.taskId().length() > 200) throw new IllegalArgumentException("采集任务标识无效");
        String source = "history".equals(input.source()) ? "history" : "matched";
        String hash = hash(normalize(description));
        String metadata = metadata(input).toString();
        String config = sanitizedConfig(input.configJson());
        String requestedType = new JSONObject(config).optString("requestedJobType", text(input.jobType()));
        if (!Set.of("0", "1901", "1902", "1903").contains(requestedType)) requestedType = "0";
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=1500"); }
                c.setAutoCommit(false);
                try {
                    long now = System.currentTimeMillis();
                    Long jobId = selectLong(c, "SELECT id FROM boss_jd_job WHERE platform='boss' AND encrypt_id=?", encryptId);
                    boolean newJob = jobId == null;
                    if (newJob) {
                        update(c, "INSERT INTO boss_jd_job(encrypt_id,job_name,company_name,city,first_seen,last_seen) VALUES(?,?,?,?,?,?)",
                                encryptId, text(input.jobName()), text(input.companyName()), text(input.city()), now, now);
                        jobId = selectLong(c, "SELECT last_insert_rowid()");
                    }
                    Long previousVersion = selectLong(c, "SELECT current_version_id FROM boss_jd_job WHERE id=?", jobId);
                    Long versionId = selectLong(c, "SELECT id FROM boss_jd_version WHERE job_id=? AND content_hash=?", jobId, hash);
                    boolean newVersion = versionId == null;
                    if (newVersion) {
                        update(c, "INSERT INTO boss_jd_version(job_id,content_hash,description,metadata_json,captured_at) VALUES(?,?,?,?,?)",
                                jobId, hash, description, metadata, now);
                        versionId = selectLong(c, "SELECT last_insert_rowid()");
                    }
                    long order = Objects.requireNonNull(selectLong(c, "SELECT observation_order FROM boss_jd_job WHERE id=?", jobId)) + 1;
                    Long matchId = selectLong(c, "SELECT id FROM boss_jd_match WHERE task_id=? AND job_id=? AND version_id=? AND keyword=? AND search_city=? AND job_type=? AND source=?",
                            input.taskId(), jobId, versionId, text(input.keyword()), text(input.searchCity()), requestedType, source);
                    if (matchId == null) {
                        update(c, """
                            INSERT INTO boss_jd_match(job_id,version_id,task_id,keyword,search_city,actual_city,job_type,
                              config_json,metadata_json,source,matched,rules_version,first_seen,last_seen,observation_order)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """, jobId, versionId, input.taskId(), text(input.keyword()), text(input.searchCity()), text(input.city()),
                                requestedType, config, metadata, source, "history".equals(source) ? 0 : 1, RULE_VERSION, now, now, order);
                    } else {
                        update(c, "UPDATE boss_jd_match SET last_seen=?,observation_order=?,actual_city=?,config_json=?,metadata_json=? WHERE id=?",
                                now, order, text(input.city()), config, metadata, matchId);
                    }
                    update(c, """
                        UPDATE boss_jd_job SET current_version_id=?,job_name=?,company_name=?,city=?,last_seen=?,
                          seen_count=seen_count+?,observation_order=? WHERE id=?
                        """, versionId, text(input.jobName()), text(input.companyName()), text(input.city()), now, matchId == null ? 1 : 0, order, jobId);
                    c.commit();
                    boolean changed = !Objects.equals(previousVersion, versionId);
                    return new CaptureResult(newJob ? "created" : changed ? "updated" : "duplicate", jobId, versionId);
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            } catch (Exception e) {
                failure = new IllegalStateException("JD 留存失败，原投递流程可继续；请检查本地数据库。", e);
                if (!isBusy(e)) throw failure;
                try { Thread.sleep(60L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure; }
            }
        }
        throw failure;
    }

    public List<Map<String, Object>> samples(Scope scope) { return selectSamples(scope, null, false); }

    public List<Map<String, Object>> samplesForTask(String taskId) {
        if (text(taskId).isBlank()) return List.of();
        return selectSamples(ALL, taskId, false);
    }

    private List<Map<String, Object>> selectSamples(Scope requested, String taskId, boolean includeExcluded) {
        requireAvailable();
        Scope scope = requested == null ? ALL : requested;
        List<Object> args = new ArrayList<>();
        StringBuilder conditions = new StringBuilder(includeExcluded ? "1=1" : "j.included<>0");
        if (!scope.includeHistory()) conditions.append(" AND (m.matched=1 OR j.included=1)");
        if (!text(scope.keyword()).isBlank()) { conditions.append(" AND m.keyword=?"); args.add(scope.keyword().trim()); }
        literal(conditions, args, "m.actual_city", scope.city());
        if (!text(scope.jobType()).isBlank()) { conditions.append(" AND m.job_type=?"); args.add(scope.jobType().trim()); }
        if (!text(scope.from()).isBlank()) { conditions.append(" AND m.last_seen>=?"); args.add(boundary(scope.from(), false)); }
        if (!text(scope.to()).isBlank()) { conditions.append(" AND m.last_seen<?"); args.add(boundary(scope.to(), true)); }
        if (!text(scope.from()).isBlank() && !text(scope.to()).isBlank() && boundary(scope.from(), false) >= boundary(scope.to(), true))
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if (scope.jobIds() != null && !scope.jobIds().isEmpty()) {
            if (scope.jobIds().size() > 10_000 || scope.jobIds().stream().anyMatch(id -> id == null || id <= 0))
                throw new IllegalArgumentException("岗位 ID 范围无效");
            // JSON input avoids SQLite's bound-variable ceiling for large explicit selections.
            conditions.append(" AND j.id IN (SELECT value FROM json_each(?))");
            args.add(new org.json.JSONArray(scope.jobIds()).toString());
        }
        if (taskId != null) { conditions.append(" AND m.task_id=? AND m.source<>'history'"); args.add(taskId); }
        String partition = taskId == null ? "m.job_id" : "m.job_id,m.keyword,m.actual_city,m.job_type";
        String sql = """
            WITH chosen AS (
              SELECT m.*,j.encrypt_id,j.first_seen AS job_first_seen,j.last_seen AS job_last_seen,
                j.included,j.seen_count,j.current_version_id,
                ROW_NUMBER() OVER(PARTITION BY %s ORDER BY m.last_seen DESC,m.observation_order DESC,m.id DESC) AS rn
              FROM boss_jd_match m JOIN boss_jd_job j ON j.id=m.job_id WHERE %s
            )
            SELECT c.*,v.description,v.content_hash FROM chosen c
              JOIN boss_jd_version v ON v.id=c.version_id
              WHERE rn=1 ORDER BY c.last_seen DESC,c.id DESC
            """.formatted(partition, conditions);
        return jdbc.query(sql, (rs, row) -> sample(rs), args.toArray());
    }

    public Map<String, Object> list(Scope scope, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数超出范围");
        List<Map<String, Object>> samples = selectSamples(scope, null, true);
        long offset = (long)(page - 1) * size;
        int from = (int)Math.min(offset, samples.size());
        int end = Math.min(from + size, samples.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> sample : samples.subList(from, end)) {
            Map<String, Object> item = new LinkedHashMap<>(sample);
            String description = String.valueOf(item.remove("description"));
            item.put("descriptionLength", description.length());
            item.put("preview", description.substring(0, Math.min(120, description.length())));
            items.add(item);
        }
        return Map.of("items", items, "total", samples.size(), "page", page, "size", size);
    }

    public Map<String, Object> overview(Scope scope) {
        if (!available) return Map.of("available", false, "error", String.valueOf(initializationError), "totalJobs", 0,
                "totalCompanies", 0, "duplicateContents", 0, "terms", List.of());
        List<Map<String, Object>> rows = samples(scope);
        Set<String> companies = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!text(row.get("companyName")).isBlank()) companies.add(text(row.get("companyName")));
            hashes.add(text(row.get("contentHash")));
        }
        for (Map.Entry<String, Pattern> term : TERMS.entrySet()) {
            int count = 0;
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String description = text(row.get("description"));
                Matcher matcher = term.getValue().matcher(description);
                if (matcher.find()) {
                    count++;
                    if (evidence.size() < 5) {
                        int from = Math.max(Math.max(0, description.lastIndexOf('\n', matcher.start()) + 1), matcher.start() - 120);
                        int end = description.indexOf('\n', matcher.end());
                        if (end < 0) end = description.length();
                        String quote = description.substring(from, Math.min(end, from + 300));
                        evidence.add(Map.of("jobId", row.get("jobId"), "versionId", row.get("versionId"), "quote", quote));
                    }
                }
            }
            if (count > 0) terms.add(Map.of("term", term.getKey(), "count", count, "total", rows.size(), "evidence", evidence));
        }
        terms.sort(Comparator.<Map<String, Object>>comparingInt(term -> ((Number)term.get("count")).intValue()).reversed()
                .thenComparing(term -> String.valueOf(term.get("term"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true); result.put("error", null); result.put("totalJobs", rows.size());
        result.put("totalCompanies", companies.size()); result.put("duplicateContents", rows.size() - hashes.size());
        result.put("terms", terms); result.put("dictionaryVersion", "jd-keywords-v1");
        result.put("description", "关键词统计按有效原文岗位计算，不代表岗位必须要求；同岗位同词只计一次。");
        result.put("firstMatchedAt", rows.stream().map(row -> text(row.get("matchedAt"))).min(String::compareTo).orElse(null));
        result.put("lastMatchedAt", rows.stream().map(row -> text(row.get("matchedAt"))).max(String::compareTo).orElse(null));
        return result;
    }

    public Map<String, Object> detail(long jobId) {
        requireAvailable();
        if (jobId <= 0) throw new IllegalArgumentException("岗位 ID 无效");
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT * FROM boss_jd_job WHERE id=?", jobId);
        if (jobs.isEmpty()) return null;
        int inclusion = ((Number)jobs.getFirst().get("included")).intValue();
        List<Map<String, Object>> versions = jdbc.query("SELECT * FROM boss_jd_version WHERE job_id=? ORDER BY captured_at DESC,id DESC", (rs, i) -> {
            Map<String, Object> v = jsonMap(rs.getString("metadata_json"));
            v.put("versionId", rs.getLong("id")); v.put("jobId", jobId);
            v.put("description", rs.getString("description")); v.put("contentHash", rs.getString("content_hash"));
            v.put("capturedAt", iso(rs.getLong("captured_at"))); v.put("createdAt", v.get("capturedAt")); return v;
        }, jobId);
        List<Map<String, Object>> matches = jdbc.query("SELECT * FROM boss_jd_match WHERE job_id=? ORDER BY last_seen DESC,observation_order DESC,id DESC", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", rs.getString("task_id")); m.put("versionId", rs.getLong("version_id"));
            m.put("keyword", rs.getString("keyword")); m.put("searchCity", rs.getString("search_city"));
            m.put("city", rs.getString("actual_city")); m.put("requestedJobType", rs.getString("job_type"));
            m.put("jobType", jsonMap(rs.getString("metadata_json")).get("jobType"));
            m.put("source", rs.getString("source")); m.put("matched", rs.getInt("matched") == 1);
            m.put("matchStatus", rs.getInt("matched") == 1 ? "matched" : inclusion == 1 ? "manual" : "unconfirmed");
            m.put("config", jsonMap(rs.getString("config_json"))); m.put("rulesVersion", rs.getString("rules_version"));
            m.put("firstSeen", iso(rs.getLong("first_seen"))); m.put("lastSeen", iso(rs.getLong("last_seen")));
            m.put("matchedAt", iso(rs.getLong("last_seen"))); return m;
        }, jobId);
        Map<String, Object> raw = jobs.getFirst();
        List<String> lastMetadata = jdbc.queryForList("SELECT metadata_json FROM boss_jd_match WHERE job_id=? ORDER BY last_seen DESC,observation_order DESC,id DESC LIMIT 1", String.class, jobId);
        Map<String, Object> job = lastMetadata.isEmpty() ? new LinkedHashMap<>() : jsonMap(lastMetadata.getFirst());
        job.put("jobId", jobId); job.put("encryptId", raw.get("encrypt_id")); job.put("jobName", raw.get("job_name"));
        job.put("companyName", raw.get("company_name")); job.put("city", raw.get("city"));
        job.put("currentVersionId", raw.get("current_version_id")); job.put("seenCount", raw.get("seen_count"));
        job.put("included", ((Number)raw.get("included")).intValue());
        job.put("firstSeen", iso(((Number)raw.get("first_seen")).longValue()));
        job.put("lastSeen", iso(((Number)raw.get("last_seen")).longValue()));
        if (!matches.isEmpty()) {
            job.put("source", matches.getFirst().get("source"));
            job.put("requestedJobType", matches.getFirst().get("requestedJobType"));
        }
        job.put("jobUrl", "https://www.zhipin.com/job_detail/" + raw.get("encrypt_id") + ".html");
        return Map.of("job", job, "versions", versions, "matches", matches);
    }

    public synchronized void setIncluded(long jobId, boolean included) {
        requireAvailable();
        if (jdbc.update("UPDATE boss_jd_job SET included=? WHERE id=?", included ? 1 : 0, jobId) == 0)
            throw new IllegalArgumentException("岗位不存在");
    }

    /** Explicit local import only: legacy delivery status is not a match certificate. */
    public Map<String, Object> importHistory() {
        requireAvailable();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='boss_data'", Integer.class) == 0)
            return Map.of("created", 0, "updated", 0, "duplicate", 0, "skipped", 0);
        int created = 0, updated = 0, duplicate = 0, skipped = 0;
        long cursor = 0;
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("SELECT * FROM boss_data WHERE id>? ORDER BY id LIMIT 200", cursor);
            if (batch.isEmpty()) break;
            for (Map<String, Object> row : batch) {
                cursor = ((Number)row.get("id")).longValue();
                String id = text(row.get("encrypt_id"));
                // Never let older legacy text replace a newer live-captured version.
                if (!id.isBlank() && jdbc.queryForObject("SELECT COUNT(*) FROM boss_jd_job WHERE encrypt_id=? AND platform='boss'", Integer.class, id) > 0) {
                    duplicate++; continue;
                }
                try {
                    CaptureResult result = capture(new Capture("legacy-" + cursor, "", "", "{}", id,
                            text(row.get("job_name")), text(row.get("company_name")), text(row.get("location")),
                            text(row.get("salary")), text(row.get("experience")), text(row.get("degree")), "",
                            text(row.get("job_description")), "history"));
                    switch (result.outcome()) { case "created" -> created++; case "updated" -> updated++; default -> duplicate++; }
                } catch (IllegalArgumentException e) { skipped++; }
            }
        }
        return Map.of("created", created, "updated", updated, "duplicate", duplicate, "skipped", skipped);
    }

    private static Map<String, Object> sample(ResultSet rs) throws SQLException {
        Map<String, Object> result = jsonMap(rs.getString("metadata_json"));
        result.put("jobId", rs.getLong("job_id")); result.put("versionId", rs.getLong("version_id"));
        result.put("encryptId", rs.getString("encrypt_id")); result.put("description", rs.getString("description"));
        result.put("contentHash", rs.getString("content_hash")); result.put("firstSeen", iso(rs.getLong("job_first_seen")));
        result.put("lastSeen", iso(rs.getLong("job_last_seen"))); result.put("matchedAt", iso(rs.getLong("last_seen")));
        result.put("source", rs.getString("source")); result.put("keyword", rs.getString("keyword"));
        result.put("searchCity", rs.getString("search_city")); result.put("requestedJobType", rs.getString("job_type"));
        result.put("matched", rs.getInt("matched") == 1); result.put("included", rs.getInt("included"));
        result.put("matchStatus", rs.getInt("matched") == 1 ? "matched" : rs.getInt("included") == 1 ? "manual" : "unconfirmed");
        result.put("seenCount", rs.getInt("seen_count")); result.put("jobUrl", "https://www.zhipin.com/job_detail/" + rs.getString("encrypt_id") + ".html");
        return result;
    }

    private static JSONObject metadata(Capture input) {
        return new JSONObject().put("jobName", text(input.jobName())).put("companyName", text(input.companyName()))
                .put("city", text(input.city())).put("salary", text(input.salary())).put("experience", text(input.experience()))
                .put("degree", text(input.degree())).put("jobType", text(input.jobType()));
    }

    private static String sanitizedConfig(String input) {
        JSONObject raw;
        try { raw = new JSONObject(input == null ? "{}" : input); }
        catch (RuntimeException e) { throw new IllegalArgumentException("JD 筛选快照无效"); }
        JSONObject safe = new JSONObject();
        for (String key : List.of("keyword", "keywords", "cityCode", "searchCity", "jobType", "requestedJobType", "jobTypeVerification", "salary", "experience", "degree", "scale", "stage", "industry", "filterDeadHR", "filterHeadhunter", "rulesVersion", "verifiedFields", "unverifiedFields")) {
            if (raw.has(key)) safe.put(key, raw.get(key));
        }
        if (safe.toString().length() > 30_000) throw new IllegalArgumentException("JD 筛选快照过长");
        return safe.toString();
    }

    private static Map<String, Object> jsonMap(String json) { return new LinkedHashMap<>(new JSONObject(json).toMap()); }
    private static String text(Object value) { return value == null || value == JSONObject.NULL ? "" : value.toString().trim(); }
    private static String normalize(String description) { return description.replace("\r\n", "\n").replace('\r', '\n').strip(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String iso(long millis) { return Instant.ofEpochMilli(millis).toString(); }
    private static void literal(StringBuilder sql, List<Object> args, String column, String value) {
        if (!text(value).isBlank()) { sql.append(" AND instr(lower(").append(column).append("),lower(?))>0"); args.add(value.trim()); }
    }
    private static long boundary(String value, boolean end) {
        try {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate day = LocalDate.parse(value);
                return (end ? day.plusDays(1) : day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            }
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeException e) { throw new IllegalArgumentException("时间范围格式无效，请使用日期或带时区的时间"); }
    }
    private static boolean isBusy(Throwable e) {
        for (Throwable next = e; next != null; next = next.getCause()) {
            if (next.getMessage() != null && (next.getMessage().contains("SQLITE_BUSY") || next.getMessage().contains("database is locked"))) return true;
        }
        return false;
    }
    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            return s.executeUpdate();
        }
    }
    private static Long selectLong(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }
    private static Map<String, Pattern> terms() {
        Map<String, Pattern> terms = new LinkedHashMap<>();
        for (String word : List.of("Python", "Java", "JavaScript", "TypeScript", "SQL", "MySQL", "PostgreSQL", "Redis", "Docker", "Kubernetes", "Linux", "Git", "React", "Vue", "Spring", "FastAPI", "LangChain", "LangGraph", "RAG", "LLM", "Agent", "Prompt", "PyTorch", "TensorFlow", "AIGC")) {
            terms.put(word, Pattern.compile("(?i)(?<![a-z0-9_])" + Pattern.quote(word) + "(?![a-z0-9_])"));
        }
        terms.put("大模型", Pattern.compile("大模型")); terms.put("智能体", Pattern.compile("智能体"));
        terms.put("提示词", Pattern.compile("提示词")); terms.put("数据分析", Pattern.compile("数据分析"));
        terms.put("产品设计", Pattern.compile("产品设计")); terms.put("需求分析", Pattern.compile("需求分析"));
        terms.put("机器学习", Pattern.compile("机器学习")); terms.put("深度学习", Pattern.compile("深度学习"));
        terms.put("沟通协作", Pattern.compile("沟通|协作"));
        return Collections.unmodifiableMap(terms);
    }
}
