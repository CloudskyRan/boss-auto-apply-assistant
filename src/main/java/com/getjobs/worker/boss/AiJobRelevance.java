package com.getjobs.worker.boss;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断 AI 类搜索词返回的岗位是否真的以 AI 工作内容为重点。
 *
 * <p>Boss 的 query 参数是全文/模糊搜索：例如“AI应用”会把“产品实习生（教育业务 / AI应用）”
 * 也返回。该类只在 AI 类关键词下启用，避免改变普通关键词（Java、设计等）的原有语义。</p>
 */
public final class AiJobRelevance {
    private AiJobRelevance() {}

    private static final Pattern AI_KEYWORD = Pattern.compile(
            "(?i)(?<![a-z0-9])(ai|aigc|llm|rag|agent)(?![a-z0-9])"
                    + "|人工智能|机器学习|深度学习|大模型|生成式|智能体|自然语言|计算机视觉");
    private static final Pattern AI_SIGNAL = Pattern.compile(
            "(?i)(?<![a-z0-9])(ai|aigc|llm|rag|agent|nlp|cv)(?![a-z0-9])"
                    + "|人工智能|机器学习|深度学习|大模型|生成式|智能体|自然语言|计算机视觉");
    /** 需要岗位标题承担的明确工作职责；单独出现“产品/实习”不算 AI 岗位。 */
    private static final Pattern STRONG_ROLE = Pattern.compile(
            "开发|研发|工程|算法|模型|架构|平台|数据|科学家|训练|推理|程序|软件"
                    + "|产品经理|产品负责人|产品总监|产品专家|解决方案|技术负责人|技术专家"
                    + "|应用|应用助理");
    private static final Pattern NON_CORE_ROLE = Pattern.compile(
            "销售|社媒|运营|讲师|教师|老师|课程|教研|测评|客服|招生|商务拓展|\\bBD\\b|主播|编辑");
    private static final Pattern EDUCATION = Pattern.compile(
            "教育|课程|教研|教师|老师|培训|学科|少儿|学校|教学|教务|班主任|招生|学习顾问");
    private static final Pattern GENERIC_EDUCATION_PRODUCT = Pattern.compile(
            "产品实习生|产品助理|产品运营");

    public record Decision(boolean applies, boolean matched, String reason) {}

    /**
     * @return applies=false 表示不是 AI 类搜索词，应沿用 Boss 原始搜索语义。
     */
    public static Decision evaluate(String keyword, String title, String description) {
        String query = normalize(keyword);
        if (query.isEmpty() || !AI_KEYWORD.matcher(query).find()) {
            return new Decision(false, true, "非AI关键词，沿用Boss搜索结果");
        }

        String jobTitle = normalize(title);
        String jobDescription = normalize(description);
        boolean titleAi = AI_SIGNAL.matcher(jobTitle).find();
        boolean descriptionAi = AI_SIGNAL.matcher(jobDescription).find();
        boolean strongRole = STRONG_ROLE.matcher(jobTitle).find();
        boolean education = EDUCATION.matcher(jobTitle).find();

        // 标题明确把 AI 放在销售、社媒、课程/讲师等非研发/产品职责中时，
        // 不因“AI应用”只是业务场景或培训内容而进入投递队列。
        boolean explicitProductRole = Pattern.compile("产品经理|产品负责人|产品总监|产品专家").matcher(jobTitle).find();
        if (NON_CORE_ROLE.matcher(jobTitle).find() && !explicitProductRole) {
            return new Decision(true, false, "岗位职责属于销售/运营/课程等非AI核心岗位");
        }

        // 教育/课程等岗位只有在标题明确写出 AI 且同时有技术/产品职责时才保留。
        // 这样“产品实习生（教育业务 / AI应用）”会被排除，而“AI产品经理（教育智能化）”仍可保留。
        if (education && (!titleAi || !strongRole)) {
            return new Decision(true, false, "教育/课程岗位未体现明确的AI技术或产品职责");
        }
        if (education && GENERIC_EDUCATION_PRODUCT.matcher(jobTitle).find()
                && !jobTitle.contains("产品经理")) {
            return new Decision(true, false, "教育岗位为泛产品实习/运营职责");
        }
        // AI 相关岗位必须在标题表达 AI；仅 JD 偶然提到 AI 的普通岗位不投递。
        if (titleAi && strongRole) {
            return new Decision(true, true, "岗位标题含AI方向及明确职责");
        }
        // 详情接口没有返回职位描述或 AI 线索时，保留候选交给原有流程，
        // 避免因页面数据不完整误杀岗位；一旦出现 AI 线索则按上面的规则过滤。
        if (!titleAi && !descriptionAi && jobDescription.isEmpty()) {
            return new Decision(true, true, "详情缺少AI线索，保留候选");
        }
        return new Decision(true, false, "标题未体现明确的AI岗位方向");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u3000', ' ')
                .replace('（', '(')
                .replace('）', ')')
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
