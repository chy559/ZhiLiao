# 考辅智聊

基于 RAG（Retrieval-Augmented Generation）的智能考辅问答系统，面向私有知识库场景，支持文档上传解析、语义检索与大模型问答。

项目通过“关键词检索 + 向量检索”的混合检索能力提升召回效果，并结合 WebSocket 流式输出实现前端打字机式交互体验。

## 技术栈

### 后端与基础设施
- Spring Boot 3
- Spring Security
- MySQL
- Redis
- Elasticsearch
- MinIO
- Kafka
- WebSocket

### AI 与检索
- RAG 检索增强流程
- 阿里 Embedding 模型（2048 维）
- Elasticsearch KNN 向量召回
- IK 分词 + BM25 重排序

### 前端
- Vue 3 + TypeScript
- Vite
- Naive UI
- Pinia
- Vue Router
- UnoCSS + SCSS

## 项目核心能力

- 文档解析与分段：支持知识文档解析、切片与结构化存储。
- 向量化入库：文本向量化后写入 Elasticsearch，支持语义召回。
- 混合检索：关键词过滤与向量检索协同，提升检索准确性与可解释性。
- 增强型 Prompt 构建：结合用户问题与召回片段生成上下文，驱动大模型回答。
- 流式问答：WebSocket 长连接 + 大模型 Stream API，实现逐字输出。

## 个人职责与亮点

### 1. 分片上传与断点续传
- 基于 Redis Bitmap 存储文件分片状态。
- 结合 MinIO 实现大文件分片上传、断点续传。
- 减少重复分片写入，节约存储空间并显著降低上传耗时。

### 2. Kafka 异步解耦处理链路
- 将“上传 -> 解析 -> 向量化 -> 入库”流程通过 Kafka 异步解耦。
- 提升系统削峰能力和可扩展性。
- 文档处理整体效率提升约 3 倍。

### 3. Elasticsearch 混合检索
- 使用 IK 分词器建立倒排索引，支持关键词检索。
- 接入 2048 维 Embedding 向量并启用 KNN 召回。
- 组合“关键词过滤 + 语义召回 + BM25 重排”实现双引擎搜索。

### 4. RAG 检索链路落地
- 通过“用户问题 + 检索片段”动态拼接增强型 Prompt。
- 基于语义上下文约束大模型输出，提升问答准确度与相关性。

### 5. 前端流式交互体验优化
- 基于 WebSocket 实现前后端实时通信。
- 集成大模型 Stream API，实现答案逐字返回。
- 前端实现“打字机”效果，提升问答反馈速度与交互体验。

## 系统架构（简述）

1. 用户上传文档（分片上传）
2. MinIO 存储文件，Kafka 投递处理任务
3. 文档解析与分块
4. 文本向量化并写入 Elasticsearch
5. 用户提问触发混合检索
6. 构建增强型 Prompt 调用大模型
7. WebSocket 流式返回答案

## 项目目录

```bash
PaiSmart-main/
├── src/                     # Spring Boot 后端代码
├── frontend/                # Vue3 前端工程
├── docs/                    # 项目文档
├── pom.xml                  # Maven 配置
└── README.md
```

## 快速启动

### 后端
```bash
# 1. 配置 MySQL / Redis / Elasticsearch / MinIO / Kafka
# 2. 修改 application.yml
# 3. 启动后端
mvn spring-boot:run
```

### 前端
```bash
cd frontend
pnpm install
pnpm dev
```

## 适用场景

- 校内课程资料问答
- 企业内部知识库检索
- 私有文档智能助手
- 垂直领域 FAQ 自动应答


