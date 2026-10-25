# ykd-summer — 多智能体微信 AI 个人助手

基于 **Spring AI + 通义千问** 大语言模型打造的 **Multi-Agent 微信个人助手 Bot**。通过 iLink SDK 接入微信个人账号，实现智能消息回复、多 Agent 任务编排、文档处理、浏览器自动化、B站运营、Spring 官网监听等多种 AI 能力。

> ⚠️ **仅供个人学习研究使用**。本项目涉及微信个人账号接入与第三方平台自动化操作，请遵守相关平台服务条款，谨慎使用。使用本项目的任何行为及其后果由使用者自行承担。

---

## ✨ 功能概览

| 能力模块 | 说明 |
|---------|------|
| **微信消息智能回复** | 支持文本、图片、语音、视频、文件等富媒体消息收发；语音自动 ASR 转写（Paraformer）进入对话，回复可选 TTS 合成语音（CosyVoice） |
| **多 Agent 任务编排** | 路由 Agent（qwen-vl-plus 多模态）自动分析用户意图，拆解多步骤计划，Kahn 拓扑排序后分发到子 Agent 执行 |
| **TEXT Agent** | 天气查询、联网搜索（含引用来源）、邮件发送、个人信息管理、高德地图（位置/POI/路径规划）、闲聊，挂载双 RAG Advisor（个人信息 + 创作档案） |
| **IMAGE Agent** | 文生图（DashScope）、图片内容识别、以图生图 |
| **FILE Agent** | 文档解析（PDF/Word/TXT）+ 语义检索（向量化 RAG）、Word/PDF 文档自动生成与发送 |
| **BROWSER Agent** | Playwright 浏览器自动化：打开网页、读取快照、点击、输入、多标签页管理 |
| **BILIBILI Agent** | 动态发布、视频全链路发布（API 上传 → 草稿 → 发布 → 监控转码）→ 自动归档至创作档案向量库 |
| **SPRING Agent** | Spring 项目版本监测（GitHub Releases + 官方博客 + 文档变更）+ 运行时动态调整监测范围 |
| **会话管理** | `#new` 指令重置 ChatMemory、纯图片/视频意图单独询问、图文时序竞态缓冲、封面续传 |
| **任务生命周期控制** | 运行时暂停/取消/继续多步骤任务：暂停保存快照、恢复断点续执行、暂停期间可补充/修改指令 |
| **Token 用量追踪** | 按 Agent 维度实时追踪 Token 消耗与费用，SSE 实时推送 |
| **阿里云余额监控** | BSS API（HMAC-SHA1 签名）查询实时余额，SSE 实时推送 |
| **定时任务** | B站数据日报、评论自动审核、Spring 监测日报 |
| **Web 监控面板** | SSE 实时事件流 + 任务追踪 + Token 追踪 + 工具调用可视化 + 工具/功能运行时开关（深色终端风格） |

---

## 🏗 核心架构

采用 **「路由编排 + 分发执行」** 两级架构：

```
用户消息
    ↓
IlinkBotRunner（Per-user Actor 串行处理）
    ├── 图文时序缓冲 / 纯图片视频意图询问
    ├── 语音 ASR 转写 → 文本路由
    ├── 附件暂存去重 / #new 指令 / 二维码发送等
    ↓
Router Agent（qwen-vl-plus，分析意图 → 生成编排计划 JSON）
    ├── 确定性短路：视频续传、图片等待等场景绕过 LLM
    └── JSON 容错修复
    ↓
OrchestrationService（Kahn 拓扑排序，解析步骤依赖）
    ↓
分发到子 Agent 执行（支持暂停/取消/继续）→ 聚合结果 → 回复用户
```

### Agent 一览

