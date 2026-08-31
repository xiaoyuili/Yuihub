package me.yui.yuihub.data.favorite

import me.yui.yuihub.data.db.entity.FavoriteEntity
import me.yui.yuihub.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
