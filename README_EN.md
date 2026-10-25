# ykd-summer — Multi-Agent WeChat AI Personal Assistant

A **Multi-Agent WeChat personal assistant** built on **Spring AI + Qwen (Tongyi Qianwen)**. It orchestrates multiple LLM agents into a collaborative "digital twin" that, once connected to a personal WeChat account, can autonomously handle chatting, images, documents, browser automation, Bilibili operations, tech-news monitoring, and more — all through plain conversational commands.

> ⚠️ **For personal learning and research only.** This project involves connecting a personal WeChat account and automating third-party platforms. Please comply with the relevant platform Terms of Service and use with caution. You are solely responsible for any consequences arising from its use.

---

## ✨ What is it?

Imagine an all-in-one assistant living inside your WeChat: it understands text, images, voice, files and videos you send; it can search, write documents, publish videos to Bilibili, and keep an eye on Spring releases — breaking complex requests into steps and executing them for you.

Under the hood, it's a **"routing + orchestration + multi-agent collaboration"** architecture:

- **7 specialized agents**, each with a clear responsibility (Text / Image / File / Browser / Bilibili / Spring / Router)
- **107 callable tools** (`@Tool`) covering weather, maps, web search, email, image generation, document I/O, web automation, and more
- **Automatic multi-step orchestration**: complex requests are decomposed into a dependency-aware DAG and executed in topological order
- **A complete loop**: beyond text replies, it can produce images, Word/PDF documents, voice, and even run end-to-end Bilibili video publishing

---

## 🌟 Key Features

| Module | Details |
|--------|---------|
| **Rich-media messaging** | Text / image / voice / video / file; voice auto-ASR transcription (Paraformer); optional TTS replies (CosyVoice) |
| **Multi-agent orchestration** | Router agent (qwen-vl-plus multimodal) parses intent → decomposes into a multi-step plan → Kahn topological sort → dispatches to sub-agents |
| **TEXT Agent** | Weather, web search (with citations), email, personal-info management, Amap (location/POI/routing), chitchat |
| **IMAGE Agent** | Text-to-image, image recognition, image-to-image |
| **FILE Agent** | Document parsing (PDF/Word/TXT) + semantic search (vector RAG), auto-generation of Word/PDF |
| **BROWSER Agent** | Playwright automation: open pages, read snapshots, click, type, multi-tab management |
| **BILIBILI Agent** | Post dynamics, end-to-end video publishing (upload → draft → publish → transcode monitoring) → auto-archive to creation profile |
| **SPRING Agent** | Version monitoring of the Spring ecosystem (GitHub Releases + official blog + doc changes), runtime-adjustable scope |
| **Session management** | `#new` memory reset, standalone image/video intent prompts, media timing buffer, cover continuation |
| **Task lifecycle control** | Runtime **pause / cancel / resume**: save snapshot, resume from breakpoint, amend instructions while paused |
| **Token usage & billing** | Per-agent real-time token consumption & cost tracking, streamed to the dashboard via SSE |
| **Alibaba Cloud balance** | BSS API (HMAC-SHA1 signed) real-time balance + estimated remaining quota |
| **Scheduled jobs** | Bilibili daily report, comment auto-moderation, Spring monitoring report |
| **Web dashboard** | SSE event stream + task tracking + token tracking + tool visualization + runtime feature toggles (dark terminal style) |

---

## 🏗 Architecture

### Overall Flow

```
User message
    ↓
IlinkBotRunner (per-user Actor, serial processing)
    ├── media timing buffer / standalone image & video intent prompts
    ├── voice → ASR → routed as text
    ├── attachment dedup / #new command / QR-code sending / cover continuation
    ↓
Router Agent (qwen-vl-plus)
    ├── parse intent → generate orchestration plan (JSON)
    ├── deterministic shortcuts (video resume, image waiting) bypass LLM
    └── JSON fault-tolerance repair
    ↓
OrchestrationService (Kahn topological sort)
    ↓
Dispatch to sub-agents (pause/cancel/resume support) → aggregate results → reply
```

### Seven Agents

| Agent | Model | Role | Tools |
|-------|-------|------|-------|
| **ROUTER** | qwen-vl-plus | Intent parsing & orchestration | none (scheduler) |
| **TEXT** | qwen-plus | Conversation, search, map, email, personal info | 6 tool categories |
| **IMAGE** | default | Text-to-image / recognition / img2img | ImageTools |
| **FILE** | default | Document parsing / retrieval / generation | FileTools |
| **BROWSER** | default | Web automation | BrowserTools |
| **BILIBILI** | qwen-turbo | Dynamics / video publishing | BilibiliAgentTools |
| **SPRING** | qwen-turbo | Version monitoring | SpringMonitorTools & more |

Each agent has its own `ChatClient`, System Prompt and toolset, isolated from each other and coordinated via the orchestration plan.

### Design Highlights

- **Per-user Actor model** — one virtual thread + queue per user; strict serial per user, full parallel across users
- **Dual RAG advisors** — two independent vector stores (personal info + creation profile), context injected on demand
- **On-demand tool injection** — 3-level filtering of TEXT tools by intent to shrink LLM choice space and cut latency
- **Dedicated web-search channel** — direct call to DashScope native endpoint with `forced_search` + citations for freshness
- **Java 21 virtual threads** — high concurrency, low overhead
- **Deterministic shortcuts + JSON repair** — bypass LLM for clear scenarios, auto-fix malformed LLM output
- **Cross-thread data safety** — ThreadLocal + ConcurrentHashMap dual path, compatible with Spring AI tool threading

