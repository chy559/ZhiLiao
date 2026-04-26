# PaiSmart Bug 修复与部署说明

## 修复概览

| Bug | 问题描述 | 根因 | 影响范围 |
|-----|---------|------|---------|
| Bug 1 | 上传文件后 AI 回复"暂无相关信息" | WebSocket 提取的 userId 格式与 ES 存储不一致 | 所有用户的 RAG 问答功能 |
| Bug 2 | 跨用户数据泄露（间歇性） | ES 8.x KNN 向量搜索未加权限过滤 | 多用户数据隔离 |
| Bug 3 | 切换页面后 AI 回复内容丢失/空白 | WebSocket 消息处理器随组件卸载被销毁 | 流式回复期间的页面导航 |
| Bug 4 | 服务器环境复制按钮无反应 | `navigator.clipboard` 仅在 HTTPS / localhost 下可用，HTTP 部署时为 undefined | 服务器 HTTP 部署下的所有复制交互 |
| Bug 5 | 服务器对话时间时/分偏差 8 小时 | JVM 与 MySQL 连接使用 UTC 时区，`LocalDateTime.now()` 未指定 ZoneId | 服务器下所有对话消息时间显示 |
| Bug 6 | 个人文件预览/下载提示"文件不存在或无权限访问" | 预览/下载接口从 `userDetails.getUsername()` 或 `extractUsernameFromToken` 拿到的是用户名（"67677"），与 FileUpload 表存储的数据库ID（"2"）不匹配 | 所有用户的文件预览与下载 |
| Bug 7 | PDF / DOC / DOCX 预览显示乱码 | `getFilePreviewContent` 将 PDF 等二进制文档也按 UTF-8 直接读取，把二进制字节当作字符渲染 | 所有二进制文档格式的预览 |
| Bug 8 | 上传头像后被踢回登录页且头像未更新 | `@RequestAttribute("userId")` 在 `/users/avatar` 路径上未被 `OrgTagAuthorizationFilter` 写入；同时前端手动设 `Content-Type: multipart/form-data` 丢失 boundary | 头像上传功能 |
| Bug 9 | 改回原昵称时私人空间标签名不同步更新 | 用 `PRIVATE_+currentUsername` 拼 tagId 查找，私人标签 tagId 实际是注册时固化的 `PRIVATE_<注册名>`，第二次改名后查不到 | 修改昵称功能 |

---

## Bug 1：上传文件后 AI 回复"暂无相关信息"

### 问题现象

用户 67677 上传了两个文件（个人简历、设计说明），后端确认 userId=2、fileCount=2，但 AI 聊天始终回复"暂无相关信息"。

### 根因分析

**userId 格式不一致：** HTTP 接口（文件上传）通过 `OrgTagAuthorizationFilter` 使用 `jwtUtils.extractUserIdFromToken()` 提取数据库 ID（如 `"2"`），而 WebSocket 聊天通过 `ChatWebSocketHandler.extractUserId()` 使用 `jwtUtils.extractUsernameFromToken()` 提取用户名（如 `"67677"`）。

- ES 中文档的 `userId` 字段存储的是数据库 ID `"2"`
- 聊天搜索时用用户名 `"67677"` 去匹配，`findUser("67677")` 被解析为 Long 类型 67677，数据库中找不到该 ID → 异常 → 搜索结果为空 → LLM 无上下文 → 回复"暂无相关信息"

同时 `resolveSearchOwnerId()` 返回的是 `getUsername()` 而非数据库 ID，即使 findUser 成功也无法与 ES 中的 userId 匹配。

### 修改文件

**1. `src/main/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandler.java`**

`extractUserId()` 方法改为优先使用 `extractUserIdFromToken()`：

```java
private String extractUserId(WebSocketSession session) {
    String path = session.getUri().getPath();
    String[] segments = path.split("/");
    String jwtToken = segments[segments.length - 1];

    // 优先提取数据库 ID，与 HTTP 接口保持一致
    String userId = jwtUtils.extractUserIdFromToken(jwtToken);
    if (userId != null) {
        logger.debug("从JWT令牌中提取的用户ID: {}", userId);
        return userId;
    }

    // 降级：提取用户名
    String username = jwtUtils.extractUsernameFromToken(jwtToken);
    if (username != null) {
        logger.warn("无法从JWT令牌中提取用户ID，使用用户名: {}", username);
        return username;
    }

    logger.warn("无法从JWT令牌中提取用户信息，使用令牌作为用户ID: {}", jwtToken);
    return jwtToken;
}
```

**2. `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`**

`resolveSearchOwnerId()` 改为返回数据库 ID：

```java
String resolveSearchOwnerId(String userId) {
    return findUser(userId).getId().toString();
}
```

**3. `src/test/java/com/yizhaoqi/smartpai/service/HybridSearchServiceTest.java`**

更新测试断言以匹配新行为（返回数据库 ID 而非用户名）。

---

## Bug 2：跨用户数据泄露（间歇性）

### 问题现象

用户 A 上传的私有资料，用户 B 偶尔能通过问答获取到内容。现象间歇性出现。

### 根因分析

**ES 8.x KNN 搜索独立执行：** 在 Elasticsearch 8.x 中，`knn` 子句和 `query` 子句是**独立执行、结果合并**的。权限过滤 `permissionFilter` 只加在了 `query` 子句上，KNN 向量搜索会扫描**全部文档**，不受权限限制。

