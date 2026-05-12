# WiseLink Manus（精简版）— 分阶段设计与实现约定

> **当前范围**：实现「多步可观测」的 Manus；**暂不**做动态大模型路由，但设计与包结构须**预留**路由接入点。  
> **实现节奏**：按阶段提交/评审，每阶段可独立编译与验收，避免一次性大 diff。  
> **文档位置**：本文件与 [`MANUS-ARCHITECTURE.md`](./MANUS-ARCHITECTURE.md) 位于仓库 **`docs/`** 目录。

---

## 0. 设计冻结（共识，不再在实现中反复改口径）

| 项 | 约定 |
|----|------|
| **RAG** | **仅第 1 步**参与；第 2 步起不再走 `RetrievalAugmentationAdvisor`（避免重复检索与 token）。 |
| **Step 事件** | **不写入** `ChatMemory`；仅通过 **SSE / 日志 / 可插拔 Sink** 对外投递，供 UI 展示「第几步」。 |
| **模型（防历史 bug）** | **一次 Manus 任务在循环外解析一次**「对话引擎」，循环内**只消费同一引用**；禁止在 `for` 内无参重建默认 `ChatClient` 或隐式取 `@Primary` `ChatModel`。日后路由 = 替换「解析」实现，不改循环骨架。 |
| **与导购关系** | Manus 与 `AiShoppingGuideApp` **并存**：普通模式不改语义；Manus 走独立编排入口（可新 API 或 query 开关）。 |
| **代码质量** | **禁止**单类多职责巨型类（如数百行混杂编排+HTTP+工具）；**允许**类数量略多，以**单一职责 + 接口隔离**换可读性。 |

---

## 1. 建议包结构（高内聚、低耦合）

```
com.gen.ai.application.manus
├── api                          # 对外契约（接口 + 纯模型/DTO，无 Spring 亦可单测）
│   ├── ManusStepEvent           # 一步对外可见事件（stepIndex、phase、摘要、可选 tool 名）
│   ├── ManusRunResult           # 整次任务：最终文本、终止原因、步数等
│   ├── ManusTerminationReason   # 枚举：MAX_STEPS、MODEL_DONE、USER_TOOL_TERMINATE、ERROR…
│   ├── ManusStepExecutor        # 接口：执行「一步」对话（内部一次 ChatClient 调用链）
│   ├── ManusBrainResolver       # 接口：为「本次 Manus 任务」解析出 StepExecutor 使用的引擎（路由预留点）
│   └── ManusStepEventSink       # 接口：消费 Step 事件（SSE 适配器、LoggingSink、NoOp）
├── policy
│   └── RagParticipationPolicy   # 接口：给定 stepIndex，本步是否挂 RAG（当前实现：step==1）
├── orchestration
│   └── ManusOrchestrator        # 仅负责：maxSteps 循环、调用 Executor、调用 Sink、终止判断
├── runtime
│   ├── DefaultManusBrainResolver        # 当前：从现有 Bean 解析「单任务单 ChatClient」（与 AiShoppingGuide 同源配置）
│   └── SpringAiManusStepExecutor        # 实现 ManusStepExecutor：组 prompt、按 policy 选 advisors、call/stream
└── web（可选，亦可放在 com.gen.ai.web）
    └── MinusChatController 或 扩展现有 Controller 的 mode 参数
```

**开闭原则**：新增「仅第一步 RAG」以外的策略时，只加 `RagParticipationPolicy` 实现或配置；新增路由时，只加 `ManusBrainResolver` 实现并装配。

---

## 2. 核心接口（职责一句话）

### 2.1 `ManusBrainResolver`（日后路由的唯一扩展点）

- **职责**：把「HTTP 请求上下文 / 用户选的 brain / 会话 id」解析为 **本次 Manus 任务冻结使用** 的「对话执行面」。  
- **今天**：实现类内部委托现有 `ChatClient.Builder` + 与 `AiShoppingGuideApp` **一致**的 advisor 装配思路，但返回 **已 `build()` 的 `ChatClient`** 或薄封装 `ManusChatRuntime`（内含同一个 `ChatClient`）。  
- **明天**：同接口下换 `RouterManusBrainResolver`，内部按规则选 `ChatModel`，仍保证 **同一任务返回对象在循环内不变**。

