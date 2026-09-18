package dev.wrtctrl.ui.screen

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private fun currentTagFromDelegate(): String {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return when {
        tags.startsWith("en") -> "en"
        tags.startsWith("zh") -> "zh-CN"
        else -> "system"
    }
}

/**
 * 语言设置（per-app language）：Android 13+ 走系统级设置并自动持久化，
 * 12 及以下由 appcompat 回退 + autoStoreLocales 持久化（manifest 已注册）。
 * 切换后 Activity 自动重建生效；若切换后有效语言不变（如已处于系统语言时选
 * "跟随系统"），不会触发重建——故选中态用本地可观察状态即时回显。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    // 页面是组合级分支而非导航栈页，不拦截系统返回会直接退到桌面
    BackHandler(onBack = onBack)
    var selectedTag by remember { mutableStateOf(currentTagFromDelegate()) }

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
                val selected = selectedTag == option.tag
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (selectedTag != option.tag) {
                                selectedTag = option.tag
                                val locales = if (option.tag == "system") {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(option.tag)
                                }
                                AppCompatDelegate.setApplicationLocales(locales)
                            }
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