间歇性是因为：只有当其他用户的文档向量与查询向量足够相似时，KNN 才会召回这些文档。

### 修改文件

**`src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`**

在 `searchWithPermission()` 的 KNN 子句中添加权限过滤：

```java
s.knn(kn -> kn
        .field("vector")
        .queryVector(queryVector)
        .k(recallK)
        .numCandidates(recallK)
        .filter(permissionFilter));  // 新增：KNN 也需要权限过滤
```

在 `searchPublic()` 的 KNN 子句中添加公开文档过滤：

```java
Query publicFilter = Query.of(qf -> qf.term(t -> t.field("isPublic").value(true)));
s.knn(kn -> kn
        .field("vector")
        .queryVector(queryVector)
        .k(recallK)
        .numCandidates(recallK)
        .filter(publicFilter));  // 新增：仅搜索公开文档
```

旧的无权限 `search()` 方法标记为 `@Deprecated`。

---

## Bug 3：切换页面后 AI 回复内容丢失

### 问题现象

在 AI 流式回复过程中切换到其他页面，再返回聊天页面后显示空白的 AI 回复气泡。后端日志显示回复已正常生成。

### 根因分析

**组件级 watcher 随页面切换被销毁：** WebSocket 消息处理的 `watch(wsData, ...)` 写在 `input-box.vue` 组件内。Vue Router 导航时组件卸载，watcher 被销毁，后续收到的 WebSocket 消息无人处理。而 Pinia store 的生命周期独立于组件，不受页面切换影响。

同时，`chat-list.vue` 的 `getList()` 在组件挂载时会从服务器重新加载对话历史，但后端在 AI 回复完成后才持久化，导致正在进行的流式内容被空数据覆盖。

### 修改文件

**1. `frontend/src/store/modules/chat/index.ts`**

将 WebSocket 消息处理从组件迁移到 Pinia store，新增 `streaming` 标志：

```typescript
/** 是否正在流式接收AI响应 */
const streaming = ref(false);

// 在 store 中处理所有 WebSocket 消息（不随组件卸载）
watch(wsData, val => {
  if (!val) return;
  try {
    const data = JSON.parse(val);
    if (data.type === 'connection' && data.sessionId) {
      sessionId.value = data.sessionId;
      return;
    }
    const assistant = list.value[list.value.length - 1];
    if (!assistant || assistant.role !== 'assistant') return;

    if (data.type === 'completion' && data.status === 'finished') {
      if (assistant.status !== 'error') assistant.status = 'finished';
      streaming.value = false;
    } else if (data.type === 'stop') {
      assistant.status = 'finished';
      streaming.value = false;
    } else if (data.error) {
      assistant.status = 'error';
      streaming.value = false;
    } else if (data.chunk) {
      assistant.status = 'loading';
      assistant.content += data.chunk;
      streaming.value = true;
    }
  } catch { }
});
```

**2. `frontend/src/views/chat/modules/input-box.vue`**

移除组件内的 `watch(wsData, ...)` 和对 `wsData` 的引用。

**3. `frontend/src/views/chat/modules/chat-list.vue`**

`getList()` 增加流式保护，防止覆盖正在接收的数据：

```typescript
async function getList() {
  if (chatStore.streaming) return;
  const lastMsg = list.value[list.value.length - 1];
  if (lastMsg?.role === 'assistant' && ['loading', 'pending'].includes(lastMsg.status || '')) return;
  // ... 正常加载逻辑
}
```

---

## Bug 4：服务器环境复制按钮失效（本地正常）

### 问题现象

本地 `localhost` 开发环境下，聊天消息右下角的"复制"按钮、知识库列表的"复制 MD5"都能正常使用；部署到服务器（HTTP 协议，`http://8.148.76.22`）后，点击复制按钮无任何反应，控制台报 `Cannot read properties of undefined (reading 'writeText')` 或类似错误。

### 根因分析

`navigator.clipboard` 属于**安全上下文（Secure Context）专用 API**，浏览器只在以下环境暴露：

- HTTPS 协议的页面
- `localhost` / `127.0.0.1`
- `file://` 本地文件

服务器走 HTTP 明文访问时，`navigator.clipboard` 为 `undefined`，调用 `.writeText()` 直接抛异常，后续 `$message?.success('已复制')` 并没有真正写入剪贴板（即便提示弹出）。

### 修复方案

采用**优先使用现代 API + 降级到 `document.execCommand('copy')`** 的兼容写法，不改动部署协议即可工作。后续如果服务器配置 HTTPS，新代码自动走 `navigator.clipboard` 分支，无需再改。

### 修改文件

**1. `frontend/src/views/chat/modules/chat-message.vue`**

替换 `handleCopy()`，新增 `copyTextCompat()` 与 `fallbackCopy()`：

```ts
function copyTextCompat(text: string) {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    return;
  }
  fallbackCopy(text);
}

function fallbackCopy(text: string) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.top = '0';
  ta.style.left = '0';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try {
    document.execCommand('copy');
  } finally {
    document.body.removeChild(ta);
  }
}

function handleCopy(content: string) {
  copyTextCompat(content);
  window.$message?.success('已复制');
}
```

**2. `frontend/src/views/knowledge-base/index.vue`**

