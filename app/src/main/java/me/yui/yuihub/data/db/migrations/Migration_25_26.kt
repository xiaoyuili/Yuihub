package me.yui.yuihub.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "ConversationEntity", columnName = "suggestions")
class Migration_25_26 : AutoMigrationSpec
