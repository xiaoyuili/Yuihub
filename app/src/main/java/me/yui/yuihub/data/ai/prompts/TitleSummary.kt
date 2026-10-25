package me.yui.yuihub.data.ai.prompts

internal val DEFAULT_TITLE_PROMPT = """Write a short title for <content>.
- Use the user's primary language ({locale}); capture the specific topic, not a generic "Chat".
- At most 12 CJK characters or 6 words; no quotes, punctuation, or Markdown.
- Output only the title.

<content>
{content}
</content>""".trimIndent()