新增相同的 `copyTextCompat()` / `fallbackCopy()` 工具函数，并将 MD5 列的复制调用 `navigator.clipboard.writeText(row.fileMd5)` 替换为 `copyTextCompat(row.fileMd5)`。

---

## Bug 5：服务器对话时间时/分偏差 8 小时（日期正确）

### 问题现象

本地运行时，聊天消息显示的时间与当前时间完全一致；部署到服务器后，同一条消息的日期正确，但**小时、分钟比真实时间少 8 小时**（即显示的是 UTC 时间而非北京时间）。

### 根因分析

三处叠加导致：

1. **JVM 容器时区默认 UTC**：`docs/docker-compose.yaml` 中 `backend` 服务没有设置 `TZ`，容器内 `java.time.LocalDateTime.now()` 返回的是 UTC 时间。
2. **代码未显式指定 ZoneId**：`ChatHandler.java` 两处 `LocalDateTime.now()` / `.now().toString()` 依赖 JVM 默认时区，部署环境一变就错。
3. **JDBC 连接串时区写死 UTC**：`application-docker.yml` 的 `serverTimezone=UTC` 导致 Hibernate 写入/读取 `LocalDateTime` 时按 UTC 解释，和本地 Asia/Shanghai 行为不一致。

本地之所以正常，是因为本地 JVM 默认时区跟随 Windows/系统（Asia/Shanghai），掩盖了代码层问题。

### 修复方案

**三处同时修**：容器时区 + 代码显式 ZoneId + JDBC 连接时区。冗余一些但最稳，防止任何一层被遗漏又复现。

### 修改文件

**1. `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`**

`updateConversationHistory()` 内时间戳生成（约第 264 行）：

```java
String currentTimestamp = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
```

`sendCompletionNotification()` 内完成通知的 `date` 字段（约第 360 行）：

```java
"date", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
```

**2. `src/main/resources/application-docker.yml`**

JDBC 连接串时区改为上海：

```yaml
url: jdbc:mysql://mysql:3306/PaiSmart?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
```

**3. `docs/docker-compose.yaml`**

`backend` 服务的 `environment` 增加容器时区与 JVM 时区参数：

```yaml
environment:
  - SPRING_PROFILES_ACTIVE=docker
  - JAVA_OPTS=-Xmx512m -Xms256m -Duser.timezone=Asia/Shanghai
  - TZ=Asia/Shanghai
```

> 建议同时给 `mysql`、`redis`、`kafka`、`es`、`minio` 等服务的 `environment` 都加上 `TZ=Asia/Shanghai`，统一整个栈的时区（非本次必改，但推荐顺手做）。

### 验证方法

服务器重启 backend 后进入容器：

```bash
docker exec -it backend sh -c 'date'
# 期望输出 CST / +0800 时间
```

前端发起一条新对话，确认消息气泡上方时间与北京时间一致。

---

## Bug 6：个人文件预览/下载提示"文件不存在或无权限访问"

### 问题现象

用户登录后进入"文件列表"，列表可以正常显示自己上传的文件（个人简历、PDF等），但点击任意文件做预览或下载时，右上角弹出 `文件不存在或无权限访问`，预览弹窗显示 `预览失败：Request failed with status code 404`。用户是文件拥有者，本就应当有权限。

### 根因分析

这是 Bug 1 同一套 userId 格式不一致问题，在文件预览/下载链路上的另一处体现：

- `FileUpload` 表 `user_id` 列存的是**数据库ID**（如 `"2"`）。上传链路由 `OrgTagAuthorizationFilter` 通过 `jwtUtils.extractUserIdFromToken()` 写入。
- `/api/v1/documents/uploads`（列表接口）使用 `@RequestAttribute("userId")`，拿到的也是数据库ID → 所以列表能查到文件。
- `/api/v1/documents/preview` 与 `/api/v1/documents/download` 两个接口在服务器上依赖 URL 里的 `token` 参数或 `SecurityContextHolder`，当时用的是：
  - `userDetails.getUsername()` → **用户名** `"67677"`
  - `jwtUtils.extractUsernameFromToken(token)` → **用户名** `"67677"`
- 接着 `documentService.getAccessibleFiles("67677", ...)` 查 `findByUserIdOrIsPublicTrue("67677")` / `findAccessibleFilesWithTags("67677", tags)`，但 DB 中 `user_id = "2"`，于是查不到用户自己的文件，只剩公开文件与组织文件，最终 `targetFile.isEmpty()` → 404 "无权限访问"。

此外 `DocumentService.getAccessibleFiles` 内部 `userRepository.findByUsername(userId)` 只能按用户名查用户，传入数据库ID时会抛 `用户不存在`。

### 修复方案

两步改：
1. **预览/下载接口统一使用数据库ID**：`extractUsernameFromToken(token)` → `extractUserIdFromToken(token)`，与上传接口保持同一套标识。
2. **`getAccessibleFiles` 兼容数据库ID与用户名**：先尝试 `userRepository.findById(Long.parseLong(userId))`，失败再按用户名查，保证历史调用方（如果还有传用户名的）也能工作。

### 修改文件

**1. `src/main/java/com/yizhaoqi/smartpai/controller/DocumentController.java`**

- `downloadFileByName()`：把 `token` 解析由 `extractUsernameFromToken` 改为 `extractUserIdFromToken`（含 catch 分支里日志用的那次）。
- `previewFileByName()`：去掉原先的 `SecurityContextHolder` 分支（因为它拿到的是用户名），统一走 URL `token` → `extractUserIdFromToken`；catch 分支同步修正。
- 删除不再使用的 `SecurityContextHolder`、`UserDetails` import。

