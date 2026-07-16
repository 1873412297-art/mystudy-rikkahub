package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptTraceRecord

@Composable
fun TavernPromptOverview(
    record: PromptTraceRecord,
    modifier: Modifier = Modifier,
) {
    val metadata = record.payload.metadata
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tavern_prompt_console_actual_tokens),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = metadata.actualPromptTokens?.toString()
                            ?: stringResource(R.string.tavern_prompt_console_not_provided),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text("Model: ${metadata.modelId}")
                    Text("Provider step: ${metadata.providerStepIndex + 1}")
                    Text("Messages: ${metadata.finalMessageCount}")
                    metadata.speakerName?.let { Text("Speaker: $it") }
                }
            }
        }

        items(record.payload.sections) { section ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(section.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(
                            R.string.tavern_prompt_console_approx,
                            section.approximateTokens,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (!section.active) {
                        Text("Inactive", color = MaterialTheme.colorScheme.outline)
                    }
                    Text(section.text)
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.tavern_prompt_console_estimate_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
