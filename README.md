<div align="center">
  <h1>YuiHub</h1>

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度二改版：面向真实使用的 AI 助手 Android 客户端，重点强化了长对话的上下文管理、提示词缓存效率与助手自进化能力。

</div>

## 📥 下载

前往 [Releases](https://github.com/xiaoyuili/Yuihub/releases) 下载 `arm64-v8a` APK。

## 🆚 与原版 RikkaHub 的区别

### 新增

- **品牌重塑**：YuiHub / `me.yui.yuihub`
- **视觉模型工具 vision_analyze**
- **自动上下文压缩**（harness 式检查点）
- **助手自进化**（方法提取 + 自动整理）
- **记忆系统重构**（embedding 语义检索）
- **子智能体 spawn_agent**
- **提示词缓存优化**
- **Token 统计账本**
- **工作区内置 Ubuntu 镜像与国内镜像测速**
- **宿主目录挂载**
- **权限管理与后台保活**
- **模型自助工具**（manage_skill / manage_mcp_server）
- **工具行默认收起**
- **Agent 输出风格约束**

### 更改

- **供应商预置精简**（仅 DeepSeek）
- **消息渲染 Harness 风格**
- **统计页重做**
- **MCP 入口移至扩展页**
- **记忆检索按助手隔离**
- **记忆页重构**
- **用户气泡样式调整**
- **压缩摘要改为检查点消息**
- **滚动时输入框自动淡出**

### 删减

- **语音功能**
- **翻译、OCR、消息建议**
- **快捷消息**
- **内置 Web 服务器**
- **WebDAV / S3 备份与存储管理页**
- **从其他 App 导入**
- **请求日志**
- **模型排行榜**
- 死代码、英文/繁体 README

### 备份

- 本地备份 + 备份提醒，备份项含「设置」
- 恢复聊天记录备份后需重启应用生效

### 稳定性修复

- 备份恢复路径逃逸与热库覆盖
- vision_analyze 宿主文件读取限制
- 记忆/自进化提取竞态与作用域校验
- 自进化整理误删
- 重新生成/工具审批丢更新
- 工作区 rootfs 覆盖安装可回滚
- rootfs 配置补丁写入串行化

## 🛠️ 构建

需要 Android Studio 或 Android SDK（compileSdk 37）。构建前在 `app` 目录放入 `google-services.json`。

```bash
./gradlew :app:assembleRelease
```

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) 开源，继承自原版 RikkaHub。
