package me.yui.yuihub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.yui.yuihub.R
import me.yui.yuihub.data.datastore.SettingsStore
import me.yui.yuihub.data.datastore.getCurrentAssistant
import me.yui.yuihub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlinx.datetime.TimeZone as KotlinTimeZone
import kotlinx.datetime.toInstant
import kotlin.time.toJavaInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
    /** 历史消息的稳定解析模式：日期类占位符用消息自身的日期，易变占位符保持字面量 */
    val stable: Boolean = false,
    /** 当前消息的发送日期（stable 模式下用于日期占位符） */
    val messageDate: LocalDate? = null,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    /** 返回 null 表示保持字面量（不解析） */
    val resolver: (PlaceholderCtx) -> String?
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String?
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) { ctx ->
            // 历史消息用消息自身的发送日期、system 用会话首条用户消息的日期，保证多次请求渲染一致；
            // 无日期可用时（如空会话）保持字面量，绝不回落到今天——否则前缀每请求变化
            (if (ctx.stable) ctx.messageDate else null)?.let { it.toDateString() }
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) { ctx ->
            // 电量是高频变化值：仅当前消息解析，历史消息保持字面量以维持请求前缀稳定
            if (ctx.stable) null else ctx.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        val timeZone = KotlinTimeZone.currentSystemDefault()
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        // system 消息属于请求前缀头部：日期类占位符必须用会话级稳定值（首条用户消息的日期），
        // 易变占位符（电量等）保持字面量——否则 system 字节每次请求变化，缓存从 token 0 断裂。
        // 当前日期由轮次尾部的 time reminder / 当前消息占位符承载。
        val systemMessageDate = messages.firstOrNull { it.role == MessageRole.USER && !it.isSynthetic }
            ?.createdAt?.toInstant(timeZone)?.toJavaInstant()
            ?.atZone(ZoneId.systemDefault())?.toLocalDate()
        return messages.mapIndexed { index, message ->
            // 合成消息（时间提醒/记忆快照等）不解析占位符
            if (message.isSynthetic) return@mapIndexed message
            // system 与历史消息使用稳定解析（system 用会话首日）；仅最新一条用户消息使用"当前值"，
            // 同一消息在多次请求中渲染结果一致，不破坏请求前缀缓存。
            val stable = message.role == MessageRole.SYSTEM || index != lastUserIndex
            val messageDate = when {
                message.role == MessageRole.SYSTEM -> systemMessageDate
                stable -> message.createdAt.toInstant(timeZone).toJavaInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                else -> null
            }
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(
                                text = part.text,
                                ctx = ctx,
                                settingsStore = settingsStore,
                                stable = stable,
                                messageDate = messageDate,
                            )
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore,
        stable: Boolean,
        messageDate: LocalDate?,
    ): String {
        var result = text

        val placeholderCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            model = ctx.model,
            assistant = ctx.assistant,
            stable = stable,
            messageDate = messageDate,
        )
        defaultProvider.placeholders.forEach { (key, placeholderInfo) ->
            val value = placeholderInfo.resolver(placeholderCtx) ?: return@forEach
            result = result
                .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
                .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
        }

        return result
    }
}
