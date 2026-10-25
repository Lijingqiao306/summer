package org.example.ykd_summer.service;

/**
 * 图片服务接口 —— 文生图 + 图片识别
 */
public interface ImageService {

    /**
     * 根据文字描述生成图片
     *
     * @param prompt 图片描述，如"一只坐在窗边的橘猫"
     * @param style  风格（可为 null，由模型自行发挥）
     * @param size   尺寸（可为 null，使用默认配置）
     * @return 生成后的图片 URL（有效期 24 小时）
     */
    String generateImage(String prompt, String style, String size);

    /**
     * 下载图片并返回二进制数据
     *
     * @param imageUrl 图片的公网可访问 URL
     * @return 图片的二进制数据
     */
    byte[] downloadImage(String imageUrl);

    /**
     * 识别并描述图片内容
     *
     * @param imageUrl 图片的公网可访问 URL
     * @return 图片的文字描述
     */
    String describeImage(String imageUrl);
}
