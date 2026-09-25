package app.lernet.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lernet.R
import app.lernet.routing.PatternSign
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.lernetButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CountryPicker(values: List<String>, onChange: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var recentRaw by rememberSaveable { mutableStateOf("") }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    val recent = recentRaw.split(',').filter { it.isNotEmpty() }
    val groups = CountryCatalog.groups(query, recent, CountryNames.all)
    val include = { code: String ->
        rememberCountry(code, recent, recentRaw) { recentRaw = it }
        onChange(GeoIpCodes.select(values, code))
    }
    Column(verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
        Text(
            stringResource(R.string.field_geoip_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val legacyPrivate = values.filter { PatternSign.body(it).equals(CountryCatalog.PRIVATE, ignoreCase = true) }
        if (legacyPrivate.isNotEmpty()) {
            PrivateNetworkPicker(legacyPrivate) { next ->
                onChange(values.filterNot { it in legacyPrivate } + next)
            }
        }
        SelectedCountryChips(values, onChange)
        TextButton(
            onClick = { catalogOpen = !catalogOpen },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Icon(LerNetSymbols.add(), contentDescription = null)
            Text(stringResource(if (catalogOpen) R.string.field_geo_hide_catalog else R.string.rule_add_country))
        }
        if (catalogOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.field_geo_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CountryCatalogList(groups, query, values, include)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCountryChips(values: List<String>, onChange: (List<String>) -> Unit) {
    val codes = values.map { PatternSign.body(it) }.filter { body ->
        body.isNotEmpty() && !body.equals(CountryCatalog.PRIVATE, ignoreCase = true)
    }.distinct()
    if (codes.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        codes.forEach { code ->
            val stored = stored(values, code) ?: return@forEach
            SignedChip(
                text = code.uppercase(),
                negated = PatternSign.negated(stored),
                onToggle = { onChange(GeoIpCodes.select(values, code)) },
                onRemove = { onChange(GeoIpCodes.clear(values, code)) },
            )
        }
    }
}

private fun rememberCountry(
    code: String,
    recent: List<String>,
    recentRaw: String,
    write: (String) -> Unit,
) {
    if (code.equals(CountryCatalog.PRIVATE, ignoreCase = true)) return
    val next = CountryCatalog.pin(recent, code)
    if (next != recentRaw) write(next)
}

@Composable
internal fun PrivateNetworkPicker(values: List<String>, onChange: (List<String>) -> Unit) {
    val stored = stored(values, CountryCatalog.PRIVATE)
    val name = stringResource(R.string.field_geo_private)
    Text(stringResource(R.string.field_geo_private_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.field_geo_private_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CountryChoice(name, stored) { onChange(GeoIpCodes.select(values, CountryCatalog.PRIVATE)) }
}

@Composable
private fun CountryChoice(
    label: String,
    stored: String?,
    onSelect: () -> Unit,
) {
    val negated = stored != null && PatternSign.negated(stored)
    val background = when {
        negated -> MaterialTheme.colorScheme.errorContainer
        stored != null -> LerNetOk.copy(alpha = 0.22f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Row(
        Modifier.fillMaxWidth().lernetButton().background(background, MaterialTheme.shapes.small).clickable(onClick = onSelect)
            .padding(horizontal = LerNetDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(when { stored == null -> "○"; negated -> "✕"; else -> "✓" },
            color = when { stored == null -> MaterialTheme.colorScheme.onSurfaceVariant; negated -> MaterialTheme.colorScheme.error; else -> LerNetOk })
    }
}

@Composable
private fun CountryCatalogList(
    groups: CountryGroups,
    query: String,
    values: List<String>,
    onSelect: (String) -> Unit,
) {
    if (query.isNotBlank() && groups.rest.isEmpty()) {
        Text(
            stringResource(R.string.field_geo_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val byCode = CountryNames.all.associateBy { it.code }
    val frequentTitle = stringResource(R.string.field_geo_frequent)
    val recentTitle = stringResource(R.string.field_geo_recent)
    val allTitle = stringResource(R.string.field_geo_all)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(LerNetDimens.catalogListHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        if (query.isBlank()) {
            countrySection(frequentTitle, groups.frequent, byCode, values, onSelect)
            countrySection(recentTitle, groups.recent, byCode, values, onSelect)
            countrySection(allTitle, groups.rest, byCode, values, onSelect)
        } else {
            countrySection(null, groups.rest, byCode, values, onSelect)
        }
    }
}

@Composable
private fun countrySection(
    title: String?,
    codes: List<String>,
    byCode: Map<String, CountryLabel>,
    values: List<String>,
    onSelect: (String) -> Unit,
) {
    if (codes.isEmpty()) return
    if (title != null) {
        CatalogHeading(title)
    }
    codes.forEach { code ->
        CountryLine(byCode.getValue(code), values) { onSelect(code) }
    }
}

@Composable
private fun CatalogHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CountryLine(
    label: CountryLabel,
    values: List<String>,
    onSelect: () -> Unit,
) {
    val stored = stored(values, label.code)
    val negated = stored != null && PatternSign.negated(stored)
    val background = if (stored != null) {
        if (negated) MaterialTheme.colorScheme.errorContainer else LerNetOk.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Row(
            Modifier
                .weight(1f)
                .lernetButton()
                .background(background, MaterialTheme.shapes.small)
                .clickable(onClick = onSelect)
                .padding(horizontal = LerNetDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(label.nameRu, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label.code.uppercase(), color = MaterialTheme.colorScheme.onSurface)
            Text(when { stored == null -> "○"; negated -> "✕"; else -> "✓" },
                color = when { stored == null -> MaterialTheme.colorScheme.onSurfaceVariant; negated -> MaterialTheme.colorScheme.error; else -> LerNetOk })
        }
    }
}

private fun stored(values: List<String>, code: String): String? =
    values.firstOrNull { PatternSign.body(it).equals(code, ignoreCase = true) }
