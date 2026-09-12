package me.yui.yuihub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.yui.yuihub.data.datastore.Settings
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.model.Lorebook
import me.yui.yuihub.data.model.PromptInjection
import kotlin.uuid.Uuid

/**
 * 世界书编辑共享状态。
 *
 * 世界书存储在 DataStore 的 Settings 中（非数据库），所有变更都通过
 * [SettingsStore.update] 的读-改-写完成，避免页面持有过期快照互相覆盖。
 */
class LorebookVM(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    private fun mutateLorebooks(transform: (List<Lorebook>) -> List<Lorebook>) {
        viewModelScope.launch {
            settingsStore.update { it.copy(lorebooks = transform(it.lorebooks)) }
        }
    }

    private fun mutateBook(bookId: Uuid, transform: (Lorebook) -> Lorebook) {
        mutateLorebooks { books -> books.map { if (it.id == bookId) transform(it) else it } }
    }

    /** 新建一本书并返回其 id（id 在写入前生成，页面可直接跳转） */
    fun addBook(): Uuid {
        val book = Lorebook()
        mutateLorebooks { it + book }
        return book.id
    }

    fun updateBook(book: Lorebook) {
        mutateLorebooks { books ->
            if (books.any { it.id == book.id }) books.map { if (it.id == book.id) book else it }
            else books + book
        }
    }

    fun deleteBook(bookId: Uuid) = mutateLorebooks { books -> books.filterNot { it.id == bookId } }

    fun renameBook(bookId: Uuid, name: String) = mutateBook(bookId) { it.copy(name = name) }

    fun setBookDescription(bookId: Uuid, description: String) = mutateBook(bookId) { it.copy(description = description) }

    fun reorderBooks(from: Int, to: Int) = mutateLorebooks { books ->
        books.toMutableList().apply { add(to.coerceIn(0, size), removeAt(from.coerceIn(0, size - 1))) }
    }

    fun upsertEntry(bookId: Uuid, entry: PromptInjection.RegexInjection) = mutateBook(bookId) { book ->
        val exists = book.entries.any { it.id == entry.id }
        book.copy(
            entries = if (exists) book.entries.map { if (it.id == entry.id) entry else it }
            else book.entries + entry
        )
    }

    fun deleteEntry(bookId: Uuid, entryId: Uuid) = mutateBook(bookId) { book ->
        book.copy(entries = book.entries.filterNot { it.id == entryId })
    }

    fun setEntryEnabled(bookId: Uuid, entryId: Uuid, enabled: Boolean) = mutateBook(bookId) { book ->
        book.copy(entries = book.entries.map { if (it.id == entryId) it.copy(enabled = enabled) else it })
    }

    fun reorderEntries(bookId: Uuid, from: Int, to: Int) = mutateBook(bookId) { book ->
        book.copy(
            entries = book.entries.toMutableList().apply {
                add(to.coerceIn(0, size), removeAt(from.coerceIn(0, size - 1)))
            }
        )
    }
}
