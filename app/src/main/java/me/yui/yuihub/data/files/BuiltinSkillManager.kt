package me.yui.yuihub.data.files

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内置技能管理：把 assets/builtin_skills/ 下的预置技能释放到 skills 目录。
 *
 * - 首次启动（或内置技能版本升级）时释放，用户可见可编辑可删除；
 * - 用户删除内置技能后写入墓碑标记（.deleted），之后不再自动恢复；
 * - 内置技能版本升级时，未被删除的技能会被覆盖为新版（用户改动会丢失，版本号只在
 *   内置内容变更时递增，避免覆盖用户手改）。
 */
object BuiltinSkillManager {

    private const val TAG = "BuiltinSkillManager"
    private const val ASSET_DIR = "builtin_skills"
    private const val VERSION_FILE = "builtin_skills_version.txt"

    // 与 assets/builtin_skills 内容对齐，更新内置技能时递增此值
    private const val CURRENT_VERSION = 1

    /**
     * 释放内置技能到 skills 目录。可在任意线程调用；启动时在 IO 线程调用。
     */
    fun extractBuiltinSkills(context: Context) {
        val skillsDir = context.filesDir.resolve(FileFolders.SKILLS).apply { mkdirs() }
        val versionFile = File(skillsDir, VERSION_FILE)

        if (versionFile.exists() && versionFile.readText().trim().toIntOrNull() == CURRENT_VERSION) {
            return
        }

        val names = runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        for (name in names) {
            val tombstone = File(skillsDir, ".$name.deleted")
            if (tombstone.exists()) continue
            val destDir = SkillPaths.resolveSkillDir(skillsDir, name) ?: continue
            runCatching {
                destDir.deleteRecursively()
                destDir.mkdirs()
                copyAssetDir(context, "$ASSET_DIR/$name", destDir)
            }.onFailure {
                Log.w(TAG, "extractBuiltinSkills: failed to extract $name", it)
                destDir.deleteRecursively()
            }
        }
        versionFile.writeText(CURRENT_VERSION.toString())
        Log.i(TAG, "extractBuiltinSkills: extracted builtin skills (version $CURRENT_VERSION)")
    }

    /**
     * 删除技能时调用：若目标目录存在内置版本标记（由释放流程写入），留下墓碑，
     * 防止下次启动自动恢复。
     */
    fun markDeleted(context: Context, skillName: String) {
        val skillsDir = context.filesDir.resolve(FileFolders.SKILLS)
        runCatching {
            if (context.assets.list("$ASSET_DIR/$skillName") != null) {
                File(skillsDir, ".$skillName.deleted").writeText(CURRENT_VERSION.toString())
            }
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        for (name in assets) {
            val childAsset = "$assetPath/$name"
            val destFile = File(destDir, name)
            val children = context.assets.list(childAsset)
            if (!children.isNullOrEmpty()) {
                destFile.mkdirs()
                copyAssetDir(context, childAsset, destFile)
            } else {
                context.assets.open(childAsset).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}