package me.rerere.rikkahub.ui.pages.tavern.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.trace.PromptInjectionSourceType
import me.rerere.rikkahub.data.ai.trace.PromptInjectionTrace

@Composable
fun TavernPromptHits(
    hits: List<PromptInjectionTrace>,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(hits) {
        hits.sortedWith(
            compareBy<PromptInjectionTrace>(
                { it.sourceType == PromptInjectionSourceType.MODE },
                { it.lorebookName.orEmpty() },
                { -it.priority },
            )
        )
    }
    if (ordered.isEmpty()) {
        TavernPromptEmptyState(
            title = stringResource(R.string.tavern_prompt_console_no_hits),
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ordered, key = { it.injectionId.toString() }) { hit ->
            var expanded by rememberSaveable(hit.injectionId.toString()) { mutableStateOf(false) }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { expanded = !expanded },
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = hit.lorebookName ?: hit.injectionName.ifBlank { hit.sourceType.name },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.tavern_prompt_console_hit_details,
                            hit.position,
                            hit.role,
                            hit.priority,
                            hit.injectDepth,
                        )
                    )
                    hit.match?.let { match ->
                        Text(
                            stringResource(
                                R.string.tavern_prompt_console_hit_scan,
                                match.type,
                                match.scannedMessageIds.size,
                                match.scanDepth,
                            )
                        )
                        if (match.matchedTerms.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.tavern_prompt_console_matched_terms,
                                    match.matchedTerms.joinToString(", "),
                                )
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.tavern_prompt_console_approx,
                            hit.approximateTokens,
                        )
                    )
                    if (expanded) {
                        HorizontalDivider()
                        Text(hit.content)
                    }
                }
            }
        }
    }
}
