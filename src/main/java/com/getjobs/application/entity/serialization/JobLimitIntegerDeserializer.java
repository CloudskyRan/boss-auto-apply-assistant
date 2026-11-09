package com.getjobs.application.entity.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/** 数量限制只接受 JSON 整数，避免 Jackson 将小数静默截断。负数由配置控制器校验。 */
public class JobLimitIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.isExpectedNumberIntToken()) {
            context.reportInputMismatch(Integer.class, "岗位数量限制必须为 0 或正整数（0 表示不限）");
        }
        return parser.getIntValue();
    }
}
