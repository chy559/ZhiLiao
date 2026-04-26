## 近期功能更新

### 交流广场

#### 后端

- 新增 `Post` / `PostLike` 实体、索引与点赞唯一约束，支持帖子发布、编辑、删除、点赞切换。
- `PostRepository` / `PostLikeRepository` 提供点赞计数原子增减与列表查询能力。
- `PostService` 提供 CRUD、点赞 toggle、分页列表查询，支持 `all / school / major` 筛选。
- 列表查询批量关联 `users` 与 `organization_tags`，避免 N+1，统一返回 `{ items, total, page, size }`。
- 每条帖子返回作者 `username / avatar / 学校名+描述 / 学院名 / 专业名 / isOwn / liked`。
- 发帖时写入学校 / 学院 / 专业快照字段；帖子展示阶段优先取当前作者信息，作者不存在时回退快照。
- `PostController` 提供 `GET/POST /posts`、`PUT/DELETE /posts/{id}`、`POST /posts/{id}/like`。
- `SecurityConfig` 已放开 `/api/v1/posts/**` 给 `USER / ADMIN`。
- `MAJOR` 已加入组织标签枚举，`User` 已支持 `majorTag`。
- 已新增 `GET /users/majors`、`PUT /users/major`。
- 已支持 `seed-majors.csv` 常用专业启动自动导入，CSV 批量导入也支持 `MAJOR`。
- 注销账号时会连带清理本人帖子与自己点过的赞。

#### 前端

- 新增 `frontend/src/service/api/posts.ts`，封装帖子相关类型与接口。
- `views/posts/index.vue` 已实现帖子列表、分页、点赞、编辑、删除、发布。
- 顶部筛选已改成更贴合页面风格的工具栏：
  - 左侧使用 `筛选` 下拉，包含 `我的学校`、`同专业`、`取消筛选`
  - 右侧放置 `我的帖子` 与 `发布新帖`
- 发帖 / 编辑弹窗限制：
  - 标题 `<= 200`
  - 内容 `<= 5000`
  - 纯文本
- 个人中心已新增“我的专业”卡片与修改弹窗。
- 路由 `/posts` 已注册到 `imports.ts / routes.ts / transform.ts / elegant-router.d.ts`。
- 左侧菜单中的“交流广场”入口已移除，改为全局悬浮按钮进入。

#### 交流广场 UI 补充调整

- 左侧栏不再直接展示“交流广场”菜单项。
- 基础布局新增右下角悬浮入口按钮，可点击进入交流广场。
- 悬浮按钮支持拖拽、位置记忆、同一次登录内保留位置；重新登录后重置到右下角。
- 顶部筛选工具栏已完成浅色 / 深色主题适配。
- 帖子列表、帖子详情弹窗、个人中心学校 / 学院 / 专业区域已补齐深色模式文字与背景样式。

### 知识库标签与权限语义调整

#### 标签展示与上传选择

- 用户标签展示已做可见性收敛：
  - 管理员用户列表只展示私人空间、学校、学院、专业标签
  - 上传文件时的标签下拉只允许选择私人空间、学校、学院，不再展示专业标签
- `/users/org-tags` 保留给个人中心等“展示用途”接口。
- `/users/upload-orgs` 专门返回上传可选标签。

#### 文件可见范围语义

- 旧的“公开 / 私有”语义已调整为：
  - `组织内公开`
  - `仅自己可见`
- 当前规则：
  - 选学校 + `组织内公开`：学校及其下属学院范围可见
  - 选学院 + `组织内公开`：该学院及其下级范围可见，其他学院不可见
  - 选任意标签 + `仅自己可见`：仅上传者本人可见，且标签会强制绑定到私人空间
- 后端上传接口已增加硬校验：
  - `仅自己可见` 时只能使用私人空间标签
  - `组织内公开` 时不能选择私人空间标签
  - 上传可选标签中不允许使用专业标签

#### 知识库列表与访问

- 知识库首页列表已从“仅本人上传文件”调整为“当前用户可访问的文件”：
  - 自己上传的文件
  - 组织内公开且当前用户有权限访问的文件
- 前端列表中，非本人文件不显示删除按钮。
- 文件预览 / 下载 / 预览 URL 访问已要求登录后再进行组织权限判断。

### 用户资料与关联数据同步

- 修改学校 / 学院 / 专业后：
  - 个人中心信息会立即更新
  - 交流广场帖子展示会使用最新作者信息