**2. `src/main/java/com/yizhaoqi/smartpai/service/DocumentService.java`**

`getAccessibleFiles()` 对用户的解析改为：

```java
User user = null;
try {
    user = userRepository.findById(Long.parseLong(userId)).orElse(null);
} catch (NumberFormatException ignored) {
    // 非数字 userId，继续按用户名查
}
if (user == null) {
    user = userRepository.findByUsername(userId)
        .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
}
```

### 补充：前端 token 读取方式错误（与 Bug 6 同步修复）

本地复现时发现，即使后端全部修好，点击预览仍会提示 `文件不存在或需要登录访问`（注意是"登录"，不是"权限"）。这是后端进入了"匿名用户，只能看公开文件"的分支——说明 URL token 根本没到后端。

**根因**：`frontend/src/components/custom/file-preview.vue` 读 token 用的是：

```ts
const token = localStorage.getItem('token');
```

但项目封装了 `localStg = createStorage('local', 'PaiSmart_')`，token 实际存在 `localStorage["PaiSmart_token"]` 下并做了 `JSON.stringify` 包装。裸 `localStorage.getItem('token')` 永远返回 `null` → 后端收到的 `token` 是 `null` → userId 为 null → 走匿名分支。

**修复**：改为使用项目统一的 `localStg`：

```ts
import { localStg } from '@/utils/storage';
// ...
const token = localStg.get('token') || '';
```

`file-preview.vue` 中 `loadPreviewContent` 与 `downloadFile` 两处都要改。

### 验证方法

1. 前端登录任意账号，进入"文件列表"。
2. 点击任一自己上传的文件 → 期望可以正常预览、下载，不再弹"文件不存在或无权限访问"或"需要登录访问"。
3. 用另一账号登录，确认**只有**自己的文件、组织内可见文件、公开文件能预览，不能预览别人的私有文件（Bug 2 边界应继续成立）。

---

## Bug 7：PDF / DOC / DOCX 预览显示乱码

### 问题现象

解决 Bug 6 之后，点击 PDF、Word 文档预览虽然不再 404，但窗口里显示的是一串非可读的乱码（`%PDF-1.4` 开头的二进制字节 + 各种控制字符和方块），而不是文档里的文字。TXT、MD 等纯文本文件显示正常。

### 根因分析

`DocumentService.getFilePreviewContent()` 的 `isTextFile()` 列表把 `pdf`、`doc`、`docx` 也当成了文本文件。对这些**二进制格式**，代码走了：

```java
new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))
```

把 PDF/Word 的二进制字节当 UTF-8 解码，前端用 `<pre>` 渲染 → 满屏乱码。二进制文档应该经过解析器（例如 Apache Tika）把内部文字提取出来再展示。

项目 `pom.xml` 已引入 `tika-core` + `tika-parsers-standard-package 2.9.1`，可以直接用。

### 修复方案

拆分识别函数、分流处理：

1. `isPlainTextFile(ext)`：只覆盖**真正的纯文本格式**（txt、md、json、csv、log、代码文件、html/htm、各种配置文件）。
2. `isTikaParsableFile(ext)`：覆盖**二进制文档格式**（pdf、doc、docx、ppt、pptx、xls、xlsx、odt、rtf）。
3. 新分支：对 Tika 可解析的文件用 `AutoDetectParser + BodyContentHandler(20000)` 提取前 ~20K 字符文本；解析失败时降级为文件信息描述，不再输出乱码。

### 修改文件

**`src/main/java/com/yizhaoqi/smartpai/service/DocumentService.java`**

新增 import：

```java
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
```

`getFilePreviewContent()` 分流：

```java
if (isPlainText) {
    // 原有 UTF-8 读取逻辑
} else if (isTikaParsable) {
    BodyContentHandler handler = new BodyContentHandler(20000);
    Metadata metadata = new Metadata();
    new AutoDetectParser().parse(inputStream, handler, metadata, new ParseContext());
    String text = handler.toString();
    // 空内容/截断/异常均有降级处理
    return text;
} else {
    // 非文本 & 非Tika可解析：返回文件信息
}
```

并把原先的 `isTextFile()` 拆成两个专用方法（见修复方案第 1、2 条）。

### 验证方法

1. 预览 PDF（如"个人简历-刘奇.pdf"）→ 窗口里显示的应是可读中文文本，不再是二进制乱码。
2. 预览 `.txt` / `.md` / `.json` → 依然正常。
3. 预览 `.jpg` / `.mp3` 等不支持的类型 → 显示"此文件类型不支持预览，请下载后查看"。

---

## 修改文件清单

