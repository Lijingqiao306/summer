# ykd-summer — 多智能体微信 AI 个人助手

基于 **Spring AI + 通义千问** 打造的一套 **Multi-Agent 微信个人助手**。它将多个大模型 Agent 编排成一个可协作的「数字分身」，接入微信个人账号后，能自动处理聊天、图片、文档、浏览器操作、B站运营、技术资讯监控等日常任务——全程只需像聊天一样下达指令。

> ⚠️ **仅供个人学习研究使用**。项目涉及微信个人账号接入与第三方平台自动化操作，请遵守相关平台服务条款，谨慎使用。使用本项目的任何行为及其后果由使用者自行承担。

---

## ✨ 它是什么？

想象一个嵌在你微信里的全能助理：你发消息、发图片、发文件、发视频，它都能理解并回应；你让它「帮我搜一下」「帮我写个文档」「帮我把视频发到B站」「每天盯着 Spring 的更新」，它会自己拆解任务、调用合适的工具、分步执行，然后把结果返回给你。

这一切背后，是一个 **「路由 + 编排 + 多 Agent 协作」** 的架构：

- **7 个专职 Agent** 各司其职（文本 / 图像 / 文档 / 浏览器 / B站 / Spring / 路由调度）
- **107 个可调用工具**（`@Tool`），覆盖天气、地图、搜索、邮件、生图、文档读写、网页自动化等
- **多步骤任务自动编排**：复杂请求被拆成有依赖关系的有向无环图（DAG），按拓扑序执行
- **完整体验闭环**：除了回复文字，还能直接产出图片、Word/PDF 文档、语音，甚至完成 B站视频全链路发布

---

## 🌟 核心特性

| 能力模块 | 具体说明 |
|---------|---------|
| **富媒体消息收发** | 文本 / 图片 / 语音 / 视频 / 文件；语音自动 ASR 转写（Paraformer）后进入对话，回复可选 TTS 合成语音（CosyVoice） |
| **多 Agent 任务编排** | 路由 Agent（qwen-vl-plus 多模态）识别意图 → 拆解多步计划 → Kahn 拓扑排序 → 分发到子 Agent 并行/串行执行 |
| **TEXT Agent** | 天气、联网搜索（含引用来源）、邮件发送、个人信息管理、高德地图（位置/POI/路径规划）、日常闲聊 |
| **IMAGE Agent** | 文生图、图片内容识别、以图生图 |
| **FILE Agent** | 文档解析（PDF/Word/TXT）+ 语义检索（向量 RAG）、自动生成 Word/PDF 并发送 |
| **BROWSER Agent** | Playwright 浏览器自动化：开网页、读快照、点击、输入、多标签页管理 |
| **BILIBILI Agent** | 动态发布、视频全链路发布（上传→草稿→发布→监控转码）→ 自动归档创作档案 |
| **SPRING Agent** | Spring 生态版本监测（GitHub Releases + 官方博客 + 文档变更），运行时动态调整监测范围 |
| **会话管理** | `#new` 重置记忆、纯图片/视频意图单独询问、图文时序竞态缓冲、封面续传 |
| **任务生命周期控制** | 运行时**暂停 / 取消 / 继续**：暂停保存快照、断点续执行、暂停期间可补充/修改指令 |
| **Token 用量 & 计费追踪** | 按 Agent 维度实时追踪 Token 消耗与费用，SSE 推送至监控面板 |
| **阿里云余额监控** | BSS API（HMAC-SHA1 签名）查询实时余额，结合累计消费估算剩余额度 |
| **定时任务** | B站数据日报、评论自动审核、Spring 监测日报，全自动运行 |
| **Web 监控面板** | SSE 实时事件流 + 任务追踪 + Token 追踪 + 工具可视化 + 功能运行时开关（深色终端风格） |

---

## 🏗 架构详解

### 整体流程

