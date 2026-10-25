package org.example.ykd_summer.tools;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.tika.Tika;
import org.example.ykd_summer.dto.ReceivedContent;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 文件 Agent 可调用的工具集合。
 * <p>
 * 提供文档读取（Tika 提取 → 分块 → Embedding → VectorStore）、
 * 语义检索、全文拼接、Word/PDF 生成等能力。
 * <p>
 * ThreadLocal 管理会话级状态：FILE_MAP（绑定文件）、STORE_MAP（向量库）、
 * CHUNK_MAP（分块文本）、GENERATED_FILE_HOLDER（生成文件）。
 * ConcurrentHashMap 提供跨线程回退路径（Spring AI @Tool 可能在独立线程执行）。
 */
@Slf4j
@Component
public class FileTools {

    private final EmbeddingModel embeddingModel;
    private final Tika tika = new Tika();

    // ==================== 分块与检索参数 ====================

    private static final int CHUNK_SIZE = 1000;
    private static final int CHUNK_OVERLAP = 200;
    /** 动态分块的最小块数（短文档保底） */
    private static final int MIN_CHUNKS = 5;
    /** 动态分块的最大块数（硬上限，超出拒绝处理） */
    private static final int MAX_CHUNKS_HARD = 20;
    /** 文档最大字符数 = MAX_CHUNKS_HARD × CHUNK_SIZE */
    private static final int MAX_TEXT_LENGTH = MAX_CHUNKS_HARD * CHUNK_SIZE;
    private static final double SIMILARITY_THRESHOLD = 0.1;
    private static final int FULL_TEXT_LIMIT = 60000;

    /** 文件会话过期时间（15 分钟无操作后自动清理） */
    private static final long FILE_SESSION_TTL_MS = 15 * 60 * 1000L;

    /** PDF 中文字体候选路径列表（Windows / macOS / Linux） */
    private static final String[] CJK_FONT_CANDIDATES = {
            "C:\\Windows\\Fonts\\msyh.ttc",       // 微软雅黑 (Windows)
            "C:\\Windows\\Fonts\\simsun.ttc",     // 宋体 (Windows)
            "C:\\Windows\\Fonts\\simhei.ttf",     // 黑体 (Windows)
            "/System/Library/Fonts/PingFang.ttc", // 苹方 (macOS)
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", // Noto (Linux)
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    };

    // ==================== ThreadLocal 会话状态 ====================

    private static final ThreadLocal<Map<String, ReceivedContent>> FILE_MAP = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, SimpleVectorStore>> STORE_MAP = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, List<String>>> CHUNK_MAP = new ThreadLocal<>();
    private static final ThreadLocal<ReceivedContent> GENERATED_FILE_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_KEY = new ThreadLocal<>();

    // ==================== ConcurrentHashMap 跨线程回退 ====================

    private static final ConcurrentHashMap<String, Map<String, ReceivedContent>> FILE_CROSS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, SimpleVectorStore>> STORE_CROSS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, List<String>>> CHUNK_CROSS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReceivedContent> GENERATED_CROSS = new ConcurrentHashMap<>();

    /** 每个文件会话的最后活跃时间戳（ms），用于过期清理 */
    private static final ConcurrentHashMap<String, Long> LAST_FILE_ACTIVITY = new ConcurrentHashMap<>();

    // ==================== 后台会话清理 ====================