---

## 🛠 Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Framework | Spring Boot | 4.1.0 |
| Language | Java (JDK 21, virtual threads) | 21 |
| AI framework | Spring AI | 2.0.0 |
| LLM | Alibaba Cloud DashScope | qwen-plus / qwen-vl-plus / qwen-turbo |
| Image / voice / embedding | DashScope z-image-turbo / qwen3-asr / cosyvoice / text-embedding-v3 | - |
| Database | H2 (embedded file DB) | runtime |
| WeChat | wechat-ilink-sdk | 2.3.3 |
| Browser | Playwright | 1.49.0 |
| HTTP client | OkHttp | 4.12.0 |
| Document parse / generate | Apache Tika / POI / PDFBox | - / 5.4.0 / 3.0.4 |
| Mail / AOP / Map / Billing | Spring Mail / AOP+AspectJ / Amap / Alibaba Cloud BSS | - |

---

## 🚀 Quick Start

### Requirements

- JDK 21, Maven 3.6+
- Playwright browser (for BROWSER Agent)

### Install Playwright

```bash
npx playwright install chromium
```

### Configure Environment Variables

**All sensitive values are injected via environment variables — never hardcode secrets.** Copy `.env.example` to `.env` and fill in your values:

| Variable | Purpose | Required |
|----------|---------|----------|
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` | Qwen API key | ✅ |
| `DASHSCOPE_WORKSPACE_ID` | DashScope workspace | optional |
| `ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET` | Alibaba Cloud balance query | optional |
| `QQ_MAIL_USERNAME` / `QQ_MAIL_AUTH_CODE` | Email delivery | optional |
| `AMAP_API_KEY` | Amap | optional |
| `BILIBILI_COOKIE` / `BILIBILI_UID` / `BILIBILI_REPORT_QQ` | Bilibili operations | optional |
| `BILIBILI_DEFAULT_COVER` | Default video cover | optional |

### Build & Run

```bash
mvn clean package -DskipTests
java -jar target/ykd_summer-0.0.1-SNAPSHOT.jar
```

After startup: a **WeChat QR code** appears in the console → scan to log in → the bot starts listening → open `http://localhost:8080` for the dashboard.

CLI console: `help` / `status` / `version`.

---

## 📖 Usage Examples

### Conversation

```
You: What's the weather in Shenzhen today?
Bot: [answer + real weather data]

You: Search for recent exhibitions in Shenzhen
Bot: [web search with citations]

You: Generate a cyberpunk-style cat image
Bot: [generates and sends image]

You: Remember that I like my coffee without sugar
Bot: [stores into personal memory, auto-retrieved later]

You: #new
Bot: [resets conversation history]
```

### Task Control

```
You: pause    →  [saves execution snapshot]
You: resume   →  [continues from breakpoint]
You: cancel   →  [terminates and cleans up]
```

### Documents / Browser

```
You: [send PDF] summarize this document
Bot: [parse → chunk → vectorize → retrieve → summary]

You: open Baidu homepage and check hot searches
Bot: [open page → read snapshot → summarize]
```

### Bilibili Operations

```
You: post a Bilibili dynamic "happy weekend" [with image]
Bot: [posts dynamic, returns link]

You: [send video] upload this to Bilibili
Bot: [ask title/zone/tags → upload → draft → publish → monitor transcoding]

You: what's trending on Bilibili this week? give me content ideas
Bot: [collect trends → search creation profile → AI analysis → suggestions]
```

---

## ⏰ Scheduled Jobs

| Job | Frequency | Description |
|-----|-----------|-------------|
| Bilibili daily report | daily 09:30 | followers, video stats, dynamics; Monday adds trend analysis + content suggestions |
| Comment auto-moderation | every 30 min | two-stage review (keywords + AI semantics) → auto-delete violations → alert |
| Spring monitoring report | configurable (default 09:55) | monitor 7 core repos + official blog + doc changes, AI-summarized push |

---

## 📁 Project Structure

```
ykd-summer/
├── src/main/java/org/example/ykd_summer/
│   ├── YkdSummerApplication.java     # entry point
│   ├── runner/                       # startup (IlinkBotRunner / CommandRunner)
│   ├── web/                          # dashboard (Controller / SSE / trackers)
│   ├── config/                       # agent configs + dual RAG advisors + properties
│   ├── service/                      # business services (orchestration / Bilibili / browser / image / voice)
│   ├── billing/                      # billing & token tracking
│   ├── tools/                        # 107 @Tool methods
│   ├── scheduler/                    # scheduled jobs
│   ├── dto/ session/ store/ utils/ aspect/ exception/
│   └── ...
├── src/main/resources/
│   ├── application.yml               # main config (secrets via env vars)
│   ├── schema.sql                    # H2 schema
│   ├── prompts/                      # per-agent System Prompts
│   └── static/index.html             # dashboard frontend
├── .env.example                      # environment variable template
├── pom.xml
└── README.md
```

---

## ⚠️ Privacy & Security

1. All secrets are injected via environment variables — never hardcode real keys in code or config.
2. The `data/` directory (cookies, browser state, personal vector stores, chat memory) is gitignored — **do not commit it manually**.
3. If sensitive data was ever leaked in Git history, rotate the affected credentials immediately (Bilibili cookie, Alibaba Cloud AccessKey, iLink token, etc.).
4. This project is for personal learning and research only; please comply with the ToS of WeChat, Bilibili and other platforms.

---

## 📄 License

This project is for personal learning and research use only; no open-source license has been specified yet. Please assess and comply with relevant platform policies before any secondary development or other use.
