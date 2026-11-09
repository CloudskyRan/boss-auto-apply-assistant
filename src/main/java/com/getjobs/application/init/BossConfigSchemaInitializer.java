package com.getjobs.application.init;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** 兼容已有 Boss 配置表，新增数量限制字段而保留用户配置、登录态和投递记录。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BossConfigSchemaInitializer {

    private final DataSource dataSource;

    @PostConstruct
    public void ensureLimitColumns() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            Set<String> columns = new HashSet<>();
            try (ResultSet result = statement.executeQuery("PRAGMA table_info(boss_config)")) {
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
            }
            if (columns.isEmpty()) {
                throw new IllegalStateException("缺少 boss_config 表，请检查已有的 db/getjobs.db 数据库文件");
            }
            if (!columns.contains("max_search_jobs")) {
                statement.execute("ALTER TABLE boss_config ADD COLUMN max_search_jobs INTEGER NOT NULL DEFAULT 0");
                log.info("已为 boss_config 增加 max_search_jobs 字段（默认不限）");
            }
            if (!columns.contains("max_delivery_jobs")) {
                statement.execute("ALTER TABLE boss_config ADD COLUMN max_delivery_jobs INTEGER NOT NULL DEFAULT 0");
                log.info("已为 boss_config 增加 max_delivery_jobs 字段（默认不限）");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Boss 配置表数量限制字段初始化失败，服务无法安全启动：" + e.getMessage(), e);
        }
    }
}
