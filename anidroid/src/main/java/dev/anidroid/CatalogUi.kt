package dev.anidroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable fun CatalogControls(model: LibraryModel) {
    val keyboard=LocalSoftwareKeyboardController.current
    var statusExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("Catalog",style=MaterialTheme.typography.headlineMedium); Text("${model.catalogTotal} matching titles",style=MaterialTheme.typography.labelMedium) }
        TextButton(enabled=!model.busy && model.catalogConnection != null,onClick={model.browseCatalog()}) { Text("Refresh") }
    }
    if(model.catalogConnection == null) return
    OutlinedTextField(model.catalogQuery,{model.catalogQuery=it;model.catalogMore=false},Modifier.fillMaxWidth(),singleLine=true,label={Text("Search catalog")},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();model.browseCatalog()}),trailingIcon={TextButton(enabled=!model.busy,onClick={keyboard?.hide();model.browseCatalog()}){Text("Go")}})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        CatalogChoice("Source",model.catalogProvider,listOf("all" to "All sources","ani" to "Anime","luffy" to "Luffy"),Modifier.weight(1f)) { model.catalogProvider=it;model.catalogGenre="";model.browseCatalog() }
        CatalogChoice("Type",model.catalogKind,listOf("all" to "All types","movie" to "Movies","series" to "Series"),Modifier.weight(1f)) { model.catalogKind=it;model.catalogGenre="";model.browseCatalog() }
        CatalogChoice("Sort",model.catalogSort,listOf("name" to "A–Z","recent" to "Recently added"),Modifier.weight(1f)) { model.catalogSort=it;model.browseCatalog() }
    }
    CatalogChoice("Genre",model.catalogGenre,listOf("" to "All genres", "__untagged__" to "Uncategorized (${model.genreCoverage.optInt("untagged")})") + model.catalogGenres.map { it.first to "${it.first} (${it.second})" },Modifier.fillMaxWidth()) { model.catalogGenre=it;model.browseCatalog() }
    Text("${model.genreCoverage.optInt("tagged")} of ${model.genreCoverage.optInt("total")} titles categorized",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    val counts=model.catalogStatus.array("sources").objects().joinToString(" · ") { "${if(it.optString("provider")=="ani") "Anime" else "Luffy"}: ${it.optInt("count")}" }
    TextButton(onClick={statusExpanded=!statusExpanded}) { Text(counts.ifEmpty { "Indexing status" } + if(statusExpanded) " ▴" else " ▾") }
    if(statusExpanded) {
        Column(Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()).padding(bottom=8.dp)) {
            model.catalogStatus.array("feeds").objects().forEach { feed ->
                val progress=if(feed.optInt("totalPages")>0) "${feed.optInt("page")}/${feed.optInt("totalPages")} pages" else "${feed.optInt("page")} pages"
                val complete=if(feed.optLong("completedAt")>0) " · feed pass finished" else " · indexing"
                Text("${feed.optString("name")}: $progress$complete",style=MaterialTheme.typography.labelMedium)
                if(feed.optString("error").isNotEmpty()) Text(feed.getString("error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                else if(feed.optLong("lastSuccess")>0) Text("Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(feed.getLong("lastSuccess"))),style=MaterialTheme.typography.bodySmall)
            }
            Text("Listings may change; playback checks for a fresh stream. Luffy uses discovery metadata; stream availability is checked when you play.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
        }
    }
}
@Composable private fun CatalogChoice(label: String,value: String,values: List<Pair<String,String>>,modifier: Modifier,onChange: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick={expanded=true},contentPadding=PaddingValues(horizontal=8.dp)) { Text(values.firstOrNull { it.first==value }?.second ?: value,style=MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded,{expanded=false}) { values.forEach { (id,text) -> DropdownMenuItem(text={Text("$label: $text")},onClick={expanded=false;onChange(id)}) } }
    }
}
@Composable fun ServerDialog(model: LibraryModel) {
    var address by remember { mutableStateOf(model.catalogConnection?.url.orEmpty()) }
    var key by remember { mutableStateOf(model.catalogConnection?.token.orEmpty()) }
    var pin by remember { mutableStateOf(model.catalogConnection?.fingerprint.orEmpty()) }
    AlertDialog(onDismissRequest={if(!model.busy) model.showServerSettings=false},title={Text("Catalog server")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Connect to your catalog server. On your home network, keep the server computer running. Live search works without it.")
            OutlinedTextField(address,{address=it},label={Text("HTTPS address")},placeholder={Text("https://catalog.example.com")},singleLine=true)
            OutlinedTextField(key,{key=it},label={Text("Access key")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(pin,{pin=it},label={Text("Certificate fingerprint")},supportingText={Text("For a private server, use the SHA-256 fingerprint from its connection file. Leave blank only for a publicly trusted certificate.")})
            model.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(model.catalogConnection!=null) TextButton(enabled=!model.busy,onClick=model::disconnectCatalog) {Text("Disconnect")}
        }
    },confirmButton={TextButton(enabled=!model.busy && address.isNotBlank() && key.isNotBlank(),onClick={model.connectCatalog(CatalogConnection(address.trim(),key.trim(),pin.trim()))}){Text("Save & connect")}},dismissButton={TextButton(enabled=!model.busy,onClick={model.showServerSettings=false}){Text("Cancel")}})
}