```
用户消息
    ↓
IlinkBotRunner（Per-user Actor 串行处理）
    ├── 图文时序缓冲：文字含图片意图 → 等待图片合并处理
    ├── 纯图片/视频意图询问：询问识别 / 发动态 / 生图 / 上传
    ├── 语音消息 → ASR 转写 → 作为文本路由
    ├── 附件暂存去重 / #new 指令 / 二维码发送 / 封面续传
    ↓
Router Agent（qwen-vl-plus 多模态）
    ├── 分析意图 → 生成编排计划 JSON
    ├── 确定性短路：视频续传 / 图片等待等场景绕过 LLM
    └── JSON 容错修复：自动修复常见格式错误
    ↓
OrchestrationService（Kahn 拓扑排序，解析步骤依赖）
    ↓
分发到子 Agent 执行（支持暂停/取消/继续）→ 聚合结果（文本+图片+文档+语音）→ 回复用户
```

### 七大 Agent

| Agent | 模型 | 职责 | 工具集 |
|-------|------|------|--------|
| **ROUTER** | qwen-vl-plus | 意图识别与任务编排 | 无（纯调度） |
| **TEXT** | qwen-plus | 文本对话、搜索、地图、邮件、个人信息 | 6 类工具（见下） |
| **IMAGE** | 默认 | 文生图 / 识图 / 以图生图 | ImageTools |
| **FILE** | 默认 | 文档解析 / 检索 / 生成 | FileTools |
| **BROWSER** | 默认 | 网页自动化操作 | BrowserTools |
| **BILIBILI** | qwen-turbo | 动态 / 视频发布 | BilibiliAgentTools |
| **SPRING** | qwen-turbo | 版本监测 | SpringMonitorTools 等 |

每个 Agent 拥有独立的 `ChatClient`、System Prompt 与工具集，互不干扰，通过编排计划声明依赖实现协作。

### 设计亮点

- **Per-user Actor 模式**：每用户独立虚拟线程 + 队列，同用户消息严格串行、不同用户完全并行
- **双 RAG Advisor**：个人信息库 + 创作档案库两个独立向量库，按需检索注入上下文
- **工具按需注入**：TEXT Agent 的 6 类工具按意图做「三级过滤」，减少 LLM 选择空间、降低延迟
- **联网搜索独立通道**：直连 DashScope 原生端点，`forced_search` + 引用来源，保证时效性
- **Java 21 虚拟线程**：高并发低开销的消息处理
- **确定性短路 + JSON 容错**：明确场景绕过 LLM，LLM 输出自动修复，兼顾速度与稳定
- **跨线程数据安全**：ThreadLocal + ConcurrentHashMap 双路径，兼容 Spring AI 工具独立线程执行

---

## 🛠 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 基础框架 | Spring Boot | 4.1.0 |
| 语言 | Java（JDK 21，虚拟线程） | 21 |
| AI 框架 | Spring AI | 2.0.0 |
| 大模型 | 阿里云 DashScope | qwen-plus / qwen-vl-plus / qwen-turbo |
| 文生图 / 语音 / 嵌入 | DashScope z-image-turbo / qwen3-asr / cosyvoice / text-embedding-v3 | - |
| 数据库 | H2（嵌入式文件数据库） | runtime |
| 微信接入 | wechat-ilink-sdk | 2.3.3 |
| 浏览器自动化 | Playwright | 1.49.0 |
| HTTP 客户端 | OkHttp | 4.12.0 |
| 文档解析 / 生成 | Apache Tika / POI / PDFBox | - / 5.4.0 / 3.0.4 |
| 邮件 / AOP / 地图 / 计费 | Spring Mail / AOP+AspectJ / 高德 / 阿里云 BSS | - |

---

## 🚀 快速开始

### 环境要求

- JDK 21、Maven 3.6+
- Playwright 浏览器（BROWSER Agent 需要）

### 安装 Playwright

```bash
npx playwright install chromium
```

### 配置环境变量

**所有敏感信息均通过环境变量注入，请勿硬编码密钥。** 复制 `.env.example` 为 `.env` 并填入真实值：

