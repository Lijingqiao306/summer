package org.example.ykd_summer.service;

import org.springframework.core.io.Resource;

/**
 * 语音识别服务接口，将音频转为文字。
 */
public interface VoiceService {

    /**
     * TTS 合成结果。
     *
     * @param format 音频格式（mp3 / wav），合成失败时为 null
     * @param bytes  音频二进制数据，合成失败时为 null
     */
    record VoiceResult(String format, byte[] bytes) {
        public boolean success() {
            return bytes != null && bytes.length > 0 && format != null;
        }
    }

    /**
     * 调用 DashScope Paraformer ASR 将语音转写为文字。
     *
     * @param audioResource 音频数据（Spring Resource，支持 ByteArrayResource 等）
     * @return 转写后的文字，失败时返回 null
     */
    String transcribe(Resource audioResource);

    /**
     * 调用 DashScope CosyVoice TTS 将文字合成为语音，内置降级链（mp3 → wav → null）。
     * 用于作为文件附件发送场景。
     *
     * @param text 要合成的文字
     * @return 合成结果（含格式和音频数据），全部格式失败时返回 success()==false
     */
    VoiceResult synthesize(String text);
}
