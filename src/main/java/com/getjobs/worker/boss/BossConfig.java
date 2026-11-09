package com.getjobs.worker.boss;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 *
 * Boss配置数据类
 * 配置加载由 BossConfigLoaderService 负责
 */
@Data
public class BossConfig {
    /** 在开始真实投递前检查配置，避免点击“立即沟通”后才发现消息为空。 */
    public void validateForDelivery() {
        if (maxSearchJobs != null && maxSearchJobs < 0) {
            throw new IllegalArgumentException("搜索岗位上限必须是 0 或正整数");
        }
        if (maxDeliveryJobs != null && maxDeliveryJobs < 0) {
            throw new IllegalArgumentException("本次投递上限必须是 0 或正整数");
        }
        if (jobType == null || jobType.size() != 1) {
            throw new IllegalArgumentException("职位类型有且仅能选择一个");
        }
        if (keywords == null || keywords.isEmpty()
                || keywords.stream().allMatch(keyword -> keyword == null || keyword.isBlank())) {
            throw new IllegalArgumentException("请填写搜索关键词并保存配置后再开始投递");
        }
        // AI 模式也需要固定招呼语，作为 AI 请求失败或返回空文本时的备用信息。
        if (!Boolean.TRUE.equals(debugger) && (sayHi == null || sayHi.isBlank())) {
            throw new IllegalArgumentException("请填写固定招呼语并保存配置后再开始投递（AI模式也需备用招呼语）");
        }
    }

    /**
     * 用于打招呼的语句
     */
    private String sayHi;

    /**
     * 开发者模式
     */
    private Boolean debugger;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /** 本次最多检查的候选岗位数（包含过滤和失败），null/0 表示不限。 */
    private Integer maxSearchJobs;

    /** 本次最多尝试发起沟通的岗位数，null/0 表示不限。 */
    private Integer maxDeliveryJobs;

    /**
     * 城市编码
     */
    private List<String> cityCode;

    /**
     * 自定义城市编码映射
     */
    private Map<String, String> customCityCode;

    /**
     * 行业列表
     */
    private List<String> industry;

    /**
     * 工作经验要求
     */
    private List<String> experience;

    /** 工作类型编码列表，投递时必须只有一个值（例如 1901=全职）。 */
    private List<String> jobType;

    /**
     * 薪资范围（多选）
     */
    private java.util.List<String> salary;

    /**
     * 学历要求列表
     */
    private List<String> degree;

    /**
     * 公司规模列表
     */
    private List<String> scale;

    /**
     * 公司融资阶段列表
     */
    private List<String> stage;

    /**
     * 是否开放AI检测
     */
    private Boolean enableAI;

    /**
     * 是否过滤不活跃hr
     */
    private Boolean filterDeadHR;

    /**
     * 是否发送图片简历
     */
    private Boolean sendImgResume;

    /**
     * 目标薪资
     */
    private List<Integer> expectedSalary;

    /**
     * 等待时间
     */
    private String waitTime;

    /**
     * HR未上线状态
     */
    private List<String> deadStatus;
}