| 环境变量 | 用途 | 必填 |
|---------|------|------|
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` | 通义千问 API Key | ✅ |
| `DASHSCOPE_WORKSPACE_ID` | DashScope 工作空间 | 可选 |
| `ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET` | 阿里云余额查询 | 可选 |
| `QQ_MAIL_USERNAME` / `QQ_MAIL_AUTH_CODE` | 邮件投递 | 可选 |
| `AMAP_API_KEY` | 高德地图 | 可选 |
| `BILIBILI_COOKIE` / `BILIBILI_UID` / `BILIBILI_REPORT_QQ` | B站运营 | 可选 |
| `BILIBILI_DEFAULT_COVER` | 视频默认封面 | 可选 |

### 编译运行

```bash
mvn clean package -DskipTests
java -jar target/ykd_summer-0.0.1-SNAPSHOT.jar
```

启动后：控制台显示**微信扫码登录二维码** → 扫码登录 → Bot 开始监听 → 浏览器访问 `http://localhost:8080` 查看监控面板。

命令行控制台：`help` / `status` / `version`。

---

## 📖 使用示例

### 日常对话

```
你：今天深圳天气怎么样？
它：[回答 + 真实天气数据]

你：帮我搜索一下深圳最近有什么展览
它：[联网搜索返回时效结果 + 引用来源]

你：帮我生成一张赛博朋克风格的猫咪图片
它：[生成图片并发送]

你：帮我记住我喜欢喝咖啡不加糖
它：[向量化存入个人记忆库，后续自动检索]

你：#new
它：[重置会话历史]
```

### 任务控制

```
你：暂停  →  [保存执行快照]
你：继续  →  [从断点继续]
你：取消  →  [终止并清理]
```

### 文档处理 / 浏览器

```
你：[发 PDF] 帮我总结这份文档
它：[解析 → 分块 → 向量化 → 检索 → 摘要]

你：帮我打开百度首页看看热搜
它：[开网页 → 读快照 → 整理摘要]
```

### B站运营

```
你：帮我在B站发条动态"周末愉快" [附图片]
它：[发布动态，返回链接]

你：[发视频] 帮我把这个视频传到B站
它：[询问标题/分区/标签 → 上传 → 草稿 → 发布 → 监控转码]

你：看看B站这周有什么热门话题，给我点创作建议
它：[采集热门 → 检索创作档案 → AI 分析 → 个性化建议]
```

---

## ⏰ 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| B站数据日报 | 每日 9:30 | 粉丝数、播放/点赞/评论、最新动态；周一附热门分析 + 创作建议 |
| 评论自动审核 | 每 30 分钟 | 两阶段审核（关键词 + AI 语义）→ 自动删除违规 → 告警 |
| Spring 监测日报 | 可配置（默认 9:55） | 监测 7 个核心仓库 Release + 官方博客 + 文档变更，AI 摘要推送 |

---

## 📁 项目结构

```
ykd-summer/
├── src/main/java/org/example/ykd_summer/
│   ├── YkdSummerApplication.java     # 主入口
│   ├── runner/                       # 启动执行（IlinkBotRunner / CommandRunner）
│   ├── web/                          # 监控面板（Controller / SSE / 追踪器）
│   ├── config/                       # 各 Agent 配置 + 双 RAG Advisor + properties
│   ├── service/                      # 业务服务（编排 / B站 / 浏览器 / 图像 / 语音等）
│   ├── billing/                      # 计费与 Token 追踪
│   ├── tools/                        # 107 个 @Tool 工具
│   ├── scheduler/                    # 定时任务
│   ├── dto/ session/ store/ utils/ aspect/ exception/
│   └── ...
├── src/main/resources/
│   ├── application.yml               # 主配置（密钥走环境变量）
│   ├── schema.sql                    # H2 建表
│   ├── prompts/                      # 各 Agent System Prompt
│   └── static/index.html             # 监控面板前端
├── .env.example                      # 环境变量模板
├── pom.xml
└── README.md
```

---

## ⚠️ 隐私与安全

1. 所有密钥均通过环境变量注入，请勿在代码或配置中硬编码真实密钥。
2. `data/` 目录（Cookie、浏览器状态、个人信息向量库、ChatMemory）已被 `.gitignore` 忽略，**切勿手动提交**。
3. 若曾在 Git 历史泄露过敏感信息，请立即轮换对应密钥（B站 Cookie、阿里云 AccessKey、iLink token 等）。
4. 本项目仅供个人学习研究，请遵守微信、B站等平台的用户协议与服务条款。

---

## 📄 许可证

本项目仅供个人学习研究使用，暂未指定开源许可证。如需二次开发或其他用途，请自行评估并遵守相关平台规范。
