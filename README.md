<div align="center">
  <h1>YuiHub</h1>

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度二改版：面向真实使用的 AI 助手 Android 客户端，重点强化了长对话的上下文管理、提示词缓存效率与助手自进化能力。

</div>

## 📥 下载

前往 [Releases](https://github.com/xiaoyuili/Yuihub/releases) 下载 `arm64-v8a` APK。

## 🆚 与原版 RikkaHub 的区别

### 新增

- **自动上下文压缩（harness 式）**：上下文占用达到窗口 80% 时自动触发，把早期历史压缩成一条检查点摘要（保留尾部 16% 原文），对话流中以「上下文压缩」流程行可见；再次压缩时与旧摘要合并，不会堆积。长对话不再爆窗口。
- **助手自进化**（默认关闭，按助手独立开关）：每轮回复后自动提取三类可复用方法——代码开发（排障步骤）、日常聊天（语气/风格纠正）、角色扮演（人设/边界纠正），存入独立数据库表。同类方法积累后**自动整理**：把解决同一问题的多条合并成一条更通用的方法并删除被吸收的旧条目，避免无限堆叠。学到的方法在后续对话中自动注入，也可在页面手动增删改。
- **子智能体（spawn_agent）**：主 agent 可把独立子任务派发给空会话的子 agent（继承工作区/模型/工具，禁止嵌套派发），父对话只收最终结果。
- **提示词缓存优化**：动态内容（记忆、学到的方法）注入到会话尾部而非 system prompt，保持稳定前缀；OpenAI 官方端点自动携带 `prompt_cache_key` 按会话路由缓存。多轮 agent 任务的缓存命中率显著提升。
- **Token 统计账本**：按消息 id 幂等记账，删除对话不影响历史累计；统计页含缓存率、模型调用次数与近 7 天逐日消耗图。
- **工具行默认收起**：工具调用的命令与输出默认折叠，点击才展开，长对话更清爽。
- **Agent 输出风格约束**：工具轮次不输出面向用户的旁白，完整回答只在最终轮，对话结构对齐 思考→工具→最终输出。

### 更改

- **记忆检索按助手隔离**：`conversation_search` 只搜当前助手自己的历史对话，助手之间不再互相看到聊天记录（全局记忆仍是跨助手共享）。
- **记忆页重构**：顶部明确显示「记忆存储范围」（仅本助手 / 全局共享），切换即切换列表，不再与其它开关混在一起。
- **用户气泡样式**：胶囊形改为 12dp 圆角矩形，颜色改用贴近背景的 `surfaceContainerHighest`。
- **统计页视觉**：卡片标题去掉了彩色方块底，只留图标。
- **压缩摘要注入方式**：从顶部摘要卡片改为检查点消息，对 KV cache 更友好。

### 删减

- **请求日志**（HTTP 请求记录页面）：普通用户用不上，整体移除。
- **模型排行榜**（Leaderboard）相关代码。
- 悬浮窗组件、ViewText 等未使用的死代码。
- 英文/繁体 README（仅保留简体中文）。

### 备份

- 「聊天记录」备份包含自进化方法、记忆、token 账本（同一 Room 数据库）。
- 「文件」备份新增工具输出物（`tool_outputs`）目录。

## ✨ 原版功能（保留）

- 🎨 Material You 设计、暗色模式、预测性返回
- 📦 工作区：基于 proot 的 Linux 智能体环境
- 🛠️ MCP 支持
- 🔄 多供应商支持（OpenAI、Google、Anthropic），自定义 API / URL / 模型
- 🖼️ 多模态输入
- 📝 Markdown 渲染（代码高亮、数学公式、表格、Mermaid）
- 🔍 联网搜索（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤖 助手自定义、类 ChatGPT 记忆功能
- 🌐 自定义 HTTP 请求头和请求体

## 🛠️ 构建

需要 Android Studio 或 Android SDK（compileSdk 37）。构建前在 `app` 目录放入 `google-services.json`。

```bash
./gradlew :app:assembleRelease
```

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) 开源，继承自原版 RikkaHub。