# RAG：源指纹短路 + 侧车判重（大白话说明）

本地说明文档；`docs/` 已加入 `.gitignore` / `.cursorignore`，不进入版本库、默认不参与 AI 索引。

---

## 这波在解决什么问题？

1. **每次启动都全量灌向量**：知识源文件其实没变，却还要跑一遍提取、判重、写向量，又慢又费钱（尤其判重若走向量检索会**反复调 embedding**）。
2. **老办法用 `similaritySearch` 判重**：要给查询做向量再去库里比相似度，**判一次重就打一次 embedding**，启动时数据一多就很夸张。

因此做了两件事：

- **源指纹短路**：能证明「源没变、快照还在」时，**整段 `importDocs` 直接跳过**。
- **判重不依赖 similaritySearch**：判重改走**内存 + 小 JSON 台账**，避免为了判重去调向量检索（从而避免 embedding）。

---

## 一、源指纹短路（干嘛的）

**白话**：给「当前这批知识源文件」算一个**很短的代表串**（指纹）。若磁盘上**已经有向量快照**（`vector-store.json`），且侧车里记的**上一次成功灌库时的指纹**和**今天算出来的一样** → 认为源没改过 → **`importDocs` 直接 return**，不读大文件、不跑灌库流水线。

**指纹从哪来（实现里）**：

| 知识类型 | 行为 |
|----------|------|
| **json** | `goods_knowledge_base.json` 整文件 SHA-256，前缀形如 `json:{bizCategory}:{hex}`。 |
| **markdown** | `rag-docs` 下所有 `.md` 的路径 + 最后修改时间 + 文件大小，排序后揉进 SHA-256，前缀形如 `markdown:{bizCategory}:{hex}`。 |
| **mysql** | 返回**空**（没有「一个本地文件代表整库」的简单模型）→ **不做短路**，避免误跳过；以后若要短路可再接库表版本号等。 |

**入口代码锚点**：`RagDataService#importDocs` 开头：`fpOpt`、`vectorIndex`、`ragIngestionSidecar.getLastSourceFingerprint()`。

---

## 二、判重不依赖 similaritySearch（干嘛的）

**白话**：灌库时要判断「这条知识是否已以同样版本在向量里」。若用 `similaritySearch`，往往要对查询做 **embedding**。现在改为在**侧车**里维护台账（例如 JSON 商品的 `goods_id` + `update_time`，Markdown 的 `source` + `file_hash`），判重时**只查台账**，**不调向量库检索**。

**锚点**：`VectorStoreKnowledgeDuplicateChecker` 只委托 `RagIngestionSidecar`；台账在灌库策略里随删改更新并落盘。

---

## 三、新增类一览

| 类 | 作用（白话） |
|----|----------------|
| **`VectorStoragePaths`** | 统一解析：`app.storage.vector-db` 为**目录**时索引为 `vector-store.json`；侧车 `rag-ingestion-sidecar.json` 与索引**同目录**。避免路径逻辑散落、写岔。 |
| **`KnowledgeRevisionFingerprinter`** | 接口：给定知识类型 + 类目，**可选**返回源指纹字符串。 |
| **`DefaultKnowledgeRevisionFingerprinter`** | 实现：json / markdown 按上表算指纹；mysql 返回空。 |
| **`RagIngestionSidecar`** | **侧车组件**：读写 `rag-ingestion-sidecar.json`（指纹 + 判重台账）。启动时若无侧车但有向量文件，会**从向量 JSON 回填**部分商品台账（老环境升级）。灌库成功后会写入最新指纹。 |
| **`VectorStoreKnowledgeDuplicateChecker`** | 判重门面：内部**只问侧车**，不调用 `VectorStore#similaritySearch`。 |

---

## 四、改动类一览（为何要动）

| 类 | 改动意图 |
|----|-----------|
| **`RagDataService`** | `importDocs` 短路判断；成功后 `setFingerprintAfterSuccessfulImport`；删库时清侧车；`saveIndex` / `deleteSimpleVectorStoreFiles` 使用 `VectorStoragePaths`，与「目录 vs 单文件」配置一致。 |
| **`StorageConfig`** | 启动 `SimpleVectorStore#load` 使用 `VectorStoragePaths.resolveVectorIndexFile`，避免 `vector-db` 配成目录时加载错路径。 |
| **`JsonKnowledgeIngestionStrategy` / `MarkdownKnowledgeIngestionStrategy`** | 删/接受文档时更新侧车台账并持久化，与判重、指纹一致。 |
| **`KnowledgeVectorIngester`**（若存在装配） | 注入的判重实现改为基于侧车的 checker。 |

---

## 五、时间线（读代码顺序）

1. **Spring 启动** → `RagIngestionSidecar` `@PostConstruct`：有侧车则加载；无侧车但有 `vector-store.json` 则尽量**回填**台账。
2. **`RagDataService#importDocs`**：算指纹 → 向量文件存在且指纹与侧车一致 → **打日志并 return**（短路）。
3. 未短路 → `KnowledgeImportPipeline#loadDocuments` → `KnowledgeVectorIngester#ingest` → 判重走侧车，**不 embedding**。
4. 成功后写入指纹到侧车。
5. **`deleteDocs`**：删向量相关文件并清侧车，避免旧指纹导致「假短路」。

---

## 六、磁盘上会多什么

与 `vector-store.json` **同目录**：

- **`rag-ingestion-sidecar.json`**：上次成功灌库后的源指纹 + 判重台账（及与类型/类目相关的元数据字段，以实际 JSON 为准）。

---

## 七、和 MySQL 知识源的关系

指纹对 **mysql** 为空 → **不会做源指纹短路**；侧车仍可预留为将来从 DB 维护台账。当前行为以代码为准。
