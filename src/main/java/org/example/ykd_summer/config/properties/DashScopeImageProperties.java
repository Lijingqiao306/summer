package org.example.ykd_summer.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DashScope 原生图片服务配置属性，绑定 yml 中的 dashscope.image.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashscope.image")
public class DashScopeImageProperties {

    /** DashScope API Key，优先用 DASHSCOPE_API_KEY，回退到 OPENAI_API_KEY */
    private String apiKey;

    /** 文生图配置 */
    private Generation generation = new Generation();

    /** 图片识别配置 */
    private Recognition recognition = new Recognition();

    @Data
    public static class Generation {
        /** 文生图模型，默认 z-image-turbo（速度快、成本低） */
        private String model = "z-image-turbo";
        /** 出图尺寸，默认 1024*1024 */
        private String size = "1024*1024";
    }

    @Data
    public static class Recognition {
        /** 多模态识别模型，默认 qwen-vl-plus */
        private String model = "qwen-vl-plus";
    }
}