### 2.2 `ManusStepExecutor`

- **职责**：执行第 `k` 步：组 `ChatClient` 请求（含 memory、tools、toolContext），**本步内**仍允许 Spring AI 自带的 model↔tool 内层循环。  
- **不负责**：不决定 maxSteps、不写 Step 到 memory、不做「任务级」路由。

### 2.3 `RagParticipationPolicy`

- **职责**：`boolean useRag(int stepIndex)`；当前固定 `stepIndex == 1`。  
- **实现方式**（实现阶段二选一，在 Phase 3 定稿）：  
  - **A**：两步用 **两套** `ChatClient`（一套带 RAG advisor、一套不带），由 `Executor` 按 policy 选实例——**清晰、易测**；  
  - **B**：单 `ChatClient` + 自定义 Advisor 读 ThreadLocal/Context 判断本步是否 no-op——耦合略高，暂不优先。

### 2.4 `ManusStepEventSink`

- **职责**：`onEvent(ManusStepEvent)`；SSE Controller 注册一个 sink 刷前端；单元测试用 `ListSink`。

### 2.5 `ManusOrchestrator`

- **职责**：`run(ManusRunRequest) -> ManusRunResult`；循环内：**先** `sink.onEvent(STARTED)` → `executor.executeStep(k)` → `sink.onEvent(TOOL/OBS/...)` → 检查终止条件。  
- **禁止**：内聚 HTTP、敏感词、RAG 细节（敏感词可在 Controller 或单独请求守卫层）。

---

## 3. 分阶段交付（按顺序做；每阶段结束可停住评审）

### Phase 1 — 契约与空实现（无业务风险）✅ 已实现

**目标**：编译通过 + 单测可跑「假 Orchestrator」。

**交付物**：

- `api` 包下：`ManusStepEvent`、`ManusRunResult`、`ManusTerminationReason`、`ManusStepExecutor`、`ManusBrainResolver`、`ManusStepEventSink` 接口与不可变 DTO。  
- `policy/RagParticipationPolicy` + `FirstStepOnlyRagPolicy` 默认实现。  
- `ManusOrchestrator` 骨架：依赖接口，循环内调用 **NoOp** 或 stub executor（返回固定字符串），验证 **步数上限** 与 **sink 被调用次数**。

**验收**：无 Spring 上下文亦可对 Orchestrator + Policy 做单测。

**实现备注**：

- 包名：`com.gen.ai.application.manus`。
- 占位：`PlaceholderManusBrainResolver`、`PlaceholderManusChatRuntime`；编排内 **仅一次** `resolve`。
- 日志：`DefaultManusOrchestrator`、`PlaceholderManusBrainResolver` 使用 `>>>> [Manus-*]` 前缀；可选 `LoggingManusStepEventSink` 装饰下游 Sink。
- 对外 API：Phase 4 使用 **`GET /ai/chat/manus`** 与短路径 **`GET /ai/manus`**（与普通 `GET /ai/chat` 分接口）。
- 单测：`DefaultManusOrchestratorTest`、`FirstStepOnlyRagPolicyTest`。

---

### Phase 2 — `ManusBrainResolver` + 冻结引擎（对接现有 Bean，仍可不接真实对话）✅ 已实现

**目标**：实现 `DefaultManusBrainResolver`：从 Spring 容器取得 `ChatClient.Builder`（或与 `AiShoppingGuideApp` 共享的工厂），**`build()` 一次**，封装进 `ManusChatRuntime`；`ManusOrchestrator` 整次 run **只向 Resolver 要一次**。

**交付物**：

- `ManusChatRuntime`：`frozenChatClient()` 默认 empty；实现类 `ChatClientManusChatRuntime` 持有 `final ChatClient`。  
- `ShoppingGuideChatClientFactory`：`AiShoppingGuideApp` 与 Manus **共用**同一套 defaultAdvisors 装配，避免双份复制。  
- `DefaultManusBrainResolver`（`@Component`）：`resolve` 内 `factory.buildFrozenClient(...)` 一次，日志带 `wiselink.active-brain` 与 `identityHashCode`。  
- 文档/代码注释：**禁止** Orchestrator 在循环中再次调用 `resolve`（已由 Phase 1 编排保证）。