| 文件 | 修改类型 | 关联 Bug |
|------|---------|---------|
| `src/main/java/.../handler/ChatWebSocketHandler.java` | 修改 `extractUserId()` | Bug 1 |
| `src/main/java/.../service/HybridSearchService.java` | 修改搜索逻辑 + 权限过滤 | Bug 1, 2 |
| `src/test/java/.../service/HybridSearchServiceTest.java` | 更新测试断言 | Bug 1 |
| `frontend/src/store/modules/chat/index.ts` | WS 处理迁移到 store | Bug 3 |
| `frontend/src/views/chat/modules/input-box.vue` | 移除组件级 WS watcher | Bug 3 |
| `frontend/src/views/chat/modules/chat-list.vue` | 添加流式保护 | Bug 3 |
| `frontend/src/views/chat/modules/chat-message.vue` | 复制函数兼容非安全上下文 | Bug 4 |
| `frontend/src/views/knowledge-base/index.vue` | MD5 复制兼容非安全上下文 | Bug 4 |
| `src/main/java/.../service/ChatHandler.java` | 时间戳显式使用 Asia/Shanghai | Bug 5 |
| `src/main/resources/application-docker.yml` | JDBC serverTimezone 改为 Asia/Shanghai | Bug 5 |
| `docs/docker-compose.yaml` | backend 容器增加 TZ / user.timezone | Bug 5 |
| `src/main/java/.../controller/DocumentController.java` | 预览/下载接口统一使用数据库ID，移除 SecurityContext 分支 | Bug 6 |
| `src/main/java/.../service/DocumentService.java` | `getAccessibleFiles` 兼容数据库ID；预览按 PlainText / Tika 分流；新增 isPlainTextFile / isTikaParsableFile | Bug 6, 7 |
| `frontend/src/components/custom/file-preview.vue` | token 读取由裸 `localStorage.getItem('token')` 改为 `localStg.get('token')` | Bug 6 |
| `src/main/java/.../model/User.java` | 新增 `avatarUrl` 字段 | 头像功能 |
| `src/main/java/.../service/AvatarService.java` | 新建：MinIO 公共桶上传与初始化 | 头像功能 |
| `src/main/java/.../controller/UserController.java` | 新增 `/avatar`、`/profile`、`/password` 三个接口；`/me` 增加 `avatarUrl` | 头像功能 / 改昵称 / 改密码 / Bug 8 |
| `src/main/java/.../service/UserService.java` | 新增 `updateUsername`、`updatePassword`；私人标签 tagId 改为从 `user.orgTags` 提取 | 改昵称 / 改密码 / Bug 9 |
| `src/main/java/.../config/SecurityConfig.java` | 放行 `/users/avatar`、`/users/profile`、`/users/password` | 头像功能 / 改昵称 / 改密码 |
| `frontend/src/typings/api.d.ts` | `UserInfo` 增加 `avatarUrl?: string` | 头像功能 |
| `frontend/src/service/api/auth.ts` | 新增 `fetchUploadAvatar`、`fetchUpdateUsername`、`fetchUpdatePassword`；移除手动 multipart Content-Type | 头像功能 / Bug 8 / 改昵称 / 改密码 |
| `frontend/src/store/modules/auth/index.ts` | 暴露 `getUserInfo` 给外部 | 头像功能 / 改昵称 |
| `frontend/src/views/personal-center/index.vue` | 头像上传区、修改昵称弹窗、修改密码弹窗 | 头像功能 / 改昵称 / 改密码 |
| `frontend/src/layouts/modules/global-header/components/user-avatar.vue` | 顶栏头像支持显示 `avatarUrl` | 头像功能 |

---

## 部署说明

### 后端部署（Docker）

**本地构建并推送镜像：**

```powershell
# 在项目根目录执行（Windows PowerShell）
.\build-push.ps1
```

> 该脚本会执行 Maven 构建、Docker 镜像打包，并推送至阿里云容器镜像服务：
> `crpi-r3qkhh5h65pj3yag.cn-shanghai.personal.cr.aliyuncs.com/zhiliao123/backend:latest`

**服务器拉取并重启：**

```bash
ssh root@8.148.76.22

cd /opt/pai-smart
docker compose pull backend
docker compose up -d backend
docker compose up -d --force-recreate backend
```

### 前端部署

**本地构建：**

```bash
cd frontend
pnpm build
```

> 构建产物在 `frontend/dist/` 目录下。

**上传到服务器：**

```bash
# 在 frontend 目录下执行
rsync -avz --delete /home/lq67677/projects/PaiSmart-main/PaiSmart-main/frontend/dist/ root@8.148.76.22:/usr/share/nginx/html/
```

> 注意：根据实际 Nginx 配置，前端文件目录可能不同，请确认服务器上 Nginx 的 `root` 指向路径。

**如果需要重启 Nginx（通常不需要，静态文件替换即可生效）：**

```bash
ssh root@8.148.76.22
nginx -s reload
```

---

## 部署顺序建议

1. 先部署**后端**（涉及搜索逻辑和权限修复，是核心变更）
2. 再部署**前端**（流式消息处理优化，依赖后端正常运行）
3. 部署完成后测试：
   - 上传文件 → 聊天提问 → 验证能正确检索到内容（Bug 1）
   - 两个不同用户分别上传私有文件 → 互相提问 → 验证无法获取对方内容（Bug 2）
   - AI 回复过程中切换页面再返回 → 验证内容不丢失（Bug 3）
   - 服务器 HTTP 环境下点击聊天复制、知识库 MD5 复制 → 验证剪贴板真的拿到内容（Bug 4）
   - 新建对话发送消息 → 验证消息时间与北京时间一致，且 `docker exec backend date` 输出为 CST/+0800（Bug 5）
   - 登录后点击自己上传的文件 → 正常预览/下载，不再提示"文件不存在或无权限访问"（Bug 6）
   - 预览 PDF/Word → 显示可读文字而非二进制乱码（Bug 7）