- 注销账号时：
  - 会删除本人帖子
  - 会删除自己点赞记录
  - 私人标签也会一并清理

## 待解决问题

（已全部解决）

### 已修复

1. 管理员用户列表标签展示收敛：只取用户当前登记的 `schoolTag / collegeTag / majorTag` 以及私人空间标签，不再回退到 `orgTags` 原始串，避免把同一学校下的其他下级标签混进来。
2. 用户修改学校 / 学院时，自动迁移”组织内公开”的历史资料：
   - 原来公开到学校的文件，`orgTag` 切换到新学校，继续保持学校级公开
   - 原来公开到学院的文件，`orgTag` 切换到新学院，继续保持学院级公开
   - 若用户清空学校 / 学院，对应文件会被降级为”仅自己可见”并改绑到私人标签，防止旧组织成员继续访问
   - `FileUpload` 与 Elasticsearch 索引同步更新，旧组织用户立刻失去访问权限
3. 普通用户切换账号或改学校 / 学院后，知识库列表无需刷新浏览器，会自动按最新用户与组织拉取：
   - 切账号时 `tasks` 先清空再重建，避免残留上一账号的文件
   - `schoolTag / collegeTag` 变动会触发列表重拉
   - 远端返回的 `orgTagName / 可见范围` 会整条覆盖本地，不再只改 `status`
4. 知识库列表组织标签展示时，学院会自动拼上所属学校，例如 `中南大学湘雅医学院`，一眼看出归属。
5. 普通用户上传简化为 `公开 / 私有` 二选一：
   - `公开`：后端按用户当前学院（无学院则按学校）自动绑定组织标签，学院 / 学校范围可见
   - `私有`：绑定到私人空间，仅上传者可见
   - 管理员仍保留跨学校的级联选择，可指定任意学校 / 学院标签
6. 聊天页新增"新建会话"入口：
   - 新增 `POST /api/v1/users/conversation/new`，会清理 `user:{id}:current_conversation` 指针以及对应的 `conversation:{uuid}` 历史
   - 下一次提问时 `ChatHandler` 自然生成新的 `conversationId`，`history` 为空，不会再被已删文件的旧回答污染
   - 前端聊天页 Hero 区右侧加"新建会话"按钮，带二次确认；流式回复进行中会提示先等响应结束
7. 已上传文件支持切换可见范围：
   - 新增 `PATCH /api/v1/documents/{fileMd5}/visibility`，接受 `{ isPublic: boolean }`
   - 权限：仅文件所有者或管理员可切换
   - 公开：按所有者的学院 / 学校自动绑定；所有者未设置学校 / 学院时直接拒绝
   - 私有：绑回所有者的私人空间标签
   - 会同步更新 `FileUpload`、`document_vectors`、Elasticsearch 索引
   - 前端在知识库列表 `操作` 列新增 `设为公开 / 设为私有` 按钮（仅当前用户自己的已完成文件展示）
8. 服务器端知识库检索报 `the backend request error`，本地可正常检索：
   - 根因：前端部分接口写死了 `baseURL: '/proxy-api'`，只在本地 Vite 代理下生效，生产 Nginx 未配置 `/proxy-api` 导致 404
   - 修复：移除 5 处 `baseURL: '/proxy-api'` 覆盖，统一走默认 `VITE_SERVICE_BASE_URL`（生产 `/api/v1`）
     - `frontend/src/views/knowledge-base/modules/search-dialog.vue` —「知识库检索」
     - `frontend/src/views/chat/modules/chat-message.vue` 3 处 —「点引用查 MD5 / 按 MD5 下载 / 按文件名下载」
     - `frontend/src/views/chat/modules/input-box.vue` —「发送中断时获取 websocket token」