**验收**：`DefaultManusBrainResolverSpringBootTest` — `frozenChatClient` 非空；同一次 `DefaultManusOrchestrator` 两步内 `ChatClient` **引用相同**（`isSameAs`）。

**说明**：`AiShoppingGuideApp` 与每次 Manus `resolve` 各自 `build()` 出不同 `ChatClient` **实例**属预期；防换脑约束的是**同一 Manus 任务多步**共用 `resolve` 返回的那一个实例。与导购会话仍用 App 启动时 build 的那条 client，二者并行不混用。

---

### Phase 3 — `SpringAiManusStepExecutor`（真实一步对话）✅ 已实现

**目标**：每一步真实调用 `ChatClient`，工具链与现网一致；**第 1 步带 RAG、第 2 步起不带**（`FirstStepOnlyRagPolicy` + 双实例 `ChatClient` 同源 `ChatModel`，见 `ShoppingGuideChatClientFactory#buildFrozenClient` / `#buildFrozenClientWithoutRag`）。

**交付物**：

- `ChatClientManusChatRuntime`：同时冻结带 RAG / 不带 RAG 两个 `ChatClient`；`selectForStep(step, policy)` 择一；`frozenChatClient()` 仍返回带 RAG 实例供诊断。  
- `SpringAiManusStepExecutor`：`@Component`，对齐导购侧 system / tools / `toolContext`（含 `biz_category`）；整次 Manus 任务共享一个 `AtomicInteger` 传入 `ShoppingGuideMergedToolCallbacks.allToolCallbacks(...)`（`ManusRunContext#manusTaskToolBudget`）。  
- `ManusOrchestrationConfiguration`：`ManusOrchestrator`、`ManusStepEventSink`（当前 `LoggingManusStepEventSink` + `NoOp`）Bean；HTTP Manus 见 Phase 4 `ManusChatSseService`（每请求自建 Sink）。  
- **未做**（留待后续若需进一步去重）：把 advisor 装配抽到独立 `AdvisorChatClientFactory` 并让 `AiShoppingGuideApp` 完全委托——当前以工厂双方法 + 导购侧 persona 抽取（`AssistantGuidePersonaLoader`）降低重复为主。

**验收**：`mvn test "-Dtest=com.gen.ai.application.manus.**"` 通过；`DefaultManusBrainResolverSpringBootTest` 断言同一次 run 内 step1/step2 选不同 client、工具预算计数器同一实例；全量 `mvn test` 仍可能因 **DashScope 额度**、**WiseLink 工具注册断言** 等与 Manus 无关项失败。

**注意**：终止策略当前为 MVP：`maxSteps`、或响应中含工具预算耗尽提示子串、或 executor 显式 `finish`；「无 tool 且自然语言收束」等细粒度枚举优先级可在产品对齐后收紧。

---

### Phase 4 — Web 层与 SSE Step 事件 ✅ 已实现

**目标**：用户可见「Step 1 / Step 2」。

**交付物**：

- `JsonSseManusStepEventSink` + `ManusChatSseService`：`ManusStepEvent` 序列化为 JSON，经 SSE `event: manus` 下发；编排结束后追加 `event: done`（`ManusDoneEventDto`：finalSummary、termination、executedSteps）。每 HTTP 请求在 `subscribeOn(boundedElastic)` 内自建 `DefaultManusOrchestrator` + Sink，**不**复用全局 `ManusStepEventSink` Bean，避免多用户串事件。
- `GET /ai/chat/manus` 与短路径 **`GET /ai/manus`**（context-path `/api` 时即 **`/api/ai/chat/manus`** / **`/api/ai/manus`**）等价，`?prompt=...&sessionId=...&category=...&maxSteps=5`（`produces=text/event-stream`），与普通流式 `GET /ai/chat` **分路径**。
- 敏感词：`SensitiveWordService` 与 `AiShoppingGuideApp#doChatStream` 一致；`prompt` / `sessionId` / `category` 语义与现网导购对齐。

**验收**：curl / 浏览器订阅 SSE 可见多条 `Manus` 与一条 `done`；`JsonSseManusStepEventSinkTest` 校验事件名与 JSON 字段。

---

### Phase 5 — 观测、限额、文档 ✅ 已实现

