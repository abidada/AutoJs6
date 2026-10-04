/**
 * Compose TTS 语音配置页：播报设置 / TTS 服务商（本地模型管理或 CloneTTS HTTP 服务）/ 试听。
 *
 * 自包含状态（直读写 Prefs），不经过 AsrSettingsViewModel；本地模型下载/导入/清除
 * 复用 ModelDownloadService（modelType=tts_offline），就绪检查走 TtsLocalModelCatalog。
 * 服务商按 TtsVendor 分支渲染：sherpa_offline 显示模型管理，clonetts 显示服务地址/
 * 音色/测试连接，互不混杂。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.KEY_TTS_ENABLED
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.tts.CloneTtsVoices
import com.brycewg.asrkb.tts.TtsLocalModelCatalog
import com.brycewg.asrkb.tts.TtsPlaybackCoordinator
import com.brycewg.asrkb.tts.TtsVendor
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.asr.ModelDownloadService
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDownloadSourceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TtsDownloadRequest(
    val variant: String,
    val options: List<DownloadSourceOption>
)

@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { Prefs(context) }

    // ---- 播报设置状态 ----
    var ttsEnabled by remember { mutableStateOf(prefs.ttsEnabled) }
    var speed by remember { mutableStateOf(prefs.ttsSpeed) }

    // ---- 服务商状态 ----
    var vendorId by remember { mutableStateOf(prefs.ttsVendorId) }
    val isCloneTtsVendor = TtsVendor.fromId(vendorId) == TtsVendor.CloneTts

    // ---- 本地模型状态 ----
    var variant by remember { mutableStateOf(prefs.ttsModelVariant) }
    var voiceSid by remember {
        mutableStateOf(
            TtsLocalModelCatalog.resolveVoiceSid(
                prefs.ttsModelVariant,
                prefs.ttsVoiceSid(prefs.ttsModelVariant)
            )
        )
    }
    var numThreads by remember { mutableStateOf(prefs.ttsNumThreads) }
    var preload by remember { mutableStateOf(prefs.ttsPreloadEnabled) }
    var keepAliveMinutes by remember { mutableStateOf(prefs.ttsKeepAliveMinutes) }
    var modelReady by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }

    // 下载/导入进行中标记：后台任务完成时页面仍在前台、不会触发 ON_RESUME，
    // 需轮询刷新就绪状态（与 AsrSettingsScreen 的 2.5s×120 轮询模式一致）
    var operationPending by remember { mutableStateOf(false) }

    // ---- CloneTTS 状态 ----
    var cloneTtsBaseUrl by remember { mutableStateOf(prefs.ttsCloneTtsBaseUrl) }
    var cloneTtsVoice by remember { mutableStateOf(prefs.ttsCloneTtsVoice) }
    var cloneTtsVoices by remember { mutableStateOf<List<CloneTtsVoices.VoiceInfo>>(emptyList()) }
    var cloneTtsVoiceLoadFailed by remember { mutableStateOf(false) }
    var cloneTtsTesting by remember { mutableStateOf(false) }

    // ---- 试听状态 ----
    var auditionText by remember { mutableStateOf("") }
    var auditionBusy by remember { mutableStateOf(false) }

    var downloadRequest by remember { mutableStateOf<TtsDownloadRequest?>(null) }
    var pendingImportVariant by remember { mutableStateOf<String?>(null) }
    var clearDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    // 音色列表缓存回显：服务未开时下拉仍显示上次成功拉取的列表
    LaunchedEffect(Unit) {
        val cached = prefs.ttsCloneTtsVoiceListCache
        if (cached.isNotBlank()) {
            val list = CloneTtsVoices.parse(cached)
            if (list.isNotEmpty()) cloneTtsVoices = list
        }
    }

    // 切到 CloneTTS 服务商时自动拉取音色列表；失败且无缓存时提示手动输入
    val currentCloneTtsBaseUrl by rememberUpdatedState(cloneTtsBaseUrl)
    LaunchedEffect(vendorId) {
        if (TtsVendor.fromId(vendorId) != TtsVendor.CloneTts) return@LaunchedEffect
        val json = runCatching {
            withContext(Dispatchers.IO) { CloneTtsVoices.fetchJson(currentCloneTtsBaseUrl) }
        }.getOrNull()
        val fetched = json?.let { CloneTtsVoices.parse(it) }.orEmpty()
        if (json != null && fetched.isNotEmpty()) {
            cloneTtsVoices = fetched
            cloneTtsVoiceLoadFailed = false
            prefs.ttsCloneTtsVoiceListCache = json
        } else if (cloneTtsVoices.isEmpty()) {
            cloneTtsVoiceLoadFailed = true
        }
    }

    fun refreshModelReady() {
        scope.launch(Dispatchers.IO) {
            val ready = TtsLocalModelCatalog.isModelReady(context, prefs.ttsModelVariant)
            withContext(Dispatchers.Main) {
                modelReady = ready
                if (ready) {
                    // 到达终态：清掉「已开始下载/导入」等陈旧操作提示，停止轮询
                    operationStatus = null
                    operationPending = false
                }
            }
        }
    }
    LaunchedEffect(Unit) { refreshModelReady() }

    LaunchedEffect(operationPending) {
        var attempts = 0
        while (operationPending && attempts < 120) {
            delay(2500)
            refreshModelReady()
            attempts += 1
        }
    }

    // 返回本页时刷新就绪状态（后台下载可能已完成）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshModelReady()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 跨入口实时同步：悬浮菜单等外部入口修改播报总开关时，本页显示同步刷新
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TTS_ENABLED) {
                ttsEnabled = prefs.ttsEnabled
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val importVariant = pendingImportVariant
        pendingImportVariant = null
        if (uri != null && importVariant != null) {
            runCatching {
                ModelDownloadService.startImport(context, uri, importVariant, TtsLocalModelCatalog.MODEL_TYPE)
                operationStatus = context.getString(R.string.tts_import_started_in_bg)
                operationPending = true
            }.onFailure {
                operationStatus = context.getString(R.string.tts_import_failed, it.message ?: "")
            }
        }
    }

    fun showToast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    TtsScaffold(onBack = onBack) { innerPadding, scrollModifier ->
        downloadRequest?.let { request ->
            SettingsDownloadSourceSheet(
                options = request.options,
                onDismiss = { downloadRequest = null },
                onSelect = { option ->
                    runCatching {
                        ModelDownloadService.startDownload(
                            context,
                            option.url,
                            request.variant,
                            TtsLocalModelCatalog.MODEL_TYPE
                        )
                        operationStatus = context.getString(R.string.tts_download_started_in_bg)
                        operationPending = true
                    }.onFailure {
                        operationStatus = context.getString(R.string.tts_download_status_failed)
                    }
                    downloadRequest = null
                }
            )
        }
        clearDialog?.let { dialog ->
            TtsClearDialogHost(
                dialog = dialog,
                onDismiss = { clearDialog = null }
            )
        }

        SettingsLazyColumn(
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("feedback") {
                TtsFeedbackSection(
                    ttsEnabled = ttsEnabled,
                    speed = speed,
                    onEnabledChange = { checked ->
                        ttsEnabled = checked
                        prefs.ttsEnabled = checked
                        if (!checked) {
                            // 总开关关闭：立刻打断播报，监听逻辑回到原版行为
                            TtsPlaybackCoordinator.stopSpeaking()
                        }
                    },
                    onSpeedChange = { speed = it; prefs.ttsSpeed = it }
                )
            }

            item("vendor") {
                TtsVendorSection(
                    context = context,
                    prefs = prefs,
                    vendorId = vendorId,
                    variant = variant,
                    voiceSid = voiceSid,
                    numThreads = numThreads,
                    preload = preload,
                    keepAliveMinutes = keepAliveMinutes,
                    modelReady = modelReady,
                    operationStatus = operationStatus,
                    cloneTtsBaseUrl = cloneTtsBaseUrl,
                    cloneTtsVoices = cloneTtsVoices,
                    cloneTtsVoice = cloneTtsVoice,
                    cloneTtsTesting = cloneTtsTesting,
                    cloneTtsVoiceLoadFailed = cloneTtsVoiceLoadFailed,
                    onVendorChange = { selected ->
                        vendorId = selected
                        prefs.ttsVendorId = selected
                    },
                    onVariantChange = { selected ->
                        variant = selected
                        prefs.ttsModelVariant = selected
                        // 切换变体：音色回落到该变体的已存/默认音色
                        voiceSid = TtsLocalModelCatalog.resolveVoiceSid(
                            selected,
                            prefs.ttsVoiceSid(selected)
                        )
                        refreshModelReady()
                    },
                    onVoiceSidChange = { sid ->
                        voiceSid = sid
                        prefs.setTtsVoiceSid(TtsLocalModelCatalog.normalizeVariant(variant), sid)
                    },
                    onNumThreadsChange = { numThreads = it; prefs.ttsNumThreads = it },
                    onPreloadChange = { preload = it; prefs.ttsPreloadEnabled = it },
                    onKeepAliveChange = { keepAliveMinutes = it; prefs.ttsKeepAliveMinutes = it },
                    onDownload = { selectedVariant ->
                        downloadRequest = TtsDownloadRequest(
                            variant = selectedVariant,
                            options = DownloadSourceConfig.buildOptions(
                                context,
                                TtsLocalModelCatalog.variantSpec(selectedVariant).downloadUrl
                            )
                        )
                    },
                    onImport = { selectedVariant ->
                        pendingImportVariant = selectedVariant
                        importLauncher.launch("*/*")
                    },
                    onClear = {
                        clearDialog = SettingsMessageDialogState(
                            title = context.getString(R.string.tts_clear_confirm_title),
                            message = context.getString(R.string.tts_clear_confirm_message),
                            confirmText = context.getString(android.R.string.ok),
                            dismissText = context.getString(R.string.btn_cancel),
                            onConfirm = {
                                scope.launch(Dispatchers.IO) {
                                    val success = runCatching {
                                        TtsLocalModelCatalog.clearInstalled(context)
                                    }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        operationStatus = context.getString(
                                            if (success) R.string.tts_clear_done else R.string.tts_clear_failed
                                        )
                                        refreshModelReady()
                                    }
                                }
                            }
                        )
                    },
                    onCloneTtsBaseUrlChange = {
                        cloneTtsBaseUrl = it
                        prefs.ttsCloneTtsBaseUrl = it
                    },
                    onCloneTtsVoiceChange = {
                        cloneTtsVoice = it
                        prefs.ttsCloneTtsVoice = it
                    },
                    onCloneTtsTest = {
                        if (!cloneTtsTesting) {
                            scope.launch {
                                cloneTtsTesting = true
                                val json = runCatching {
                                    withContext(Dispatchers.IO) { CloneTtsVoices.fetchJson(cloneTtsBaseUrl) }
                                }
                                cloneTtsTesting = false
                                json.onSuccess { body ->
                                    val list = CloneTtsVoices.parse(body)
                                    if (list.isNotEmpty()) {
                                        cloneTtsVoices = list
                                        cloneTtsVoiceLoadFailed = false
                                        prefs.ttsCloneTtsVoiceListCache = body
                                        showToast(
                                            context.getString(R.string.tts_clone_tts_test_ok, list.size)
                                        )
                                    } else {
                                        showToast(
                                            context.getString(
                                                R.string.tts_clone_tts_test_failed,
                                                context.getString(R.string.tts_clone_tts_test_invalid)
                                            )
                                        )
                                    }
                                }.onFailure { t ->
                                    showToast(
                                        context.getString(
                                            R.string.tts_clone_tts_test_failed,
                                            t.message ?: ""
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }

            item("audition") {
                TtsAuditionSection(
                    context = context,
                    prefs = prefs,
                    text = auditionText,
                    busy = auditionBusy,
                    modelReady = modelReady,
                    isCloneTts = isCloneTtsVendor,
                    onTextChange = { auditionText = it },
                    onPlay = {
                        val textToSpeak = auditionText.ifBlank {
                            context.getString(R.string.tts_audition_default_text)
                        }
                        auditionBusy = true
                        TtsPlaybackCoordinator.speak(
                            context,
                            textToSpeak,
                            bypassToggle = true,
                            onFinished = { success ->
                                auditionBusy = false
                                if (success) return@speak
                                when {
                                    isCloneTtsVendor -> showToast(
                                        context.getString(R.string.tts_clone_tts_audition_failed)
                                    )

                                    !TtsLocalModelCatalog.isModelReady(context, prefs.ttsModelVariant) -> {
                                        android.widget.Toast.makeText(
                                            context,
                                            R.string.tts_status_not_installed,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TtsScaffold(
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        titleRes = R.string.title_tts_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
private fun TtsClearDialogHost(
    dialog: SettingsMessageDialogState,
    onDismiss: () -> Unit
) {
    AsrSettingsDialogHost(
        choiceSheet = null,
        multiChoiceSheet = null,
        featureExplainerDialog = null,
        messageDialog = dialog,
        onDismissChoiceSheet = {},
        onDismissMultiChoiceSheet = {},
        onDismissFeatureExplainerDialog = {},
        onDismissMessageDialog = onDismiss
    )
}

@Composable
private fun TtsFeedbackSection(
    ttsEnabled: Boolean,
    speed: Float,
    onEnabledChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    AsrSection(titleRes = R.string.section_tts_feedback) {
        // 总开关即全部行为：开启后固定播报「正在听」与分发命中/未命中结果
        var itemIndex = 0
        val itemCount = if (!ttsEnabled) 1 else 2
        AsrSwitchPreference(
            id = "tts_enabled",
            titleRes = R.string.label_tts_enabled,
            checked = ttsEnabled,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onEnabledChange
        )
        if (!ttsEnabled) return@AsrSection
        AsrSliderPreference(
            titleRes = R.string.label_tts_speed,
            valueLabel = { value -> String.format(Locale.US, "%.2f×", value) },
            value = speed,
            valueRange = Prefs.TTS_SPEED_MIN..Prefs.TTS_SPEED_MAX,
            steps = 5,
            index = itemIndex,
            count = itemCount,
            onValueChange = onSpeedChange
        )
    }
}

@Composable
private fun TtsVendorSection(
    context: android.content.Context,
    prefs: Prefs,
    vendorId: String,
    // 本地离线（sherpa-onnx）
    variant: String,
    voiceSid: Int,
    numThreads: Int,
    preload: Boolean,
    keepAliveMinutes: Int,
    modelReady: Boolean,
    operationStatus: String?,
    // CloneTTS（HTTP 服务）
    cloneTtsBaseUrl: String,
    cloneTtsVoices: List<CloneTtsVoices.VoiceInfo>,
    cloneTtsVoice: String,
    cloneTtsTesting: Boolean,
    cloneTtsVoiceLoadFailed: Boolean,
    onVendorChange: (String) -> Unit,
    onVariantChange: (String) -> Unit,
    onVoiceSidChange: (Int) -> Unit,
    onNumThreadsChange: (Int) -> Unit,
    onPreloadChange: (Boolean) -> Unit,
    onKeepAliveChange: (Int) -> Unit,
    onDownload: (String) -> Unit,
    onImport: (String) -> Unit,
    onClear: () -> Unit,
    onCloneTtsBaseUrlChange: (String) -> Unit,
    onCloneTtsVoiceChange: (String) -> Unit,
    onCloneTtsTest: () -> Unit
) {
    AsrSection(titleRes = R.string.section_tts_vendor) {
        val isCloneTts = TtsVendor.fromId(vendorId) == TtsVendor.CloneTts
        var itemIndex = 0
        if (isCloneTts) {
            // CloneTTS：服务商 + 服务地址 + 音色（下拉或手动输入） + 测试连接
            val itemCount = 4
            AsrDropdownPreference(
                id = "tts_vendor",
                titleRes = R.string.label_tts_vendor,
                options = TtsVendor.ordered().map { vendor ->
                    DropdownOption(vendor.id, context.getString(vendor.displayNameResId))
                },
                selectedOptionId = TtsVendor.fromId(vendorId).id,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onVendorChange
            )
            AsrTextField(
                value = cloneTtsBaseUrl,
                onValueChange = onCloneTtsBaseUrlChange,
                label = stringResource(R.string.label_tts_clone_tts_base_url),
                index = itemIndex++,
                count = itemCount
            )
            if (cloneTtsVoices.isEmpty()) {
                // 列表不可用（服务未开/未拉取）：手动输入别名兜底，留空跟随默认音色
                AsrTextField(
                    value = cloneTtsVoice,
                    onValueChange = onCloneTtsVoiceChange,
                    label = stringResource(R.string.label_tts_clone_tts_voice_manual),
                    index = itemIndex++,
                    count = itemCount
                )
            } else {
                val voiceOptions = buildList {
                    add(DropdownOption("", context.getString(R.string.tts_clone_tts_voice_default)))
                    cloneTtsVoices.forEach { voice ->
                        add(DropdownOption(voice.alias, voice.label))
                    }
                }
                AsrDropdownPreference(
                    id = "tts_clone_tts_voice",
                    titleRes = R.string.label_tts_voice,
                    options = voiceOptions,
                    selectedOptionId = if (voiceOptions.any { it.id == cloneTtsVoice }) cloneTtsVoice else "",
                    index = itemIndex++,
                    count = itemCount,
                    onSelectedOptionChange = onCloneTtsVoiceChange
                )
            }
            if (cloneTtsVoiceLoadFailed) {
                AsrBodyText(
                    text = stringResource(R.string.tts_clone_tts_voice_load_failed)
                )
            }
            AsrActionPreference(
                id = "tts_clone_tts_test",
                titleRes = if (cloneTtsTesting) R.string.tts_clone_tts_testing else R.string.btn_tts_clone_tts_test,
                index = itemIndex,
                count = itemCount,
                onClick = onCloneTtsTest
            )
        } else {
            // 本地离线：模型变体/音色/线程数/预加载/常驻 + 模型状态与操作
            val spec = TtsLocalModelCatalog.variantSpec(variant)
            val showVoicePicker = spec.voices.size > 1
            val itemCount = 5 + if (showVoicePicker) 1 else 0
            AsrDropdownPreference(
                id = "tts_vendor",
                titleRes = R.string.label_tts_vendor,
                options = TtsVendor.ordered().map { vendor ->
                    DropdownOption(vendor.id, context.getString(vendor.displayNameResId))
                },
                selectedOptionId = TtsVendor.fromId(vendorId).id,
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onVendorChange
            )
            AsrDropdownPreference(
                id = "tts_model_variant",
                titleRes = R.string.label_tts_model_variant,
                options = TtsLocalModelCatalog.variants.map { s ->
                    DropdownOption(s.id, context.getString(s.labelRes))
                },
                selectedOptionId = TtsLocalModelCatalog.normalizeVariant(variant),
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onVariantChange
            )
            if (showVoicePicker) {
                AsrDropdownPreference(
                    id = "tts_voice",
                    titleRes = R.string.label_tts_voice,
                    options = spec.voices.map { voice ->
                        DropdownOption(
                            voice.sid.toString(),
                            TtsLocalModelCatalog.voiceLabel(context, spec.id, voice.sid)
                        )
                    },
                    selectedOptionId = voiceSid.toString(),
                    index = itemIndex++,
                    count = itemCount,
                    onSelectedOptionChange = { value ->
                        value.toIntOrNull()?.let(onVoiceSidChange)
                    }
                )
            }
            AsrSliderPreference(
                titleRes = R.string.label_tts_threads,
                valueLabel = { it.toInt().toString() },
                value = numThreads.toFloat(),
                valueRange = 1f..8f,
                steps = 6,
                index = itemIndex++,
                count = itemCount,
                onValueChange = { onNumThreadsChange(it.toInt()) }
            )
            AsrSwitchPreference(
                id = "tts_preload",
                titleRes = R.string.label_tts_preload,
                checked = preload,
                index = itemIndex++,
                count = itemCount,
                onCheckedChange = { checked ->
                    onPreloadChange(checked)
                    if (checked) {
                        TtsPlaybackCoordinator.ensureInit(context)
                        com.brycewg.asrkb.tts.OfflineTtsManager.preloadAsync(context, prefs)
                    }
                }
            )
            AsrDropdownPreference(
                id = "tts_keep_alive",
                titleRes = R.string.label_tts_keep_alive,
                options = ttsKeepAliveOptions(context),
                selectedOptionId = keepAliveMinutes.toString(),
                index = itemIndex,
                count = itemCount,
                onSelectedOptionChange = { value -> onKeepAliveChange(value.toIntOrNull() ?: 5) }
            )

            // 模型状态与操作（下载/导入/清除）
            val status = operationStatus
                ?: stringResource(if (modelReady) R.string.tts_status_ready else R.string.tts_status_not_installed)
            AsrBodyText(text = status)
            SettingsActionButtonRow() {
                if (modelReady) {
                    SettingsActionButton(
                        text = stringResource(R.string.btn_tts_clear),
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    SettingsActionButton(
                        text = stringResource(R.string.btn_tts_download),
                        onClick = { onDownload(TtsLocalModelCatalog.normalizeVariant(variant)) },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsActionButton(
                        text = stringResource(R.string.btn_tts_import),
                        onClick = { onImport(TtsLocalModelCatalog.normalizeVariant(variant)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsAuditionSection(
    context: android.content.Context,
    prefs: Prefs,
    text: String,
    busy: Boolean,
    modelReady: Boolean,
    isCloneTts: Boolean,
    onTextChange: (String) -> Unit,
    onPlay: () -> Unit
) {
    AsrSection(titleRes = R.string.section_tts_audition) {
        AsrTextField(
            value = text,
            onValueChange = onTextChange,
            label = stringResource(R.string.label_tts_audition_text),
            singleLine = false,
            minLines = 2,
            index = 0,
            count = 2
        )
        AsrActionPreference(
            id = "tts_audition_play",
            titleRes = if (busy) R.string.btn_tts_audition_stop else R.string.btn_tts_audition_play,
            index = 1,
            count = 2,
            onClick = {
                if (busy || TtsPlaybackCoordinator.isBusy) {
                    TtsPlaybackCoordinator.stopSpeaking()
                } else if (isCloneTts || modelReady || TtsLocalModelCatalog.isModelReady(context, prefs.ttsModelVariant)) {
                    onPlay()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        R.string.tts_status_not_installed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

private fun ttsKeepAliveOptions(context: android.content.Context): List<DropdownOption> = listOf(
    DropdownOption("0", context.getString(R.string.tts_keep_alive_immediate)),
    DropdownOption("1", context.getString(R.string.tts_keep_alive_1m)),
    DropdownOption("5", context.getString(R.string.tts_keep_alive_5m)),
    DropdownOption("15", context.getString(R.string.tts_keep_alive_15m)),
    DropdownOption("-1", context.getString(R.string.tts_keep_alive_forever))
)