9. 知识库检索返回 0 条且后端抛 `UnrecognizedPropertyException: Unrecognized field "isPublic"` / `"public"`：
   - 根因：`EsDocument.isPublic` 通过 Lombok `@Data` 生成 `isPublic()` getter，Jackson 按 Bean 规则把 JSON key 推断为 `public`（脱掉 `is` 前缀）；而 `ElasticsearchService.updateOrgTagByFileMd5` 的 painless 脚本写的是 `ctx._source.isPublic`，两种 key 在同一索引中混存，Jackson 反序列化任一种都会失败
   - 修复（`src/main/java/com/yizhaoqi/smartpai/entity/EsDocument.java`）：
     - 在 `isPublic` 字段加 `@JsonProperty("isPublic")`，强制序列化 / 反序列化都使用 `isPublic`
     - 类级加 `@JsonIgnoreProperties(ignoreUnknown = true)`，兜底任何历史遗留字段（如老数据的 `public`），即使 ES 里存在未知字段也不会再导致整次检索失败
   - 配套一次性数据清理脚本（可选，不影响功能，只是为了数据整洁）：
     ```bash
     curl -u elastic:PaiSmart2025 -XPOST "http://localhost:9200/knowledge_base/_update_by_query?refresh=true&conflicts=proceed" \
       -H 'Content-Type: application/json' -d @/tmp/mig.json
     # /tmp/mig.json 内容：
     # {"script":{"lang":"painless","source":"if (ctx._source.containsKey(\"public\")) { if (!ctx._source.containsKey(\"isPublic\")) { ctx._source.isPublic = ctx._source.public; } ctx._source.remove(\"public\"); }"},"query":{"exists":{"field":"public"}}}
     ```
   - 部署注意事项（顺序很重要）：
     - 先更新后端代码并部署（`mvn clean package -DskipTests` + 重建镜像）
     - 再跑 ES 数据清理脚本（如需要）
     - 避免“老代码 + 新数据”或“新代码 + 老数据”中间态再次导致空结果
10. 聊天暂停按钮按下后仍会继续输出一段时间才停：
    - 根因：旧实现只在 `ChatHandler.stopResponse` 里翻一个 `stopFlags` 位，`sendResponseChunk` 看到置位就不再向前端推 chunk；但上游 `deepSeekClient.streamResponse` 返回 `void`，`.subscribe(...)` 产生的 `Disposable` 直接被丢弃，**DeepSeek 的订阅从未被取消**，token 还在继续生成（持续烧费用），并且在取消前端推送前已累积到浏览器 WebSocket 接收缓冲的 chunk 也会被逐条 render 出来
    - 修复：
      - `client/DeepSeekClient.java`：`streamResponse` 返回 `reactor.core.Disposable`，把 `.subscribe(...)` 返回值透传给调用方
      - `service/ChatHandler.java`：新增 `Map<String, Disposable> activeStreams`，下行流开始时按 `sessionId` 存订阅；`stopResponse` 从 map 取出并调用 `subscription.dispose()` 真正切断上游；所有正常完成 / 错误 / 强制结束 / 外层异常路径都补了 `activeStreams.remove(sessionId)`，避免内存泄漏
    - 效果：点暂停后 DeepSeek 上游立即被取消，token 不再继续产生；前端仍可能看到少量已在 WebSocket 缓冲中的 chunk，但不会再把整条回答流式播完
