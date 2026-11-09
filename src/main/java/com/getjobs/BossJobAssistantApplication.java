package com.getjobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boss 求职助手启动类，仅提供 Boss 直聘的求职与投递管理。
 *
 * @author GetJobs
 */
@SpringBootApplication(scanBasePackages = "com.getjobs")
@EnableScheduling
@EnableAsync
public class BossJobAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(BossJobAssistantApplication.class, args);
    }
}
