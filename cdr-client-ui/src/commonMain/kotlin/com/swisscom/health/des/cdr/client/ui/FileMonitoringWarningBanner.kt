package com.swisscom.health.des.cdr.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.Res
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_file_monitoring_error_files
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_file_monitoring_old_temp_files
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_file_monitoring_refresh
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.label_file_monitoring_warnings
import com.swisscom.health.des.cdr.client.ui.cdr_client_ui.generated.resources.refresh_24dp_000000_FILL0_wght400_GRAD0_opsz24
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FileMonitoringWarningBanner(
    modifier: Modifier = Modifier,
    fileMonitoringStatus: DTOs.FileMonitoringStatusResponse,
    onRefreshClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3CD),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Text(
                text = "⚠️ ${stringResource(Res.string.label_file_monitoring_warnings)}",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF856404)
            )

            if (fileMonitoringStatus.errorFileCount > 0) {
                Text(
                    text = "• ${stringResource(Res.string.label_file_monitoring_error_files, fileMonitoringStatus.errorFileCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF856404),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (fileMonitoringStatus.oldTempFileCount > 0) {
                Text(
                    text = "• ${stringResource(Res.string.label_file_monitoring_old_temp_files, fileMonitoringStatus.oldTempFileCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF856404),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            TextButton(
                onClick = onRefreshClick,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
            ) {
                Icon(
                    painter = painterResource(Res.drawable.refresh_24dp_000000_FILL0_wght400_GRAD0_opsz24),
                    contentDescription = stringResource(Res.string.label_file_monitoring_refresh),
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFF856404)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(Res.string.label_file_monitoring_refresh),
                    color = Color(0xFF856404)
                )
            }
        }
    }
}