| Agent | 模型 | 工具集 |
|-------|------|--------|
| **ROUTER** | qwen-vl-plus | 无（纯编排） |
| **TEXT** | qwen-plus | BotTools、MailSendTools、PersonalTools、LocationTools、AmapTools、WebSearchTools |
| **IMAGE** | 默认 | ImageTools（文生图/识图/以图生图） |
| **FILE** | 默认 | FileTools（文档解析/检索/生成） |
| **BROWSER** | 默认 | BrowserTools（网页操作/快照/交互） |
| **BILIBILI** | qwen-turbo | BilibiliAgentTools（发动态/上传视频/发布） |
| **SPRING** | qwen-turbo | SpringMonitorTools、SpringMonitorConfigTools、MailSendTools |

每个 Agent 拥有独立的 `ChatClient`、System Prompt 和工具集，通过编排计划声明依赖实现协作。

---

## 🛠 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **基础框架** | Spring Boot | 4.1.0 |
| **Java** | JDK 21（虚拟线程） | 21 |
| **AI 框架** | Spring AI | 2.0.0 |
| **大模型** | 阿里云 DashScope（通义千问） | qwen-plus / qwen-vl-plus / qwen-turbo |
| **文生图** | DashScope z-image-turbo | - |
| **语音识别** | DashScope qwen3-asr-flash | - |
| **语音合成** | DashScope cosyvoice-v3-plus | - |
| **向量嵌入** | DashScope text-embedding-v3 | - |
| **数据库** | H2（嵌入式文件数据库） | runtime |
| **微信接入** | wechat-ilink-sdk | 2.3.3 |
| **浏览器自动化** | Playwright | 1.49.0 |
| **HTTP 客户端** | OkHttp | 4.12.0 |
| **文档解析** | Apache Tika（Spring AI Reader） | - |
| **文档生成** | Apache POI / PDFBox | 5.4.0 / 3.0.4 |
| **邮件** | Spring Boot Mail | - |
| **AOP** | Spring AOP + AspectJ | - |
| **地图服务** | 高德地图 Web API | - |
| **计费监控** | 阿里云 BSS API（HMAC-SHA1 签名） | - |

---

## 🚀 快速开始

### 环境要求

- JDK 21
- Maven 3.6+
- Playwright 浏览器（用于 BROWSER Agent）

### 安装 Playwright 浏览器

```bash
npx playwright install chromium
```

### 配置环境变量

**重要：本项目所有敏感信息均通过环境变量注入，请勿硬编码密钥。**

复制 `.env.example` 为 `.env`（或直接在系统中设置环境变量），填入你的真实值：

| 环境变量 | 用途 | 是否必填 |
|---------|------|---------|
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` | 通义千问 API Key | ✅ 必填 |
| `DASHSCOPE_WORKSPACE_ID` | DashScope 工作空间 ID | 可选 |
| `ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET` | 阿里云余额查询 | 可选 |
| `QQ_MAIL_USERNAME` / `QQ_MAIL_AUTH_CODE` | 邮件投递 | 可选 |
| `AMAP_API_KEY` | 高德地图 | 可选 |
| `BILIBILI_COOKIE` / `BILIBILI_UID` / `BILIBILI_REPORT_QQ` | B站运营 | 可选 |
| `BILIBILI_DEFAULT_COVER` | 视频默认封面 | 可选 |

环境变量与 `application.yml` 的完整对应关系见 `.env.example`。

### 编译运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/ykd_summer-0.0.1-SNAPSHOT.jar
```

启动后：
- 控制台显示微信扫码登录二维码，使用微信扫描登录
- 登录成功后 Bot 开始监听消息
- 访问 `http://localhost:8080` 查看 Web 监控面板

### 命令行控制台

```text
help    - 显示帮助
status  - 查看 Bot 状态
version - 显示版本号
```

---

## 📁 项目结构

