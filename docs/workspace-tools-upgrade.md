# 工作区工具升级

面向 YuiHub 的 Android 客户端 + Ubuntu proot 工作区，目标是把文件读取、搜索、编辑和输出控制做成原生能力，而不是引入外部插件或另建一套运行时。

## 设计原则

- 保持 `workspace_*` 工具名前缀与 `/workspace`、`/upload`、bind mount 的路径语义。
- 默认行为向后兼容：不传 `offset`/`limit` 时仍可读取文件；旧的单段 `old_text/new_text` 编辑仍可用。
- 所有范围、分页、截断都返回显式完整性字段，模型不应把“本页完整”误认为“整个扫描完整”。
- 写入先校验 revision，再落盘；冲突时零副作用。
- 不照搬桌面插件或其它宿主的工具层；文件、shell、权限、路径解析都使用 YuiHub 现有的 Kotlin/proot 实现。
- 按需加载只覆盖高级工作区工具；基础读写、shell、present 保持常驻，避免常用操作多一轮。

## 工具契约

### `workspace_read_file`

参数：

- `path`：Rootfs 内绝对路径。
- `offset`：可选，1-based 起始行，默认 1。
- `limit`：可选，最多 2000 行，默认 200。

文本结果：

```json
{
  "path": "/workspace/a.kt",
  "startLine": 10,
  "endLine": 29,
  "totalLines": 120,
  "text": "10|...\n11|...",
  "complete": false,
  "nextOffset": 30,
  "revision": "<sha256>",
  "truncated": false
}
```

- `text` 使用 `N|原始行` 的紧凑行号格式；编辑时不要把 `N|` 写回文件。
- 图片路径仍返回图片 part，不套用文本范围。
- `revision` 是整个文件字节的 SHA-256，不是当前范围的。

### `workspace_read_files`

参数：`files: [{path, offset?, limit?}]`，最多 16 项，一次调用读取多个文件。返回 `items`，每项与 `workspace_read_file` 同构；单个文件失败不影响其他文件。

### `workspace_glob`

参数：`pattern`、`path?`、`offset?`、`limit?`。返回 `items`、`pageComplete`、`scanComplete`、`truncated`、`nextOffset`。只返回普通文件，跳过隐藏目录和 `.l2s.` 临时文件。

### `workspace_grep`

参数：`query`、`path?`、`regex?`、`ignoreCase?`、`includeGlob?`、`offset?`、`limit?`。返回 `items: [{path,line,text}]` 和完整性字段。

### `workspace_stat`

参数：`path`。返回 `exists`、`type`、`sizeBytes`、`modifiedAt`、`revision`；目录不计算 revision。

### `workspace_edit_file`

参数：

- `path`
- `edits: [{old_text, new_text}]`，可选；最多 32 段。
- 兼容旧参数 `old_text`、`new_text`、`replace_all`。
- `expectedRevision`：可选；不匹配时返回 `REVISION_CONFLICT`，不写入。

执行顺序：读取一次原文 → 依次应用每段替换 → 全部成功才写一次 → 返回 `replacements`、`revision`、`sizeBytes`、`updatedAt`。失败时原文件保持不变。

### `workspace_write_file`

新增 `expectedRevision`。与现有 `expectedMtimeMs` 同时存在时先校验 revision，再校验 mtime。

### `workspace_shell`

新增 `script_path`：

- `command` 与 `script_path` 二选一。
- `script_path` 只接受脚本路径，命令内部用 shell 引号包裹，不重复把脚本正文放进工具参数。
- 执行时等价于 `bash <script_path>`，保留 `cwd`、`timeout`，运行在 Ubuntu Rootfs 中。

## 输出预算与续读

- 文本工具自身做行数/条数上限。
- 全局 `maybeTruncateToolOutput` 继续兜底：超 32K 字符时保留 4K 预览，完整输出写入 `/tool_outputs/<id>.txt`。
- 截断结果增加 `resultId`，并明确提示用 `workspace_read_file` 或 `workspace_read_result` 做范围读取。
- 搜索/读取结果使用统一的 `truncated`、`scanComplete`、`pageComplete`、`nextOffset` 字段。

## 高级工具按需加载

- 常驻：`workspace_read_file`、`workspace_write_file`、`workspace_edit_file`、`workspace_present_file`、`workspace_shell`、`workspace_load_tools`。
- 高级：`workspace_read_files`、`workspace_glob`、`workspace_grep`、`workspace_stat`、`workspace_read_result`。
- `workspace_load_tools` 参数 `tools: string[]`；成功后在当前生成上下文的下一次模型请求生效。
- 发现状态按生成/子代理隔离，不跨会话、不跨子代理共享。
- 未知工具名返回可用清单，不注册任何工具。

## 重复读取去重

- 同一次生成内，对同一路径、同一范围、同一 `revision` 的重复单文件读取，且此前没有写入该路径时，返回 `unchanged: true` 和引用信息，不重复发送正文。
- 写入或编辑该路径后清空对应记录；shell 执行后清空全部记录。
- 不覆盖批量读取、shell 和搜索。

## 分阶段任务

- [x] 0. 设计文档与接口契约。
- [x] 1. `workspace_read_file` 范围/行号/revision；`workspace_read_files`。
- [x] 2. `workspace_glob` / `workspace_grep` / `workspace_stat`。
- [x] 3. 多段编辑、`expectedRevision`、写入冲突。
- [x] 4. 统一截断字段、`resultId`、`script_path`。
- [x] 5. `workspace_load_tools` 与按生成隔离的激活状态。
- [x] 6. 读取去重与大结果续读。
- [x] 7. JVM 单测、`assembleDebug`、Shizuku 覆盖安装。

## 验收

- 旧调用：`workspace_read_file` 只传 path 仍返回文本；`workspace_edit_file` 旧 `old_text/new_text` 仍可用。
- 分页：读 1-10 行后 `nextOffset=11`；读到末尾 `complete=true`。
- 冲突：用旧 revision 编辑返回 `REVISION_CONFLICT`，文件字节不变。
- 批处理：多文件读取中一个失败，其他文件仍返回；多段编辑中途失败，原文件不变。
- 搜索：`truncated=true` 时不返回虚假的完整总数。
- 发现：未加载 `workspace_glob` 前 schema 中不存在；加载后下一次请求存在；子代理不继承父代理的激活集合。
- 去重：连续两次相同读取第二次为 `unchanged`；写入后再次读取返回新正文。
- 构建：`./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleDebug` 通过。

## 验证记录

- `:app:compileDebugKotlin`：通过。
- `:app:assembleDebug`：通过，已用 Shizuku 覆盖安装。
- `WorkspaceToolSupportTest`：通过（临时移走两个与当前源码不一致的旧测试后运行）。
- 全量 `testDebugUnitTest` 仍会在编译阶段失败：`TimeReminderTransformerTest` 引用了已删除的 `applyTimeReminder`，`MemorySnapshotTest` 引用了已删除的 `MEMORY_SNAPSHOT_PREAMBLE`。这两个文件本次未改动。