**目标**：可运维 + 与 README、`docs/MANUS-ARCHITECTURE.md` 一致。

**交付物**（已落地）：

- **结构化日志**：`DefaultManusOrchestrator` 记录 `chatId`（与 HTTP `sessionId` 对齐）、`maxSteps`、外层 `step`、`ragOn`、`runtimeId`；`SpringAiManusStepExecutor` 记录 `step`、`ragPolicy`、本步所选 `ChatClient` 与 `manusTaskToolBudget` 的 `identityHashCode`；`LoggingManusStepEventSink` 记录 `phase`、`stepIndex`、步摘要等（前缀 `>>>> [Manus-*]`，便于 grep）。
- **工具预算**：`DefaultManusOrchestrator#run` 内创建**单一** `AtomicInteger`，经 `ManusRunContext` 传入各步 `SpringAiManusStepExecutor` 与 `ShoppingGuideMergedToolCallbacks.allToolCallbacks(...)`，与 `PerRequestToolBudgetToolCallback` **整次任务多步累计**语义一致。
- **文档**：`README.md`「Manus 模式」小节链至 **`docs/MANUS-DESIGN-PHASES.md`**；架构说明见 **`docs/MANUS-ARCHITECTURE.md`**，其中 **§6** 补充 Phase 2～5 落地索引与指向本文的链接。

**验收**：日志可对齐单次请求的 step / rag；同一 Manus 任务多步日志中工具预算计数器 `identityHashCode` 一致；README 与 `docs/` 内两篇文档交叉链接有效。

---

### Phase A — SSE `event: manus` JSON 可观测字段（向后兼容）✅

**目标**：在**不改变**编排语义、不改动「循环外一次 `resolve` / 双 `ChatClient` RAG 策略 / 工具预算共享」的前提下，让步事件 JSON 足够支撑前端「智能体面板」式展示（阶段、耗时、是否仍要工具、RAG 开关等）。

**约定**（`ManusStepEvent` → `ManusStepEventDto`，经 `JsonSseManusStepEventSink` 下发）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `phase` | string | 与 `ManusStepPhase` 枚举同名：`RUN_STARTED`、`PLAN_SNIPPET`（Phase B）、`STEP_STARTED`、`STEP_OUTCOME`、`RUN_FINISHED`。 |
| `stepIndex` | int 或 null | 从 1 开始；生命周期类事件可为 null。 |
| `summary` | string | 人类可读摘要；`STEP_OUTCOME` 时为完整助手可见文本（与现网一致）。 |
| `toolHint` | string 或 null | 本步模型声明的工具名列表（逗号分隔），无工具调用时为 null。 |
| `ragOn` | bool 或 null | 本步是否按 `RagParticipationPolicy` 启用 RAG；`STEP_STARTED` / `STEP_OUTCOME` 时有值。 |
| `latencyMs` | long 或 null | `SpringAiManusStepExecutor` 单次 `ChatClient.call()` 墙钟耗时（毫秒）；仅 `STEP_OUTCOME` 有值。 |
| `messageType` | string 或 null | `ManusStepMessageType`：`META`、`PLAN_SNIPPET`（Phase B 计划摘要）、`MODEL`、`TOOL`。 |
| `hasPendingToolCalls` | bool 或 null | 与 Spring AI `ChatResponse#hasToolCalls()` 对齐；`STEP_OUTCOME` 时有值。 |

**兼容**：旧前端可继续只读 `phase` / `stepIndex` / `summary`；新字段缺省为 JSON `null`（由 DTO 映射）。`ManusStepExecutor` 自定义实现若未填充 `ManusStepOutcome` 的扩展字段，编排层仍按 `hasPendingToolCalls` 缺省视为「无挂起工具」映射 `messageType`。

**实现要点**：`ManusStepOutcome` 增加可选的 `stepLatencyMillis`、`hasPendingToolCalls`、`toolHintForObservers`；`DefaultManusOrchestrator` 将 policy 的 `ragOn` 与 outcome 合并进 `ManusStepEvent.stepOutcome(...)`。

---

### Phase B — 可选首步前计划（`ManusPlanner`，不进 Memory）✅

