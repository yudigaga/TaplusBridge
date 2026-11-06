package io.github.yudigaga.taplusbridge.translation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.yudigaga.taplusbridge.ui.SettingsTheme
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Token-only foreground result surface; its retained job never serializes text or credentials. */
class TranslationActivity : ComponentActivity() {
    private lateinit var job: TranslationJob

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = lastCustomNonConfigurationInstance as? TranslationJob ?: consumeRequest()
        setContent {
            SettingsTheme {
                BackHandler { close() }
                Scaffold(Modifier.fillMaxSize(), containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    TranslationResultDialog(job.status, job.result, job.message, ::close,
                        onRetry = { job.start() }, onCopy = {
                            if (job.status == TranslationStatus.SUCCESS && job.result.isNotEmpty()) {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("译文", job.result))
                                job.message = "已复制译文"
                            }
                        })
                }
            }
        }
    }

    private fun consumeRequest(): TranslationJob {
        return try {
            val payload = TranslationRequests.consume(this, intent.getStringExtra("token"))
                ?: return TranslationJob.expired()
            val text = payload.getString("text") ?: return TranslationJob.expired()
            val settings = TranslationStore(this).load()
            settings.validate()
            if (settings.mode == TranslationSettings.Mode.NATIVE || text.isBlank() || text.length > 32768)
                TranslationJob.expired()
            else {
                val owner = applicationContext
                val source = payload.getString("source")
                TranslationJob(settings, text, authorized = { TranslationRequests.isCurrent(owner, settings, source) })
            }
        } catch (invalid: RuntimeException) { TranslationJob.expired() }
    }

    override fun onResume() {
        super.onResume()
        if (job.status != TranslationStatus.READY) return
        if (!job.isAuthorized()) { job.expire(); return }
        if (job.settings.mode == TranslationSettings.Mode.APP) {
            job.status = TranslationStatus.LOADING
            try {
                startActivity(TranslationApps.intent(this, job.settings, job.text))
                close()
            } catch (unavailable: RuntimeException) {
                job.status = TranslationStatus.APP_ERROR
                job.message = "本机翻译应用当前不可用，请检查应用或重新选择"
            }
        } else job.start()
    }

    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any = job

    override fun onDestroy() {
        if (!isChangingConfigurations) job.cancel()
        super.onDestroy()
    }

    private fun close() { job.cancel(); finish() }
}

internal enum class TranslationStatus { READY, LOADING, SUCCESS, ERROR, APP_ERROR, EXPIRED }

/** Main-thread state owns worker cancellation and rejects callbacks after close or retry. */
internal class TranslationJob(
    val settings: TranslationSettings,
    val text: String,
    private val authorized: () -> Boolean = { true },
    private val translator: (TranslationSettings, String, CancellationSignal) -> String = AiTranslationClient::translate,
) {
    var status by mutableStateOf(TranslationStatus.READY)
    var result by mutableStateOf("")
    var message by mutableStateOf("")
    var cancellation: CancellationSignal? = null
    var future: Future<*>? = null
    private var generation = 0
    private var closed = false

    fun start() {
        if (closed || settings.mode != TranslationSettings.Mode.AI
            || status !in listOf(TranslationStatus.READY, TranslationStatus.ERROR)) return
        if (!isAuthorized()) { expire(); return }
        val expected = ++generation
        val signal = CancellationSignal()
        cancellation = signal
        status = TranslationStatus.LOADING
        message = ""
        future = worker.submit {
            if (!isAuthorized()) {
                main.post { if (!closed && expected == generation) expire() }
                return@submit
            }
            var translated = ""
            var failure = ""
            try { translated = translator(settings, text, signal) }
            catch (error: IOException) { failure = errorMessage(error.message) }
            catch (error: RuntimeException) { failure = "翻译暂时失败，请重试" }
            main.post {
                if (closed || expected != generation || signal.isCanceled) return@post
                if (!isAuthorized()) { expire(); return@post }
                future = null
                cancellation = null
                if (failure.isEmpty()) {
                    result = translated
                    status = TranslationStatus.SUCCESS
                } else {
                    message = failure
                    status = TranslationStatus.ERROR
                }
            }
        }
    }

    fun isAuthorized(): Boolean = try { authorized() } catch (unavailable: RuntimeException) { false }

    fun expire() {
        cancel()
        result = ""
        message = ""
        status = TranslationStatus.EXPIRED
    }

    fun cancel() {
        if (closed) return
        closed = true
        generation++
        cancellation?.cancel()
        future?.cancel(true)
        future = null
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newCachedThreadPool { task ->
            Thread(task, "TaplusBridge-translation").apply { isDaemon = true }
        }
        fun expired() = TranslationJob(TranslationSettings.defaults(), "").apply { status = TranslationStatus.EXPIRED }
        private fun errorMessage(reason: String?) = when {
            reason == "translation_timeout" -> "翻译请求超时，请检查网络后重试"
            reason == "translation_configuration" -> "翻译配置不可用，请在识别页重新设置"
            reason == "translation_input" -> "本次文字不可用，请重新选择"
            reason == "translation_response_too_large" -> "翻译结果过长，请减少文字后重试"
            reason == "translation_response_invalid" -> "翻译服务未返回有效译文，请检查模型后重试"
            reason?.startsWith("translation_http_") == true -> "翻译服务拒绝请求，请检查接口、密钥和模型"
            else -> "无法连接翻译服务，请检查网络后重试"
        }
    }
}

@Composable internal fun TranslationResultDialog(
    status: TranslationStatus,
    result: String,
    message: String,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
) {
    OverlayDialog(show = true, title = "翻译", onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalWindowInfo.current.containerDpSize.height * .60f),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("translation.result.body"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    TranslationStatus.READY, TranslationStatus.LOADING -> Text("正在翻译…", Modifier.testTag("translation.result.loading"))
                    TranslationStatus.SUCCESS -> SelectionContainer {
                        Text(result, Modifier.testTag("translation.result.text"))
                    }
                    TranslationStatus.EXPIRED -> Text("翻译请求已失效，请重新选择文字", Modifier.testTag("translation.result.expired"))
                    TranslationStatus.ERROR, TranslationStatus.APP_ERROR -> Text(message, Modifier.testTag("translation.result.error"))
                }
                if (status == TranslationStatus.SUCCESS && message.isNotBlank()) Text(message, Modifier.testTag("translation.result.message"))
            }
            if (status == TranslationStatus.ERROR) TextButton("重试", onRetry,
                Modifier.fillMaxWidth().testTag("translation.result.retry"), colors = ButtonDefaults.textButtonColorsPrimary())
            if (status == TranslationStatus.SUCCESS) TextButton("复制译文", onCopy,
                Modifier.fillMaxWidth().testTag("translation.result.copy"), colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton("关闭", onClose, Modifier.fillMaxWidth().testTag("translation.result.close"))
        }
    }
}
