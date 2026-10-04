/**
 * 新手引导 Compose 分页内容。
 *
 * 归属模块：ui/setup/compose
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.setup.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardingPermissionsPage(
    groups: List<OnboardingPermissionGroup>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding
) {
    OnboardingPage(modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_permissions_title),
            description = stringResource(R.string.onboarding_permissions_desc)
        )
        groups.forEach { group ->
            if (group.items.isNotEmpty()) {
                PermissionGroup(group = group)
            }
        }
    }
}

@Composable
internal fun OnboardingAsrChoicePage(
    selected: OnboardingAsrChoice,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onSelected: (OnboardingAsrChoice) -> Unit
) {
    OnboardingPage(modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_asr_title),
            description = stringResource(R.string.onboarding_asr_desc)
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_sf_free),
            description = stringResource(R.string.model_guide_option_sf_free_desc),
            action = stringResource(R.string.model_guide_option_sf_free_action),
            icon = Icons.Rounded.Stars,
            selected = selected == OnboardingAsrChoice.SiliconFlowFree,
            onClick = { onSelected(OnboardingAsrChoice.SiliconFlowFree) }
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_local),
            description = stringResource(R.string.model_guide_option_local_desc),
            action = stringResource(R.string.model_guide_option_local_action),
            icon = Icons.Rounded.PhoneAndroid,
            selected = selected == OnboardingAsrChoice.LocalModel,
            onClick = { onSelected(OnboardingAsrChoice.LocalModel) }
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_online),
            description = stringResource(R.string.model_guide_option_online_desc),
            action = stringResource(R.string.model_guide_option_online_action),
            icon = Icons.Rounded.Cloud,
            selected = selected == OnboardingAsrChoice.OnlineCustom,
            onClick = { onSelected(OnboardingAsrChoice.OnlineCustom) }
        )
    }
}

@Composable
internal fun OnboardingPrivacyPage(
    checked: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onCheckedChange: (Boolean) -> Unit
) {
    OnboardingPage(modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_privacy_title),
            description = stringResource(R.string.onboarding_privacy_desc)
        )
        OnboardingCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCheckedChange(!checked) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconByMode(Icons.Rounded.Security)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    BodyText(
                        text = stringResource(R.string.label_data_collection),
                        strong = true
                    )
                    Spacer(Modifier.height(4.dp))
                    SupportingText(
                        text = stringResource(R.string.onboarding_privacy_hint)
                    )
                }
                MiuixSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}

@Composable
internal fun OnboardingLinksPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onOpenProject: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenDocs: () -> Unit
) {
    OnboardingPage(modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_links_title),
            description = stringResource(R.string.onboarding_links_desc)
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_project),
            icon = Icons.Rounded.Code,
            onClick = onOpenProject
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_website),
            icon = Icons.Rounded.OpenInBrowser,
            onClick = onOpenWebsite
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_docs),
            icon = Icons.Rounded.Description,
            onClick = onOpenDocs
        )
    }
}

@Composable
private fun OnboardingPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    content: @Composable () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 1.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun OnboardingHeader(title: String, description: String) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MiuixText(text = title, style = MiuixTheme.textStyles.title2)
        MiuixText(
            text = description,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
private fun PermissionGroup(group: OnboardingPermissionGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            BodyText(text = stringResource(group.titleRes), strong = true)
            SupportingText(text = stringResource(group.descriptionRes))
        }
        OnboardingCard {
            Column {
                group.items.forEach { item ->
                    PermissionRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(item: OnboardingPermissionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconByMode(
            imageVector = if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            tint = when {
                item.granted -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            BodyText(text = stringResource(item.titleRes), strong = true)
            SupportingText(text = stringResource(item.descriptionRes))
            SupportingText(
                text = stringResource(
                    if (item.granted) {
                        R.string.onboarding_permission_status_granted
                    } else {
                        R.string.onboarding_permission_status_missing
                    }
                )
            )
        }
        PermissionAction(
            granted = item.granted,
            onClick = item.onRequest
        )
    }
}

@Composable
private fun PermissionAction(granted: Boolean, onClick: () -> Unit) {
    val text = stringResource(
        if (granted) {
            R.string.onboarding_permission_btn_enabled
        } else {
            R.string.onboarding_permission_btn_go_enable
        }
    )
    MiuixTextButton(
        text = text,
        onClick = onClick,
        enabled = !granted,
        colors = MiuixButtonDefaults.textButtonColorsPrimary()
    )
}

@Composable
private fun AsrChoiceCard(
    title: String,
    description: String,
    action: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected -> MiuixTheme.colorScheme.primary
        else -> Color.Transparent
    }
    OnboardingCard(
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconByMode(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BodyText(title, strong = true)
                SupportingText(description)
                SupportingText(action)
            }
            IconByMode(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                tint = when {
                    selected -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
            )
        }
    }
}

@Composable
private fun LinkButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    MiuixButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = MiuixButtonDefaults.buttonColorsPrimary()
    ) {
        MiuixIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        MiuixText(text = text, style = MiuixTheme.textStyles.button)
    }
}

@Composable
private fun OnboardingCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    MiuixCard(modifier = modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
private fun IconByMode(
    imageVector: ImageVector,
    tint: Color? = null
) {
    MiuixIcon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint ?: MiuixTheme.colorScheme.primary
    )
}

@Composable
private fun BodyText(text: String, strong: Boolean = false) {
    MiuixText(
        text = text,
        fontWeight = if (strong) FontWeight.Medium else null,
        style = MiuixTheme.textStyles.body1
    )
}

@Composable
private fun SupportingText(text: String) {
    MiuixText(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1
    )
}
