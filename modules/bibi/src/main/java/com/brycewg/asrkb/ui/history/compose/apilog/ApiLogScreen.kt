/**
 * API Log Compose 页面：搜索、筛选、列表、清空确认与详情弹窗。
 *
 * 归属模块：ui/history/compose/apilog
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.history.compose.apilog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFilterChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSearchField
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ApiLogFilter(val labelRes: Int) {
    All(R.string.filter_all),
    Asr(R.string.api_log_filter_asr),
    Llm(R.string.api_log_filter_llm),
    Failed(R.string.api_log_filter_failed)
}

@Composable
fun ApiLogScreen(
    records: List<ApiLogStore.ApiLogRecord>,
    onBack: () -> Unit,
    onClearConfirmed: () -> Unit,
    onCopyDetails: (ApiLogStore.ApiLogRecord) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ApiLogFilter.All) }
    var clearDialogVisible by rememberSaveable { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<ApiLogStore.ApiLogRecord?>(null) }

    val filteredRecords = remember(records, query, activeFilter) {
        val trimmedQuery = query.trim()
        records.filter { record ->
            val categoryMatches = when (activeFilter) {
                ApiLogFilter.All -> true
                ApiLogFilter.Asr -> record.category.equals("ASR", ignoreCase = true)
                ApiLogFilter.Llm -> record.category.equals("LLM", ignoreCase = true)
                ApiLogFilter.Failed -> !record.success && !record.canceled
            }
            val queryMatches = trimmedQuery.isEmpty() ||
                apiLogSearchableText(record).contains(trimmedQuery, ignoreCase = true)
            categoryMatches && queryMatches
        }
    }

    ApiLogScaffold(
        onBack = onBack,
        onClear = { clearDialogVisible = true }
    ) { innerPadding, scrollModifier ->
        ApiLogContent(
            records = records,
            filteredRecords = filteredRecords,
            query = query,
            activeFilter = activeFilter,
            onQueryChange = { query = it },
            onFilterChange = { activeFilter = it },
            onRecordClick = { selectedRecord = it },
            scrollModifier = scrollModifier,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        )
    }

    if (clearDialogVisible) {
        ClearApiLogDialog(
            onDismiss = { clearDialogVisible = false },
            onConfirm = {
                clearDialogVisible = false
                onClearConfirmed()
            }
        )
    }

    selectedRecord?.let { record ->
        ApiLogDetailsDialog(
            record = record,
            onDismiss = { selectedRecord = null },
            onCopy = {
                onCopyDetails(record)
                selectedRecord = null
            }
        )
    }
}

@Composable
private fun ApiLogScaffold(
    onBack: () -> Unit,
    onClear: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val clearLabel = stringResource(R.string.menu_clear_api_log)
    SettingsDetailScaffold(
        titleRes = R.string.title_api_log,
        onBack = onBack,
        actions = {
            MiuixIconButton(onClick = onClear) {
                MiuixIcon(Icons.Rounded.DeleteSweep, contentDescription = clearLabel)
            }
        },
        content = content
    )
}

@Composable
private fun ApiLogContent(
    records: List<ApiLogStore.ApiLogRecord>,
    filteredRecords: List<ApiLogStore.ApiLogRecord>,
    query: String,
    activeFilter: ApiLogFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ApiLogFilter) -> Unit,
    onRecordClick: (ApiLogStore.ApiLogRecord) -> Unit,
    scrollModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = SettingsLayoutMetrics.PageHorizontalPadding)
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = dimensionResource(R.dimen.settings_form_max_width))
                .fillMaxSize()
        ) {
            ApiLogFilters(
                activeFilter = activeFilter,
                onFilterChange = onFilterChange
            )
            ApiLogSearchField(
                query = query,
                onQueryChange = onQueryChange
            )
            if (filteredRecords.isEmpty()) {
                EmptyApiLogState(
                    message = stringResource(
                        if (records.isEmpty()) R.string.empty_api_log else R.string.empty_api_log_filtered
                    )
                )
            } else {
                ApiLogList(
                    records = filteredRecords,
                    scrollModifier = scrollModifier,
                    onRecordClick = onRecordClick
                )
            }
        }
    }
}

@Composable
private fun ApiLogFilters(
    activeFilter: ApiLogFilter,
    onFilterChange: (ApiLogFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ApiLogFilter.entries.forEach { filter ->
            SettingsFilterChip(
                label = stringResource(filter.labelRes),
                selected = activeFilter == filter,
                onClick = { onFilterChange(filter) }
            )
        }
    }
}

@Composable
private fun ApiLogSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    SettingsSearchField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.hint_search_api_log),
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun ApiLogList(
    records: List<ApiLogStore.ApiLogRecord>,
    scrollModifier: Modifier,
    onRecordClick: (ApiLogStore.ApiLogRecord) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(scrollModifier),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = records,
            key = { it.id }
        ) { record ->
            ApiLogCard(
                record = record,
                onClick = { onRecordClick(record) }
            )
        }
    }
}

@Composable
private fun ApiLogCard(
    record: ApiLogStore.ApiLogRecord,
    onClick: () -> Unit
) {
    // 使用 Card 自带 onClick，按压指示器落在圆角 clip 内，避免外层 clickable 画出直角遮罩。
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        showIndication = true
    ) {
        ApiLogCardContent(record = record)
    }
}

@Composable
private fun ApiLogCardContent(
    record: ApiLogStore.ApiLogRecord
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(statusColor(record), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ApiLogText(
                text = apiLogTitle(context, record),
                strong = true,
                maxLines = 2
            )
            ApiLogText(
                text = apiLogTime(context, record),
                secondary = true,
                maxLines = 1
            )
            ApiLogText(
                text = formatApiLogEndpoint(context, record),
                maxLines = 2
            )
            ApiLogText(
                text = apiLogMeta(context, record),
                secondary = true,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun statusColor(record: ApiLogStore.ApiLogRecord): Color = when {
    record.canceled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    record.success -> MiuixTheme.colorScheme.primary
    else -> MiuixTheme.colorScheme.error
}

@Composable
private fun ApiLogText(
    text: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    strong: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    MiuixText(
        text = text,
        modifier = modifier,
        style = if (secondary) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.body2,
        color = if (secondary) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
        fontWeight = if (strong) FontWeight.SemiBold else null,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun EmptyApiLogState(
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        ApiLogText(
            text = message,
            secondary = true,
            maxLines = 2
        )
    }
}

@Composable
private fun ClearApiLogDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.dialog_clear_api_log_title)
    val message = stringResource(R.string.dialog_clear_api_log_message)
    val confirm = stringResource(R.string.dialog_filter_ok)
    val cancel = stringResource(R.string.dialog_filter_cancel)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }

    OverlayDialog(
        show = exit.show,
        title = title,
        summary = message,
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = cancel,
                    onClick = { exit.dismiss(onDismiss) }
                ),
                SettingsDialogAction(
                    text = confirm,
                    onClick = { exit.dismiss(onConfirm) },
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun ApiLogDetailsDialog(
    record: ApiLogStore.ApiLogRecord,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    MiuixDetailsDialog(record, onDismiss, onCopy)
}

@Composable
private fun MiuixDetailsDialog(
    record: ApiLogStore.ApiLogRecord,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val exit = rememberSettingsDialogExitController(record.id)
    fun finishDismiss() {
        exit.finish()
    }

    OverlayDialog(
        show = exit.show,
        title = apiLogTitle(context, record),
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        DetailsContent(
            record = record,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .padding(bottom = 16.dp)
        )
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.btn_close),
                    onClick = { exit.dismiss(onDismiss) }
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.btn_copy),
                    onClick = { exit.dismiss(onCopy) },
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun DetailsContent(
    record: ApiLogStore.ApiLogRecord,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SelectionContainer {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DetailsHeader(
                endpoint = formatApiLogEndpoint(context, record),
                meta = apiLogMeta(context, record)
            )
            DetailSection(
                title = stringResource(R.string.api_log_request),
                value = record.requestSummary.ifBlank { "-" }
            )
            DetailSection(
                title = stringResource(R.string.api_log_request_structure),
                value = record.requestStructure.ifBlank { "-" }
            )
            DetailSection(
                title = stringResource(R.string.api_log_response),
                value = record.responseSummary.ifBlank { "-" }
            )
            DetailSection(
                title = stringResource(R.string.api_log_error),
                value = record.errorSummary.ifBlank { "-" },
                error = record.errorSummary.isNotBlank()
            )
        }
    }
}

@Composable
private fun DetailsHeader(
    endpoint: String,
    meta: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixText(
            text = endpoint,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        MiuixText(
            text = meta,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun DetailSection(
    title: String,
    value: String,
    error: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ApiLogText(
            text = title,
            strong = true,
            maxLines = 1
        )
        MiuixText(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}
