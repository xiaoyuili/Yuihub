package me.yui.yuihub.data.model

data class EvolutionLesson(
    val id: Int = 0,
    val assistantId: String = "",
    val kind: String = KIND_CHAT,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val KIND_CODING = "coding"
        const val KIND_CHAT = "chat"
        const val KIND_ROLEPLAY = "roleplay"

        val KINDS = listOf(KIND_CODING, KIND_CHAT, KIND_ROLEPLAY)
    }
}