11. 知识库文件删除未同步清理 ES，历史遗留孤儿 chunk：
    - 根因：`DocumentService.deleteDocument` 里 ES 删除被包在 `try/catch (Exception e) { logger.error(...); }` 里，一旦 ES 调用失败就被吞掉，后续的 MinIO、`document_vectors`、`file_upload` 删除继续进行，导致 DB 已无记录但 ES 里仍有该文件的全部 chunk（理论上会让同组织用户通过检索看到"已删除"文件的内容）
    - 修复：
      - `service/DocumentService.java`：去掉 ES 删除周围的 try/catch，让异常向外抛，由 `@Transactional` 回滚，**ES 删不干净就不允许整体删除成功**，杜绝新的孤儿产生
      - `service/ElasticsearchService.java`：新增 `deleteOrphansNotIn(Collection<String> validMd5s)`，用 delete_by_query + `must_not terms` 一次性删除 `fileMd5` 不在白名单里的全部文档
      - `repository/FileUploadRepository.java`：新增 `findAllDistinctFileMd5()`，提供 DB 中现存的全部 fileMd5 作为白名单
      - `controller/AdminController.java`：新增 `POST /api/v1/admin/es/cleanup-orphans`（管理员权限），调用上面两者一键清理历史孤儿，返回被删除的 chunk 条数
    - 运维说明：
      - 新产生的删除行为不会再留孤儿
      - 若 ES 暂时不可用，用户发起的删除会直接失败（而不是默默留孤儿），恢复后重试即可
    - 清理历史孤儿的操作步骤（幂等，每台环境执行一次即可）：

      步骤 1：拿管理员 token

      ```bash
      curl -s -X POST http://localhost:8081/api/v1/users/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin123"}'
      ```

      响应 `{"code":200,...,"data":{"token":"eyJhbGc..."}}`。

      步骤 2：用该 token 调清理接口

      ```bash
      curl -X POST http://localhost:8081/api/v1/admin/es/cleanup-orphans \
        -H "Authorization: Bearer <步骤 1 拿到的 token>"
      ```

      响应 `{"code":200,"message":"清理完成","data":{"deleted":N,"validMd5Count":M}}`。

      一条命令一把梭（有 `jq`）：

      ```bash
      TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/users/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin123"}' | jq -r '.data.token') && \
      curl -X POST http://localhost:8081/api/v1/admin/es/cleanup-orphans \
        -H "Authorization: Bearer $TOKEN"
      ```

      没 `jq` 就用 `grep`：

      ```bash
      TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/users/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin123"}' \
        | grep -o '"token":"[^"]*' | cut -d'"' -f4) && \
      curl -X POST http://localhost:8081/api/v1/admin/es/cleanup-orphans \
        -H "Authorization: Bearer $TOKEN"
      ```

      服务器环境把 `localhost:8081` 换成服务器实际后端地址（或通过 Nginx 暴露的 `/api/v1/...`）即可。

      步骤 3：验证清理结果（可选）

      ```bash
      curl -s -u elastic:PaiSmart2025 "http://localhost:9200/knowledge_base/_search" \
        -H 'Content-Type: application/json' \
        -d '{"size":0,"aggs":{"md5s":{"terms":{"field":"fileMd5","size":20}}}}'
      ```

      返回 `aggregations.md5s.buckets` 里的 `key` 应与 `file_upload.file_md5` 一致；若仍有多余 MD5，说明存在新的删除失败路径，结合后端日志关键字 `ADMIN_CLEANUP_ES_ORPHANS` 排查。\

    ● 全部改造完成。以下是本次改动汇总：  


✅ 执行清单

🎨 视觉资源

- src/assets/svg-icon/logo.svg — 打开的书+气泡从中升起（currentColor 自动接主色）
- public/favicon.svg + dist/favicon.svg — 墨绿底白色书本气泡的圆角方形 favicon
- src/assets/imgs/login-illustration.svg（新增）— 极淡书页+墨点水印，14MB PNG → 1.5KB SVG

🎛️ 全局样式系统

- src/theme/settings.ts — 主色 #2563eb → #065F46；layout 底色 → 米白 rgb(250, 247, 240)；info/success/warning/error 同步调和
- uno.config.ts — card-wrapper 改成细边框+微阴影（Linear 风）
- src/views/\_builtin/login/index.vue — 整个登录页样式从蓝色 glassmorphism 改写为墨绿 Linear 风（border-radius: 12px、1px 细边框、去渐变、墨绿按钮/输入框焦点环）
- 批量替换 8 个视图文件的硬编码蓝色 → 墨绿体系：chat/index.vue、chat-list.vue、chat-message.vue、input-box.vue、posts/index.vue、knowledge-base/index.vue、floating-post-entry/index.vue、theme-drawer/theme-color.vue  


🏷️ 元数据

- package.json — name: pi-smart → kaofu-zhiliao；description/author 去 PaiSmart
- .env — VITE*STORAGE_PREFIX=PaiSmart* → KaofuZhiliao\_  


⚠️ 需要你手动处理的 2 件事

1. 删掉 14MB 的废弃文件：src/assets/imgs/login-illustration.png 已经没有任何引用（我只删了 import，没删文件本身，怕你还想保留）。可以直接 rm。
2. 部署提醒：storage prefix 改了，所有已登录用户的 token 会失效，需要重新登录一次。  


🔒 已遵守的边界

- 未改任何后端 Java 文件、未改 API 路径、未改 DOM 结构
- 未动 src/service/、src/store/（除 theme）、src/router/、src/hooks/、src/utils/
- 本地运行：cd frontend && pnpm dev 即可看效果；prod 构建需要跑一次 pnpm build 让 dist/index.html 和 manifest 同步  


Recalled 1 memory, wrote 2 memories (ctrl+o to expand)

● 记忆已存档（后续改 UI 会自动遵守新规范）。你现在可以 cd PaiSmart-main/frontend && pnpm dev 看效果；如果登录页细节还想调整（比如 Hero 区留白、字号、按钮形状），告诉我就继续迭代。