    /** 后台清理线程（JVM 生命周期内常驻，跟随进程退出自动释放） */
    @SuppressWarnings("resource")
    private static final ScheduledExecutorService SESSION_CLEANER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-session-cleaner");
                t.setDaemon(true);
                return t;
            });

    static {
        SESSION_CLEANER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long cutoff = now - FILE_SESSION_TTL_MS;
            for (var it = LAST_FILE_ACTIVITY.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getValue() < cutoff) {
                    String key = entry.getKey();
                    FILE_CROSS.remove(key);
                    STORE_CROSS.remove(key);
                    CHUNK_CROSS.remove(key);
                    GENERATED_CROSS.remove(key);
                    it.remove();
                    log.debug("FileTools: 过期会话已清理, sessionKey={}", key);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 跨线程回退通道：Spring AI @Tool 可能在独立线程执行，
     * ThreadLocal 无法跨线程传递，故用 AtomicReference 兜底。
     * 由于 executeFileStep 是同步阻塞的，同一时刻最多一个工具在生成文件，无竞态风险。
     * 用于处理最后生成的单个文件
     */
    private static final java.util.concurrent.atomic.AtomicReference<ReceivedContent> LAST_GENERATED_FILE =
            new java.util.concurrent.atomic.AtomicReference<>();

    public FileTools(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // ==================== 静态绑定 / 清理方法 ====================

    /**
     * 绑定当前会话的用户上传文件。
     * <p>
     * 首次调用时创建新会话和 ThreadLocal/ConcurrentHashMap 条目；
     * 后续调用（如同会话中多轮上传文档）会追加文件到已有 map，
     * 同名文件将被覆盖。STORE_MAP / CHUNK_MAP 不会被重置，
     * 已有的文档缓存（readDocument 向量库）继续有效。
     */
    public static void bindFiles(List<ReceivedContent> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        // 尝试复用已有会话
        String sessionKey = SESSION_KEY.get();

        // 已有会话但已过期：先清理，再重建
        if (sessionKey != null && isExpired(sessionKey)) {
            log.info("FileTools: 文件会话已过期，自动清理, sessionKey={}", sessionKey);
            clearSession();
            sessionKey = null;
        }

        Map<String, ReceivedContent> existingMap = FILE_MAP.get();

        if (sessionKey == null || existingMap == null) {
            // 首次绑定或过期重建：创建新会话
            sessionKey = UUID.randomUUID().toString();
            SESSION_KEY.set(sessionKey);
            existingMap = new LinkedHashMap<>();
            FILE_MAP.set(existingMap);
            FILE_CROSS.put(sessionKey, existingMap);
            STORE_MAP.set(new LinkedHashMap<>());
            CHUNK_MAP.set(new LinkedHashMap<>());
        }
        // 已存在会话：直接追加文件到已有 map

        for (int i = 0; i < files.size(); i++) {
            ReceivedContent f = files.get(i);
            String name = f.fileName() != null ? f.fileName() : "file_" + i;
            existingMap.put(name, f);
        }

        // 刷新最后活跃时间
        LAST_FILE_ACTIVITY.put(sessionKey, System.currentTimeMillis());

        log.info("FileTools: 文件已绑定, count={}, sessionKey={}",
                existingMap.size(), sessionKey);
    }

    /**
     * 获取当前会话已绑定文件的摘要信息，供 Router / FILE Agent 感知跨轮文件上下文。
     * <p>
     * 返回格式如："当前会话已绑定以下文件：\n  - 大唐盛世诗词知识文档.docx（docx）\n  - 宋代诗词知识文档.docx（docx）"
     *
     * @return 文件摘要字符串，若无绑定文件则返回 null
     */
    public static String getBoundFilesSummary() {
        String sessionKey = SESSION_KEY.get();
        Map<String, ReceivedContent> fileMap;
        if (sessionKey != null) {
            fileMap = FILE_CROSS.get(sessionKey);
        } else {
            fileMap = FILE_MAP.get();
        }
        if (fileMap == null || fileMap.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("\n[当前会话已绑定的文件：");
        int count = 0;
        for (Map.Entry<String, ReceivedContent> entry : fileMap.entrySet()) {
            if (count > 0) sb.append("，");
            sb.append(entry.getKey());
            String mime = entry.getValue().mimeType();
            if (mime != null) {
                sb.append("（").append(mimeTypeToShortName(mime)).append("）");
            }
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    /** MIME 类型 → 简短后缀名 */
    private static String mimeTypeToShortName(String mimeType) {
        if (mimeType == null) return "未知";
        return switch (mimeType) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "text/markdown" -> "md";
            default -> "文件";
        };
    }

    /** 清理当前会话的所有 ThreadLocal 和 ConcurrentHashMap 条目 */
    public static void clearSession() {
        FILE_MAP.remove();
        STORE_MAP.remove();
        CHUNK_MAP.remove();
        GENERATED_FILE_HOLDER.remove();
        LAST_GENERATED_FILE.set(null);
        String key = SESSION_KEY.get();
        if (key != null) {
            FILE_CROSS.remove(key);
            STORE_CROSS.remove(key);
            CHUNK_CROSS.remove(key);
            GENERATED_CROSS.remove(key);
            LAST_FILE_ACTIVITY.remove(key);
        }
        SESSION_KEY.remove();
        log.debug("FileTools: 会话已清理");
    }

    /** 取出生成的文档文件（ThreadLocal → AtomicReference 跨线程回退 → ConcurrentHashMap） */
    public static ReceivedContent consumeGeneratedFile()
    {
        // 1) 同线程快速路径
        ReceivedContent rc = GENERATED_FILE_HOLDER.get();
        GENERATED_FILE_HOLDER.remove();
        if (rc != null) {
            return rc;
        }
        // 2) 跨线程回退：AtomicReference（@Tool 在独立线程执行时）
        rc = LAST_GENERATED_FILE.getAndSet(null);
        if (rc != null) {
            return rc;
        }
        // 3) 跨线程回退：ConcurrentHashMap（SESSION_KEY 对齐时）
        String key = SESSION_KEY.get();
        if (key != null) {
            return GENERATED_CROSS.remove(key);
        }
        return null;
    }

    // ==================== 辅助方法 ====================

    /** 根据文件名查找对应的 ReceivedContent（精确 → 包含匹配 → 兜底取第一个） */
    private ReceivedContent findFile(String fileName) {
        Map<String, ReceivedContent> map = resolve(FILE_MAP, FILE_CROSS);
        if (map == null || map.isEmpty()) return null;
        ReceivedContent rc = map.get(fileName);
        if (rc != null) return rc;
        for (Map.Entry<String, ReceivedContent> e : map.entrySet()) {
            if (e.getKey().contains(fileName) || fileName.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** ThreadLocal → ConcurrentHashMap 回退解析 */
    private <V> V resolve(ThreadLocal<V> tl, ConcurrentHashMap<String, V> cross) {
        V v = tl.get();
        if (v != null) return v;
        String key = SESSION_KEY.get();
        if (key != null) return cross.get(key);
        return null;
    }

    /** 判断指定 session 是否已过期 */
    private static boolean isExpired(String sessionKey) {
        Long lastActive = LAST_FILE_ACTIVITY.get(sessionKey);
        if (lastActive == null) return false;
        return System.currentTimeMillis() - lastActive > FILE_SESSION_TTL_MS;
    }

    /**
     * 供外部工具（如 MailSendTools）查找已绑定的文件数据，用于邮件附件等场景。
     * <p>
     * 按优先级查找：ThreadLocal（同线程）→ SESSION_KEY → 遍历所有活跃会话。
     * 文件名支持精确匹配和包含匹配。
     *
     * @param fileName 要查找的文件名
     * @return 匹配的 ReceivedContent，未找到返回 null
     */
    public static ReceivedContent findFileAsAttachment(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;

        // 1) 优先 ThreadLocal + SESSION_KEY（同线程快速路径）
        String sessionKey = SESSION_KEY.get();
        Map<String, ReceivedContent> map = null;
        if (sessionKey != null) {
            map = FILE_CROSS.get(sessionKey);
        }
        if (map == null) {
            map = FILE_MAP.get();
        }
        if (map != null) {
            ReceivedContent rc = findInMap(map, fileName);
            if (rc != null) return rc;
        }

        // 2) 跨线程回退：遍历所有活跃会话
        for (Map<String, ReceivedContent> sessionMap : FILE_CROSS.values()) {
            ReceivedContent rc = findInMap(sessionMap, fileName);
            if (rc != null) return rc;
        }

        return null;
    }

    /** 在单个 map 中查找文件（精确 → 包含匹配） */
    private static ReceivedContent findInMap(Map<String, ReceivedContent> map, String fileName) {
        if (map == null || map.isEmpty()) return null;
        ReceivedContent rc = map.get(fileName);
        if (rc != null) return rc;
        for (Map.Entry<String, ReceivedContent> e : map.entrySet()) {
            if (e.getKey().contains(fileName) || fileName.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 使用 Tika 提取文本 */
    private String extractText(ReceivedContent file) {
        byte[] bytes = file.toBytes();
        if (bytes == null || bytes.length == 0) return "";
        try {
            return tika.parseToString(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.error("Tika 文本提取失败: fileName={}", file.fileName(), e);
            return "";
        }
    }

    /** 将文本按句子/段落边界分块，超出硬上限返回 null */
    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= CHUNK_SIZE) return List.of(text);

        // 估算所需块数，超过硬上限则拒绝处理
        int estimatedChunks = text.length() / CHUNK_SIZE;
        if (estimatedChunks > MAX_CHUNKS_HARD) {
            return null;
        }

        // 动态计算最大块数：[MIN_CHUNKS, MAX_CHUNKS_HARD]
        int maxChunks = Math.max(MIN_CHUNKS, estimatedChunks);

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int bp = text.lastIndexOf('。', end);
                if (bp > start + CHUNK_SIZE / 2) {
                    end = bp + 1;
                } else {
                    bp = text.lastIndexOf('\n', end);
                    if (bp > start + 200) end = bp + 1;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    /** 列出已绑定文件名 */
    private String listFileNames() {
        Map<String, ReceivedContent> map = resolve(FILE_MAP, FILE_CROSS);
        if (map == null || map.isEmpty()) return "无";
        return String.join("、", map.keySet());
    }

    // ==================== 中文字体加载 ====================

    /**
     * 从系统路径中查找并加载一个支持中文的 TrueType 字体。
     * <p>
     * 按 CJK_FONT_CANDIDATES 顺序查找，找到第一个可用字体即加载并缓存。
     * 所有候选字体均不可用时返回 null，调用方应降级处理。
     */
    private static PDFont loadCjkFont() {
        for (String path : CJK_FONT_CANDIDATES) {
            Path p = Paths.get(path);
            if (!Files.isReadable(p)) {
                continue;
            }
            File fontFile = p.toFile();

            // 对于 .ttc 文件，PDFBox 3.x 需通过 TrueTypeCollection 提取子字体；
            // 提取后仍需写入临时文件再加载，复杂度较高。暂时跳过 .ttc，
            // 依靠后续 .ttf 字体 + showTextSafe 兜底来保证渲染正确性。
            if (path.toLowerCase().endsWith(".ttc")) {
                continue;
            }
            try (PDDocument tmpDoc = new PDDocument()) {
                PDFont font = PDType0Font.load(tmpDoc, fontFile);
                log.info("PDF 中文字体加载成功: {}", path);
                return font;
            } catch (IOException e) {
                log.warn("PDF 中文字体加载失败: {} ({})", path, e.getMessage());
            }
        }
        log.warn("未找到任何可用的中文字体，PDF 将使用内置字体（不支持中文）");
        return null;
    }

    // ==================== @Tool 方法 ====================

    @Tool(description = "读取并解析文档文件（PDF、Word、TXT 等），自动向量化。需最先调用。")
    public String readDocument(
            @ToolParam(description = "文件名，如 report.docx、summary.pdf") String fileName) {
        ReceivedContent file = findFile(fileName);
        if (file == null) {
            return "文件「" + fileName + "」未找到。已上传的文件有：" + listFileNames() +
                    "，请确认文件名后重试。";
        }

        // 缓存检查：如果该文件已有向量库，说明已读取过，跳过重复解析
        Map<String, SimpleVectorStore> cachedStores = resolve(STORE_MAP, STORE_CROSS);
        if (cachedStores != null && cachedStores.containsKey(fileName)) {
            log.info("readDocument: 缓存命中, fileName={}", fileName);
            return String.format("文件「%s」已在之前读取并索引完毕，无需重复读取。可直接使用 searchDocument 或 summarizeDocument 查询其内容。",
                    fileName);
        }

        try {
            String text = extractText(file);
            if (text.isBlank()) {
                return "文件「" + fileName + "」未能提取到文本内容，可能为纯图片扫描件或空文件。";
            }

            List<String> chunks = chunkText(text);
            if (chunks == null) {
                return String.format("文件「%s」文本过长（%d 字符，超过 %d 字符上限），无法处理。请分割文档后重新上传。",
                        fileName, text.length(), MAX_TEXT_LENGTH);
            }

            // 向量化存储
            SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("fileName", fileName);
                meta.put("chunkIndex", String.valueOf(i));
                docs.add(new Document(chunks.get(i), meta));
            }
            store.add(docs);

            // 保存到 ThreadLocal + ConcurrentHashMap
            Map<String, SimpleVectorStore> stores = resolve(STORE_MAP, STORE_CROSS);
            if (stores == null) {
                stores = new LinkedHashMap<>();
                STORE_MAP.set(stores);
                String key = SESSION_KEY.get();
                if (key != null) STORE_CROSS.put(key, stores);
            }
            stores.put(fileName, store);

            Map<String, List<String>> chunkMaps = resolve(CHUNK_MAP, CHUNK_CROSS);
            if (chunkMaps == null) {
                chunkMaps = new LinkedHashMap<>();
                CHUNK_MAP.set(chunkMaps);
                String key = SESSION_KEY.get();
                if (key != null) CHUNK_CROSS.put(key, chunkMaps);
            }
            chunkMaps.put(fileName, chunks);

            log.info("readDocument: fileName={}, textLen={}, chunks={}", fileName, text.length(), chunks.size());
            return String.format("已读取「%s」（%d 字符，分 %d 块）。可以向我提问文件内容了。",
                    fileName, text.length(), chunks.size());

        } catch (Exception e) {
            log.error("readDocument 异常: fileName={}", fileName, e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // 向量化失败：区分 Embedding 端点/Auth 错误和普通错误，给出对症提示
            if (msg.contains("Workspace endpoint access denied")
                    || msg.contains("Workspace endpoint is invalid")
                    || msg.contains("401") || msg.contains("403")
                    || msg.contains("access denied")) {
                return String.format("读取「%s」时 Embedding（向量化）服务不可用：%s。"
                        + "请检查 DASHSCOPE_WORKSPACE_ID 环境变量是否正确设置（端点需 \\\"ws-\\\" 前缀），"
                        + "以及 DashScope API Key 是否具备该工作空间的访问权限。",
                        fileName, msg);
            }
            return "读取「" + fileName + "」时出错：" + msg;
        }
    }

    @Tool(description = "在已读取的文档中语义检索。需先调用 readDocument。返回与查询最匹配的文本片段。")
    public String searchDocument(
            @ToolParam(description = "检索关键词或自然语言查询") String query) {
        Map<String, SimpleVectorStore> stores = resolve(STORE_MAP, STORE_CROSS);
        if (stores == null || stores.isEmpty()) {
            return "尚未读取任何文件，请先调用 readDocument。";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SimpleVectorStore> entry : stores.entrySet()) {
            List<Document> results = entry.getValue().similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .topK(3)
                            .build());
            if (!results.isEmpty()) {
                sb.append("【").append(entry.getKey()).append("】\n");
                for (Document doc : results) {
                    sb.append(doc.getText()).append("\n---\n");
                }
            }
        }

        if (sb.isEmpty()) {
            return "未找到与「" + query + "」相关的内容。";
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "获取已读取文档的完整文本内容（上限 60000 字符），用于全文分析或总结。")
    public String summarizeDocument() {
        Map<String, List<String>> chunkMaps = resolve(CHUNK_MAP, CHUNK_CROSS);
        if (chunkMaps == null || chunkMaps.isEmpty()) {
            return "尚未读取任何文件，请先调用 readDocument。";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : chunkMaps.entrySet()) {
            sb.append("【").append(entry.getKey()).append("】\n");
            String full = String.join("\n", entry.getValue());
            if (full.length() > FULL_TEXT_LIMIT) {
                sb.append(full, 0, FULL_TEXT_LIMIT).append("\n…（后续内容已截断）");
            } else {
                sb.append(full);
            }
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    @Tool(description = "生成 Word 文档（.docx）。content 为文档正文，fileName 为输出文件名。")
    public String generateWord(
            @ToolParam(description = "文档正文内容") String content,
            @ToolParam(description = "输出文件名，如 report.docx") String fileName) {
        try (XWPFDocument doc = new XWPFDocument()) {
            // 按换行分段
            for (String line : content.split("\n")) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(line);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.write(baos);
            byte[] data = baos.toByteArray();

            String name = (fileName != null && !fileName.isBlank()) ? fileName : "document.docx";
            if (!name.endsWith(".docx")) name += ".docx";

            ReceivedContent rc = ReceivedContent.build(data,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", name);
            GENERATED_FILE_HOLDER.set(rc);
            LAST_GENERATED_FILE.set(rc);  // 跨线程回退
            String key = SESSION_KEY.get();
            if (key != null) GENERATED_CROSS.put(key, rc);

            log.info("generateWord: fileName={}, size={} bytes", name, data.length);
            return String.format("Word 文档「%s」已生成（%d 字节）。", name, data.length);

        } catch (Exception e) {
            log.error("generateWord 异常", e);
            return "生成 Word 文档失败：" + e.getMessage();
        }
    }

    /**
     * 安全渲染文本：先尝试整行渲染，若失败则逐字符回退，跳过字体不支持的字符。
     * <p>
     * CJK 字体（如 SimHei）可能缺少某些符号（如 bullet • U+2022）的 glyph，
     * 直接 showText 会导致 IllegalStateException。
     */
    private static void showTextSafe(PDPageContentStream cs, String text, PDFont font) throws IOException {
        try {
            cs.showText(text);
        } catch (Exception e) {
            // 逐字符回退，用空格替换不支持的字符
            for (int i = 0; i < text.length(); i++) {
                String ch = text.substring(i, i + 1);
                try {
                    cs.showText(ch);
                } catch (Exception ex) {
                    try {
                        cs.showText(" ");
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Tool(description = "生成 PDF 文档。content 为文档正文，fileName 为输出文件名。")
    public String generatePdf(
            @ToolParam(description = "文档正文内容") String content,
            @ToolParam(description = "输出文件名，如 report.pdf") String fileName) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            // 加载中文字体
            PDFont font = loadCjkFont();
            if (font == null) {
                return "生成 PDF 失败：未找到可用的中文字体，请联系管理员。";
            }

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.setLeading(14.5f);
                cs.newLineAtOffset(50, 750);

                for (String line : content.split("\n")) {
                    // 按宽度截断，中文字符每行约 42 个
                    String remaining = line;
                    while (remaining.length() > 42) {
                        showTextSafe(cs, remaining.substring(0, 42), font);
                        cs.newLine();
                        remaining = remaining.substring(42);
                    }
                    if (!remaining.isEmpty()) {
                        showTextSafe(cs, remaining, font);
                    }
                    cs.newLine();
                }
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            byte[] data = baos.toByteArray();

            String name = (fileName != null && !fileName.isBlank()) ? fileName : "document.pdf";
            if (!name.endsWith(".pdf")) name += ".pdf";

            ReceivedContent rc = ReceivedContent.build(data, "application/pdf", name);
            GENERATED_FILE_HOLDER.set(rc);
            LAST_GENERATED_FILE.set(rc);  // 跨线程回退
            String key = SESSION_KEY.get();
            if (key != null) GENERATED_CROSS.put(key, rc);

            log.info("generatePdf: fileName={}, size={} bytes", name, data.length);
            return String.format("PDF 文档「%s」已生成（%d 字节）。", name, data.length);

        } catch (Exception e) {
            log.error("generatePdf 异常", e);
            return "生成 PDF 文档失败：" + e.getMessage();
        }
    }
}