### Bug 5 专用部署提醒

`docker-compose.yaml` 和 `application-docker.yml` 已更新：

```bash
ssh root@8.148.76.22
cd /opt/pai-smart
# 同步最新 docker-compose.yaml 后：
docker compose pull backend
docker compose up -d backend
# 或强制重建以确保环境变量生效：
docker compose up -d --force-recreate backend
```

---

## Bug 7 增强：PDF 原生预览（方案 A · iframe + 预签名 URL）

### 问题背景

Tika 文本抽取虽然解决了"二进制乱码"问题，但只能显示纯文字——PDF 中的图片、表格、字体样式、排版都丢失了。用户希望像本地 PDF 阅读器一样呈现原文档。

### 方案选择

在 4GB 内存服务器上，后端渲染 PDF（如 pdfbox 转图、LibreOffice 转 HTML）会把文件整体装入 JVM，单个大 PDF 就能吃掉几百 MB 甚至 OOM。因此采用**方案 A**：

- 后端仅**生成**一个带 `Content-Disposition: inline` 的 MinIO 预签名 URL（零文件字节进入 JVM）
- 浏览器直接从 MinIO 拉文件，用**浏览器内置的 PDF 查看器**渲染
- 图片同样走这条路径；txt/md/doc/docx 等保留 Tika 文本预览

### 变更明细

**1. `DocumentService.java`（已在前置步骤添加）**

- `generateInlinePreviewUrl(fileMd5, fileName)`：用 `GetPresignedObjectUrlArgs.extraQueryParams` 覆盖 MinIO 默认的 `attachment` 头，使浏览器内联打开
- `guessContentType(fileName)`：根据扩展名返回精准 MIME（`application/pdf`、`image/*` 等），避免浏览器误判

**2. `DocumentController.java` 新增 `/api/v1/documents/preview-url`**

- 复用 `/download` 的权限逻辑：URL 带 `token` → `extractUserIdFromToken` → `getAccessibleFiles` 过滤
- 匿名仅能访问公开文件（`findByFileMd5AndIsPublicTrue` / `findByFileNameAndIsPublicTrue`）
- 返回 `{ fileName, fileMd5, previewUrl, fileSize }`

**3. `frontend/src/components/custom/file-preview.vue`**

- 按扩展名分支：`pdf` → `<iframe>`；`png/jpg/jpeg/gif/webp/svg` → `<img>`；其余走原有 `/documents/preview`（Tika 文本）
- 新增"新标签打开"按钮，作为 iframe 不兼容时（如部分移动浏览器）的降级
- 请求头统一用 `localStg.get('token')`（承接 Bug 6 修复）

### 内存占用分析

| 路径 | 后端内存 |
| --- | --- |
| 方案 A（本次）| 0（仅一个 URL 字符串）|
| 后端渲染 PDF（未采用）| 单文件 100MB–500MB，并发下易 OOM |

### 部署步骤

与 Bug 6、Bug 7 相同——只需重启**后端**并重新构建前端：

```bash
mvn clean package -DskipTests
cd frontend && pnpm build
# 服务器
docker compose up -d --force-recreate backend
# Nginx 替换 frontend/dist 即可
```

### 验证

1. 登录后点击知识库里的 PDF → 在预览面板内直接看到原始 PDF（图片、字体、版式完整）
2. 点击 PNG/JPG → 内联显示图片
3. 点击 txt/md/doc/docx → 仍显示 Tika 抽取的纯文本
4. 点击"新标签打开"→ 浏览器新标签页原生打开 PDF，可缩放/搜索/打印
5. `docker stats backend` 观察预览大 PDF 期间 JVM 内存稳定，无尖峰

---

## 新增功能：用户头像上传（含 Bug 8）

### 设计目标

用最简单、对服务器开销最低的方式实现头像功能。

### 方案

**MinIO 公共读 bucket + 固定 URL**：
- 单独建一个 `avatars` bucket，启动时自动设为 `s3:GetObject` 公开可读
- 头像对象名固定为 `{userId}.{ext}`（覆盖式上传，永远只占用一份空间）
- 头像 URL = `{publicUrl}/avatars/{userId}.{ext}?v={timestamp}`
  - `?v=` 用来强制浏览器刷新缓存，否则改完头像还显示旧图
- 浏览器直读 MinIO，**不经过后端代理**——后端在显示路径上零开销

### 新增/修改文件

**1. `src/main/java/com/yizhaoqi/smartpai/model/User.java`**

新增字段：

```java
@Column(name = "avatar_url", length = 512)
private String avatarUrl;
```

**2. `src/main/java/com/yizhaoqi/smartpai/service/AvatarService.java`（新建）**

- `@PostConstruct init()`：启动时确保 `avatars` bucket 存在并设置 public-read 策略
- `uploadAvatar(userId, file)`：校验类型（jpg/png/webp/gif）、大小（≤2MB），写 MinIO，更新 `User.avatarUrl`

**3. `src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`**

新增 `POST /api/v1/users/avatar`，并在 `/me` 响应中返回 `avatarUrl` 字段。

**4. `src/main/java/com/yizhaoqi/smartpai/config/SecurityConfig.java`**

放行 `/api/v1/users/avatar`（`USER`/`ADMIN`）。

**5. `frontend/src/typings/api.d.ts`**

`UserInfo` 接口加上 `avatarUrl?: string`。