**目标**：在 `RUN_STARTED` 之后、外层 `STEP_STARTED` 之前，可选多一条 **`PLAN_SNIPPET`** 事件，便于前端展示「任务理解」；**不**经 `MessageChatMemoryAdvisor` 写入会话 Memory。

**开关**：`wiselink.manus.planner` = `noop`（默认）| `llm`。`llm` 时使用与当前激活 `ChatModel` 一致的 **`ChatClient.builder(chatModel).build()`**（无默认 advisors）做一次短 system+user 调用；失败则静默跳过（与 `LlmManusPlanner` 内 try/catch 一致）。

**契约**：`ManusPlanner#planBrief(ManusRunContext)`；`NoOpManusPlanner`；`DefaultManusOrchestrator` 在 `resolve` 之后调用，不改变「单次 `resolve`」与后续 `ManusStepExecutor` 语义。

**验收**：`DefaultManusOrchestratorTest` 含 planner 返回文案时 phase 序列为 `RUN_STARTED` → `PLAN_SNIPPET` → …；默认 `noop` 下与 Phase A 前行为一致（无 `PLAN_SNIPPET`）。

---

### Phase C — 单次 run 遥测（`traceId` + `activeBrainTag`）✅

**目标**：在**不改变**「循环外一次 `resolve`」、双 `ChatClient` RAG 策略、工具预算共享语义的前提下，为整次 Manus run 分配稳定 **`traceId`**（当前为 UUID 字符串），并把 **`activeBrainTag`**（与 `wiselink.active-brain` / 冻结 `ManusChatRuntime` 一致）写入 **编排日志、`ManusStepEvent`（经 SSE JSON）、`SpringAiManusStepExecutor` / `LoggingManusStepEventSink` 日志**；为日后 HTTP 头对齐与 `RouterManusBrainResolver` 预留同一字段。

**交付物**：

- `ManusRunContext#traceId()`；`DefaultManusOrchestrator` 在 `run` 入口生成并贯穿 `emit(...)`；`ManusStepEvent#withRunTelemetry` 在 sink 前绑定。
- `ManusChatRuntime#activeBrainTag()`（默认 empty）；`ChatClientManusChatRuntime` / `DefaultManusBrainResolver` 传入解析时的大脑标签。
- `ManusStepEventDto`：`traceId`、`activeBrainTag`（Jackson 可省略 null）；`JsonSseManusStepEventSinkTest` 覆盖带遥测序列化。

**验收**：同一次 run 内所有 sink 事件共享同一 `traceId`（见 `DefaultManusOrchestratorTest`）；SSE JSON 在编排注入后可读到 `traceId`（及有配置时的 `activeBrainTag`）。

---

## 4. 与 `AiShoppingGuideApp` 的关系（避免重复造轮子）

| 方式 | 说明 |
|------|------|
| **推荐** | 抽出 **`AdvisorChatClientFactory`**（命名可议）：输入 `includeRag: boolean`，输出配置好的 `ChatClient`；`AiShoppingGuideApp` 与 `SpringAiManusStepExecutor` 都调用它。 |
| **可接受** | 短期 Manus 专用装配类，与 `AiShoppingGuideApp` 并行，但 **必须在 Phase 3 内合并工厂**，否则双倍维护。 |

---

## 5. 刻意不做的事（本设计阶段结束后再议）

- 动态按复杂度切换模型（`ManusBrainResolver` 的第二个实现）。  
- Step 内容写入 ChatMemory。  
- 每步 RAG。  
- 多 Agent 协作。

---

## 6. 立项备忘（历史记录）

> 下列为原分阶段立项时的确认项；**代码已按 Phase 1～5 交付**，若后续有包名或 API 变更，请同步更新本文与 README。

1. 你确认 **包名** `com.gen.ai.application.manus` 是否 OK（或改为 `com.gen.ai.wiselink.Manus`）。  
2. Phase 4 对外 API：已采用 **`GET /ai/chat/manus`** 与短路径 **`GET /ai/manus`**（与普通 `GET /ai/chat` 分接口）。  
3. 回复「从 Phase 1 开始实现」后，再在仓库里落代码（按阶段 PR / commit）。

---

*文档版本：与 README「Manus 规划：模型选择」、[`MANUS-ARCHITECTURE.md`](./MANUS-ARCHITECTURE.md) 配套使用；两篇均位于 **`docs/`**。*
