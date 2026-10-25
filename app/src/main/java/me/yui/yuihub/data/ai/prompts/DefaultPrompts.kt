package me.yui.yuihub.data.ai.prompts

import me.yui.yuihub.data.datastore.Settings

enum class PromptId {
    TITLE,
    COMPRESS,
    COMPACTION_CHECKPOINT,
    MEMORY_EXTRACT,
    MEMORY_CONSOLIDATE,
    MEMORY_SNAPSHOT,
    WORKSPACE,
    CHILD_AGENT,
    SKILLS,
    SCHEDULED_TASK,
    VISION,
    AGENT_TOOL_STYLE,
}

fun Settings.isPromptEnabled(id: PromptId): Boolean = id.name !in disabledPromptIds

fun Settings.isPlainChatMode(): Boolean = PromptId.entries.all { it.name in disabledPromptIds }

internal val DEFAULT_MEMORY_EXTRACT_PROMPT = """You maintain durable memory for one AI assistant. Update the store only when the recent conversation contains facts worth reusing.

Today is {date}.

<existing memories>
{existing_memories}
</existing memories>

<recent conversation>
{recent_conversation}
</recent conversation>

Kinds:
- core: stable identity/user profile, durable rules, or AI self-definition. section must be user/rules/ai.
- project: workspace/project overview, architecture, decisions, deployment, todo.
- fact: one atomic durable fact (short, one sentence).
- lesson: correction, mistake, or hard-won experience. Set corrected=true when the user corrected you.
- topic: an ongoing thread with cause, progress, and goal.

Rules:
- Use explicit evidence from the conversation; do not infer or speculate.
- Keep only durable facts useful across future conversations. Exclude one-off task state, chit-chat, assistant claims, tool logs, and sensitive data.
- One fact per memory; 1-2 concise sentences. Write in the user's main language. Keep user wording for design decisions.
- keywords: 5-10 words the user would plausibly say when this memory should be recalled. Avoid project names unless essential.
- weight 1-4: 4 safety/red line, 3 user-emphasized/global rule, 2 ordinary preference or project decision, 1 minor note.
- status active by default. Do not archive unless the conversation clearly proves a memory wrong.
- Prefer update over create when an existing memory supersedes the new fact. Merge duplicates into one memory.

Operations (max 8):
- create: new fact with no equivalent.
- update: supersedes/contradicts an existing memory (include id).
- delete: existing memory is false or obsolete (include id).

Respond with ONLY this JSON (no markdown):
{"operations":[{"action":"create","title":"...","content":"...","kind":"fact","section":"","category":"coding","keywords":["..."],"weight":2,"status":"active","corrected":false},{"action":"update","id":7,"content":"...","keywords":["..."],"weight":3}]}
If nothing is worth remembering: {"operations":[]}""".trimIndent()

internal val DEFAULT_MEMORY_CONSOLIDATE_PROMPT = """Consolidate the memory store below. Merge only true overlaps or duplicates into one concise memory that keeps every distinct fact. Fix titles, kinds, categories and keywords when useful; a single memory may be rewritten without merging.

Rules:
- Do not merge unrelated memories.
- List every consumed source id.
- Preserve distinct facts; write in the source language; do not invent.
- Archive memories that are obsolete, contradicted, superseded, or clearly no longer useful. Stale means finished, archived means invalid.

<memories>
{memories}
</memories>

Respond with ONLY this JSON:
{"merged":[{"title":"...","content":"...","kind":"fact","category":"coding","weight":2,"keywords":["..."],"sources":[1,2]}],"archived":[{"id":9,"reason":"superseded"}]}
If nothing needs merging or archiving: {"merged":[],"archived":[]}""".trimIndent()

internal val DEFAULT_MEMORY_CORE_PROMPT = """Rewrite the core memory below so it fits the budget and stays useful in every session. Keep only stable, high-value information. Preserve user wording where it carries intent.

Budget: about {budget} characters.
Sections: user (who the user is), rules (durable behavior rules), ai (assistant self-definition).

<core memories>
{core_memories}
</core memories>

Respond with ONLY this JSON:
{"entries":[{"section":"user","content":"...","sources":[1,2]}],"archived":[{"id":9,"reason":"merged"}]}
Every source id must appear in exactly one entry or in archived.""".trimIndent()

internal const val DEFAULT_MEMORY_SNAPSHOT_PROMPT =
    "The following is the assistant's persistent core memory, injected as background. " +
        "Treat it as known context; do not mention the snapshot. If several snapshots appear, the latest is authoritative."

internal const val DEFAULT_MEMORY_HIT_PROMPT =
    "Possibly relevant long-term memories are listed below, for reference only. " +
        "They may be unrelated, outdated, or wrong: verify against the conversation and update them when needed. " +
        "Never quote this block to the user."

internal const val DEFAULT_MEMORY_REBASE_PROMPT =
    "Conversation context was compacted. The persistent memory below is re-injected so you do not lose it. " +
        "Treat it as known context; do not mention the injection."

internal val DEFAULT_WORKSPACE_PROMPT = """<workspace>
You have a persistent Linux workspace "{workspace_name}" (proot rootfs). `/workspace` persists across turns.
- Use absolute rootfs paths. For large files, read with offset/limit and continue with nextOffset. Text is shown as `N|content`; never write the `N|` prefix back.
- Prefer workspace_read_file/write_file/edit_file for files; use workspace_shell only when a real shell command is needed.
- Optional tools (workspace_glob, workspace_grep, workspace_stat, workspace_read_files, workspace_read_result) require workspace_load_tools first.
- Pass expectedRevision from a read before editing a shared file; on conflict, re-read and merge.
- Do not re-read a file after a successful edit unless verification requires it.
- Read `/skills/<name>/SKILL.md` before following a skill.
- `/upload` contains the user's original files and is read-only; copy into `/workspace` before editing.
{workspace_mounts}{workspace_cwd}
</workspace>""".trimIndent()

internal val DEFAULT_CHILD_AGENT_PROMPT = """<child_agent_constraints>
You are a child agent. You cannot spawn/follow up on agents, manage skills/MCP/tasks/todos, ask the user, or use approval-required tools. Complete the assigned task autonomously; report blocked work or decisions that need the parent.
Sandbox: python3/node are absent by default; use bash with Perl/awk or install a runtime. Shell time is UTC; use get_time_info for local time. `/upload` may be absent. Put produced files under `/workspace/subagents/` and return exact paths.
</child_agent_constraints>""".trimIndent()

internal const val DEFAULT_SKILLS_PROMPT = """When a request clearly matches a listed skill, call `use_skill` to load its instructions before following it. Do not load unrelated skills or mention this mechanism."""
internal const val DEFAULT_SCHEDULED_TASK_PROMPT = """Manage only this assistant's scheduled tasks with `scheduled_task` (list / create / update / delete / set_enabled / run_now). Do not access other assistants' tasks."""
internal const val DEFAULT_VISION_PROMPT =
    "This model cannot see images. When a request involves an image, call vision_analyze with its URL or path and answer from the text result. Do not guess image content."