**6. `frontend/src/service/api/auth.ts`**

新增 `fetchUploadAvatar(file)`。

**7. `frontend/src/store/modules/auth/index.ts`**

把 `getUserInfo` 暴露给外部，便于上传成功后刷新用户信息。

**8. `frontend/src/views/personal-center/index.vue`**

把头像换成可点击区域，点击触发隐藏的 `<input type="file">` → 调用 API → 刷新 store。
头像区配套圆形遮罩 + 悬停"更换头像"提示。

**9. `frontend/src/layouts/modules/global-header/components/user-avatar.vue`**

顶栏头像同步显示 `avatarUrl`，没有则继续显示默认图标。

### Bug 8：上传头像后被踢回登录页且头像未更新

**问题现象：** 选完图片"上传"后，页面立刻跳转到登录页（自动登出）；登录回来发现头像还是默认图标。

**根因（两处叠加）：**

1. **后端 403 → 前端登出**：第一版 `/users/avatar` 用 `@RequestAttribute("userId") String userId` 取用户 ID。但 `OrgTagAuthorizationFilter` 只为白名单路径（`/upload/chunk`、`/upload/merge`、`/documents/uploads` 等）写入 `userId` 属性，`/users/avatar` 不在白名单，Spring 直接抛 `MissingRequestAttributeException`。`frontend/src/service/request/index.ts` 的 `onError` 把 `error.response?.status === 403` 视为登录失效，调用 `authStore.resetStore()` → 跳转登录页。
2. **multipart 没有 boundary**：前端在 `auth.ts` 里手动设了 `headers: { 'Content-Type': 'multipart/form-data' }`，axios 不会再自动追加 `; boundary=...`，后端无法解析 multipart body。

**修复：**

- 后端：`uploadAvatar` 改为 `@RequestHeader("Authorization") String token`，自己用 `jwtUtils.extractUserIdFromToken()` 取 ID，与 `/users/me`、`/users/profile` 等其他用户接口的取值方式保持一致。
- 前端：`fetchUploadAvatar` 删除手动 `Content-Type` 头，让 axios 检测到 `FormData` 后自动加上正确的 boundary。

```ts
export function fetchUploadAvatar(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  // 不要手动设 Content-Type：axios 会自动加上正确的 multipart boundary
  return request<{ avatarUrl: string }>({
    url: '/users/avatar',
    method: 'post',
    data: formData
  });
}
```

### UI 调整记录

第一版 UI 用了 `<NUpload>` 组件，但它默认 `display: block`，把整行宽度撑满，导致用户名被挤到卡片最右边并被压成多行；同时 Tailwind 的 `group-hover:opacity-100` 配合方形外层 div，圆形遮罩对不齐头像。

最终调整为：
- 自己控制的 `<input type="file" hidden>` + 包裹 div，固定 72×72、`border-radius: 9999px; overflow: hidden`，遮罩 `position: absolute; inset: 0`，与头像完美贴合
- 用户名/副标题改用内联 CSS 写死 px 字号（项目 UnoCSS 把 `text-12` / `text-16` 当大字号 token，不是 12px / 16px）
- 头像加 2px 白边 + 轻投影
- "修改密码" 按钮放在卡片右侧，"修改"小按钮挂在用户名后面

---

## 新增功能：修改昵称 / 修改密码（含 Bug 9）

### 设计目标

在个人中心提供修改账号（用户名）和修改密码的入口。改昵称时把私人组织标签的显示名也同步更新。

### 关键约束

- **私人标签的 `tagId` 不能改**。它是注册时固化的 `PRIVATE_<注册名>`，被 `users.org_tags`、`users.primary_org`、`file_upload.org_tag` 等多张表当外键引用，一旦改 `tagId`，历史文件全部失主。所以**只改标签的 `name` 字段**，`tagId` 永远保持初始值。
- **改名后必须换 token**。JWT 的 `subject` 是用户名，旧 token 的 subject 是改名前的，下一次请求 `loadUserByUsername` 找不到人会被踢出。后端在改名成功后立即 `invalidateToken(jwt)` + `generateToken(newUsername)` + `generateRefreshToken(newUsername)`，把新 token 返回给前端。
- **改密码不需要换 token**。

### 新增/修改文件

**1. `src/main/java/com/yizhaoqi/smartpai/service/UserService.java`**

新增两个事务方法：

- `updateUsername(currentUsername, newUsername)`：校验非空 / 长度 ≤32 / 未被占用；更新 `users.username`；找到用户的私人标签并更新其 `name`；清掉旧用户名的 Redis 缓存。
- `updatePassword(username, oldPassword, newPassword)`：BCrypt 校验旧密码；新密码长度 ≥6 且不能与旧密码相同；写入加密后的新密码。

**2. `src/main/java/com/yizhaoqi/smartpai/controller/UserController.java`**

- `PUT /api/v1/users/profile` body `{ newUsername }`：调用 `updateUsername`，让旧 token 失效，签发新 token / refreshToken 返回。
- `PUT /api/v1/users/password` body `{ oldPassword, newPassword }`：校验后改密码。
- 新增两个 record：`ProfileUpdateRequest`、`PasswordUpdateRequest`。

**3. `src/main/java/com/yizhaoqi/smartpai/config/SecurityConfig.java`**

放行 `/api/v1/users/profile` 和 `/api/v1/users/password`（`USER`/`ADMIN`）。

