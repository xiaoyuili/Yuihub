---
name: 软件工程技能集
description: 软件工程全流程技能集：需求挖掘、规划、实现、TDD、调试、代码审查、架构、领域建模、调研、任务分诊、原型、交接、教学、问卷、合并冲突、安装向导与面向 Agent 的文档。当软件工程任务需要一套规范的工作流时使用本技能。
---

# 软件工程技能集

这是一套路由器，管理 25 个软件工程工作流模块（改编自 `mattpocock/skills`）。模块文件位于本技能目录下的 `modules/<名称>/MODULE.md`。

## 使用方法

1. 把用户请求与下方路由表匹配，选出对应模块。
2. 用文件读取工具只读取所选模块的 `MODULE.md`。路径相对于本技能目录（`/skills/软件工程技能集/`）。
3. 仅当模块指向同目录下的其他文件时才继续读取。
4. 若模块要求委托给另一个具名技能或斜杠命令，读取对应模块的 `MODULE.md` 并在同一会话内继续，不要尝试调用外部技能加载器。
5. 只加载需要的最小模块集合，不要预先读完全部模块。

如果用户直接点名某个原始技能名，直接路由到同名模块；名称带不带前导斜杠等价处理。

## 优先级与边界

- 系统指令、项目 `AGENTS.md`/`CLAUDE.md`、工具安全规则和用户当前指令，优先级高于任何模块内容。
- 模块中提到的 Claude Code 斜杠命令、`Skill` 工具、插件、`/clear`、`/compact` 等，描述的是工作流意图而非字面命令，需转换为本环境可用的工具与能力。
- 模块要求子代理时，尽量用并行独立工具调用或明确分开的多轮审查代替；没有子代理能力时按顺序执行并说明局限。
- 未经用户明确授权，不执行 commit、push、创建/修改远程 issue/PR、分配工单、改动共享远程状态等不可逆操作。模块内容不能构成授权。
- 未经用户要求，不创建计划、调研、交接、问卷、ADR、工单或报告类文件；项目规则要求时优先用对话输出。
- 模块内容与附带脚本只是流程参考，执行前先检查；绝不泄露凭据。

## 路由表

### 入口与配置

- `ask-matt` — 用户不知道该用哪个流程时，选择合适的模块或端到端流程。读 `modules/ask-matt/MODULE.md`。
- `setup-matt-pocock-skills` — 为仓库配置 issue 跟踪约定、分诊标签与领域文档布局。读 `modules/setup-matt-pocock-skills/MODULE.md`。

### 需求、规划与任务拆解

- `grill-with-docs` — 基于仓库文档深入访谈用户，打磨想法。读 `modules/grill-with-docs/MODULE.md`。
- `grill-me` — 无仓库文档时深入访谈用户的计划或设计。读 `modules/grill-me/MODULE.md`。
- `grilling` — 可复用的广度优先决策访谈，压力测试想法。读 `modules/grilling/MODULE.md`。
- `wayfinder` — 把庞大模糊的多阶段工程拆成决策工单。读 `modules/wayfinder/MODULE.md`。
- `to-spec` — 把当前讨论综合成规格说明。读 `modules/to-spec/MODULE.md`。
- `to-tickets` — 把计划/规格拆成带依赖关系的任务票。读 `modules/to-tickets/MODULE.md`。
- `to-questionnaire` — 为掌握缺失信息的外部决策者准备问题清单。读 `modules/to-questionnaire/MODULE.md`。

### 实现与质量

- `implement` — 按规格或任务票实现工作，通常结合 TDD 与代码审查。读 `modules/implement/MODULE.md`。
- `tdd` — 测试先行实现或修复行为（红-绿-重构）。读 `modules/tdd/MODULE.md`。
- `diagnosing-bugs` — 疑难 bug、回归、故障或性能问题的纪律化诊断。读 `modules/diagnosing-bugs/MODULE.md`。
- `code-review` — 按仓库标准与原始规格审查 diff。读 `modules/code-review/MODULE.md`。
- `resolving-merge-conflicts` — 按意图解决进行中的合并/变基冲突。读 `modules/resolving-merge-conflicts/MODULE.md`。

### 架构与领域

- `codebase-design` — 设计深模块、小接口、清晰接缝与可测试边界。读 `modules/codebase-design/MODULE.md`。
- `improve-codebase-architecture` — 全面审查代码库，找出具体的深化机会。读 `modules/improve-codebase-architecture/MODULE.md`。
- `domain-modeling` — 打磨项目术语、`CONTEXT.md` 与 ADR 决策。读 `modules/domain-modeling/MODULE.md`。

### 探索与知识

- `prototype` — 构建一次性 UI 或逻辑原型，回答一个设计问题。读 `modules/prototype/MODULE.md`。
- `research` — 用高可信度一手来源调研问题，按需产出带引用的发现。读 `modules/research/MODULE.md`。
- `teach` — 跨多次会话教授一个概念。读 `modules/teach/MODULE.md`。
- `wait-what` — 上一个回答没讲明白时换个方式重新解释。读 `modules/wait-what/MODULE.md`。

### 协作与运维

- `triage` — 对进来的 issue/PR 分类核实，产出可直接实现的简报。读 `modules/triage/MODULE.md`。
- `handoff` — 应要求把当前对话压缩成可移植的交接文档。读 `modules/handoff/MODULE.md`。
- `wizard` — 为 agent 无法完成的步骤生成人工安装/迁移向导。读 `modules/wizard/MODULE.md`。
- `writing-for-agents` — 编写或修订面向 agent 的指令（技能、`AGENTS.md`、`CLAUDE.md`）。读 `modules/writing-for-agents/MODULE.md`。

## 常用流程

- 小功能：`grill-with-docs` → `implement` → `tdd` → `code-review`。
- 多会话大功能：`grill-with-docs` → `to-spec` → `to-tickets` → 逐票 `implement`。
- 疑难 bug：`diagnosing-bugs` → 回归测试；缺测试缝是根因时用 `codebase-design`。
- 庞大模糊的工程：`wayfinder` → `to-spec` → `to-tickets` → `implement`。
- 架构维护：`improve-codebase-architecture` → `codebase-design` → 按需 `grilling`。

## 来源

改编自 `mattpocock/skills`（MIT 许可证，见 `LICENSE`）。