```
ykd-summer/
├── src/main/java/org/example/ykd_summer/
│   ├── YkdSummerApplication.java   # 主入口
│   ├── runner/                     # 启动后执行（IlinkBotRunner / CommandRunner）
│   ├── web/                        # Web 监控面板（Controller / SSE 事件总线 / 任务追踪等）
│   ├── config/                     # Spring 配置（各 Agent + 双 RAG Advisor + properties）
│   ├── service/                    # 业务服务（AI 聊天 / 编排 / B站 / 浏览器 / 图像 / 语音等）
│   ├── billing/                    # 计费与 Token 用量追踪
│   ├── tools/                      # AI Agent 工具集（@Tool 注解暴露给 LLM）
│   ├── scheduler/                  # 定时任务（日报 / 评论审核 / Spring 监测）
│   ├── dto/ / session/ / store/ / utils/ / aspect/ / exception/
│   └── ...
├── src/main/resources/
│   ├── application.yml             # 主配置（密钥走环境变量）
│   ├── schema.sql                  # H2 建表脚本
│   ├── prompts/                    # 各 Agent 的 System Prompt
│   └── static/index.html           # Web 监控面板前端
├── .env.example                    # 环境变量模板
├── pom.xml
└── README.md
```

---

## 📖 使用示例

### 日常对话

```
用户: 今天深圳天气怎么样？
Bot:  [通义千问回答 + 真实天气数据]

用户: 帮我搜索一下深圳最近有什么展览
Bot:  [联网搜索返回时效性结果 + 引用来源]

用户: 帮我生成一张赛博朋克风格的猫咪图片
Bot:  [生成图片并发送]

用户: 帮我记住：我喜欢喝咖啡不加糖
Bot:  [向量化存储到个人记忆库，后续自动检索]

用户: #new
Bot:  [重置当前会话历史]
```

### 任务控制

```
用户: 暂停   →  [保存任务执行快照]
用户: 继续   →  [从断点继续执行]
用户: 取消   →  [终止任务并清理资源]
```

### 文档处理

```
用户: [发送 PDF] 帮我总结这份文档的内容
Bot:  [解析 → 分块 → 向量化 → 语义检索 → 摘要]

用户: 根据上面的内容生成一份 Word 文档
Bot:  [生成 .docx 并发送]
```

### 浏览器操作

```
用户: 帮我打开百度首页，看看热搜榜
Bot:  [打开网页 → 获取快照 → 整理摘要]
```

### B站运营

```
用户: 帮我在B站发一条动态，内容是"周末愉快" [附图片]
Bot:  [发布动态，返回链接]

用户: [发送视频] 帮我把这个视频上传到B站
Bot:  [询问标题/分区/标签 → 上传 → 草稿 → 发布 → 监控转码]

用户: 看看B站这周有哪些热门话题，给我一些创作建议
Bot:  [采集热门视频 → 检索创作档案 → AI 分析 → 个性化建议]
```

### Spring 监测

```
用户: 帮我开启 Spring Boot 和 Spring AI 的 Release 监测
Bot:  [添加监测仓库 → 定时轮询 → 更新时推送]
```

---

## ⏰ 定时任务

| 任务 | 频率 | 说明 |
|------|------|------|
| **B站数据日报** | 每日 9:30 | 粉丝数、视频播放/点赞/评论、最新动态；周一附热门趋势分析 + AI 创作建议 |
| **评论自动审核** | 每 30 分钟 | 两阶段审核（关键词 + AI 语义）→ 自动删除违规评论 → 告警 |
| **Spring 监测日报** | 可配置（默认 9:55） | 监测 7 个核心仓库 Release + 官方博客 + 文档变更，AI 摘要推送 |

---

## ⚠️ 隐私与安全提示

1. **所有密钥均通过环境变量注入**，请勿在代码或配置文件中硬编码真实密钥。
2. `data/` 目录（Cookie、浏览器状态、个人信息向量库、ChatMemory 等）已被 `.gitignore` 忽略，**切勿手动提交**。
3. 若已在 Git 历史中提交过敏感信息，请立即轮换对应密钥（B站 Cookie、阿里云 AccessKey、iLink token 等）。
4. 本项目仅供个人学习研究，请遵守微信、B站等平台的用户协议与服务条款。

---

## 📄 许可证

本项目仅供个人学习研究使用，暂未指定开源许可证。如你需要将其用于二次开发或其他用途，请自行评估并遵守相关平台规范。
