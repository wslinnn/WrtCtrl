package dev.wrtctrl.ui.screen

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.wrtctrl.R

private data class LanguageOption(val tag: String, val labelRes: Int)

private val OPTIONS = listOf(
    LanguageOption("system", R.string.language_follow_system),
    LanguageOption("zh-CN", R.string.language_chinese),
    LanguageOption("en", R.string.language_english),
)

/**
 * 语言设置（per-app language）：Android 13+ 走系统级设置并自动持久化，
 * 12 及以下由 appcompat 回退 + autoStoreLocales 持久化（manifest 已注册）。
 * 切换后 Activity 自动重建生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val currentTag = when {
        tags.startsWith("en") -> "en"
        tags.startsWith("zh") -> "zh-CN"
        else -> "system"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.language_switch_language),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OPTIONS.forEach { option ->
                val selected = currentTag == option.tag
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val locales = if (option.tag == "system") {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(option.tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        stringResource(option.labelRes),
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