**4. `frontend/src/service/api/auth.ts`**

新增 `fetchUpdateUsername`、`fetchUpdatePassword`。

**5. `frontend/src/views/personal-center/index.vue`**

- 用户名旁加"修改"小按钮 → 弹窗修改昵称
- 卡片头部右侧加"修改密码"按钮 → 弹窗（旧密码 / 新密码 / 确认新密码）
- 改昵称成功后：把后端返回的 `token` / `refreshToken` 写回 `localStg`、调用 `authStore.setToken`、再 `getUserInfo()` + `getOrgTags()` 刷新用户信息和标签列表，整个过程用户无感知，不会被踢回登录页

### Bug 9：改回原昵称时私人空间标签名不同步更新

**问题现象：**

- 注册时用户名为 `67677`，私人标签显示 `67677的私人空间` ✅
- 改成 `abc` → 显示 `abc的私人空间` ✅
- 改回 `67677` → **仍然显示 `abc的私人空间`** ❌

**根因：**

第一版 `updateUsername` 的查询逻辑是：

```java
String privateTagId = PRIVATE_TAG_PREFIX + currentUsername;  // 错
organizationTagRepository.findByTagId(privateTagId).ifPresent(...);
```

私人标签的 `tagId` 在注册时固化为 `PRIVATE_67677`，**永远不会变**。但代码用的是当前用户名拼出来的 id：

- 第一次 `67677 → abc`：查 `PRIVATE_67677` ✅ 找到，把 name 改成 `abc的私人空间`，但 tagId 仍是 `PRIVATE_67677`
- 第二次 `abc → 67677`：查 `PRIVATE_abc` ❌ 根本不存在，不更新

**修复：**

不依赖用户名，从 `user.getOrgTags()` 里直接挑出以 `PRIVATE_` 开头的那一条作为稳定的 tagId：

```java
String privateTagId = null;
if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
    for (String tagId : user.getOrgTags().split(",")) {
        if (tagId.startsWith(PRIVATE_TAG_PREFIX)) {
            privateTagId = tagId;
            break;
        }
    }
}
if (privateTagId != null) {
    String finalTagId = privateTagId;
    organizationTagRepository.findByTagId(finalTagId).ifPresent(tag -> {
        tag.setName(trimmed + PRIVATE_ORG_NAME_SUFFIX);
        organizationTagRepository.save(tag);
    });
}
```

无论改多少次、是否改回原名，都能稳定找到自己的私人标签。

### 验证

1. 改昵称 → 标签列表里"XX 的私人空间"立刻同步刷新；不被踢回登录页；下一次请求仍然鉴权通过
2. 改回原昵称 → 标签名也同步改回（验证 Bug 9）
3. 改密码 → 退出后用新密码能登录、用旧密码不能登录
4. 改密码时填错旧密码 → 提示"旧密码不正确"，不会修改成功

---

## 管理员账号登录

服务器上的管理员账号是从 `application-docker.yml` 在启动时由 `AdminUserInitializer` 自动创建的：

```yaml
admin:
  username: admin
  password: admin123
```

直接在前端登录页用 **账号 `admin` / 密码 `admin123`** 登录即可。

> ⚠️ `admin123` 是明文写在配置里的默认密码，公网部署存在风险。**第一次登录后**应立刻使用"修改密码"功能改成强密码。`AdminUserInitializer` 只在用户不存在时创建，已存在不会覆盖密码，所以改完不会被回滚。





  后端
  - Post / PostLike 实体 + 索引（created_at / user_id / school_tag_snapshot / major_tag_snapshot）+ UNIQUE(post_id, user_id)
  - PostRepository / PostLikeRepository + 原子化 incrementLikeCount / decrementLikeCount
  - PostService：CRUD + 点赞 toggle + 分页列表（filter: all/school/major），列表里批量 join users + organization_tags 避免 N+1，返回 { items, total, page, size }，每条包含作者 username / avatar / 学校名+描述 / 学院名 / 专业名 + isOwn + liked
  - 发帖时把学校/学院/专业作快照写入 Post（用户改标签不回刷老帖）
  - PostController：GET/POST /posts、PUT/DELETE /posts/{id}、POST /posts/{id}/like
  - SecurityConfig 放开 /api/v1/posts/** (USER/ADMIN)
  - MAJOR 加入枚举 + User.majorTag 列
  - 新增 GET /users/majors、PUT /users/major 接口
  - seed-majors.csv 35 个常用专业 + 启动自动 seed
  - 批量 CSV 导入支持 MAJOR 行
  - 注销账号时连带清理本人帖子、自己点的赞

  前端
  - service/api/posts.ts：类型 + 所有接口封装
  - views/posts/index.vue：顶栏筛选（全部 / 我的学校 / 同专业；未设置时禁用）+ 发布按钮；帖子卡片显示头像、用户名、学校（tooltip 显示描述）/ 学院 / 专业 tag；自己的帖子可编辑删除；点赞切换；分页
  - 发布/编辑 modal，标题 ≤200、内容 ≤5000，纯文本
  - 个人中心新增「我的专业」卡片 + 修改弹窗
  - 路由 /posts 注册到 imports.ts / routes.ts / transform.ts / elegant-router.d.ts，菜单 icon solar:chat-round-line-line-duotone，中英文名"交流广场 / Community"





  