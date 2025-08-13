package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.app_name
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_project_source
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_swisscom
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_version
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AboutDialog(
    modifier: Modifier,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier,
        onDismissRequest = onDismissRequest,
        title = {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(modifier = modifier, style = MaterialTheme.typography.titleMedium, text = stringResource(Res.string.app_name))
                Text(modifier = modifier, style = MaterialTheme.typography.titleSmall, text = "by ${stringResource(Res.string.label_swisscom)}")
            }
        },
        text = {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val appVersion = remember { getAppVersion() }
                val githubUrl = "https://github.com/swisscom/cdr-client"
                Text(
                    text = "${stringResource(Res.string.label_version)}: $appVersion",
                )
                Text(
                    text = buildAnnotatedString {
                        append("${stringResource(Res.string.label_project_source)}: ")
                        withLink(LinkAnnotation.Url(githubUrl)) {
                            withStyle(
                                style = SpanStyle(
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(githubUrl)
                            }
                        }
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

private fun getAppVersion(): String =
    try {
        val packageName = ::getAppVersion.javaClass.packageName
        val pkg = Package.getPackages()
        pkg?.firstOrNull { it.name == packageName }
            ?.implementationVersion
            ?: "unknown"
    } catch (e: Exception) {
        println("exception while trying to get app version: ${e.message}")
        "unknown"
    }
