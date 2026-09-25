package app.lernet.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.composables.icons.materialsymbols.outlined.R as SymbolR

object LerNetSymbols {
    @Composable
    fun add(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_add_outlined)

    @Composable
    fun arrowBack(): Painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack)

    @Composable
    fun check(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_check_outlined)

    @Composable
    fun chevronRight(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_chevron_right_outlined)

    @Composable
    fun expandMore(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_expand_more_outlined)

    @Composable
    fun paste(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_content_paste_outlined)

    @Composable
    fun power(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_power_settings_new_outlined)

    @Composable
    fun close(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_close_outlined)

    @Composable
    fun copy(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_content_copy_outlined)

    @Composable
    fun delete(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_delete_outlined)

    @Composable
    fun download(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_download_outlined)

    @Composable
    fun drag(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_drag_indicator_outlined)

    @Composable
    fun edit(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_edit_outlined)

    @Composable
    fun menu(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_menu_outlined)

    @Composable
    fun more(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_more_vert_outlined)

    @Composable
    fun help(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_help_outlined)

    @Composable
    fun info(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_info_outlined)

    @Composable
    fun analytics(): Painter = painterResource(app.lernet.R.drawable.ic_analytics)

    @Composable
    fun route(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_route_outlined)

    @Composable
    fun pan(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_pan_tool_outlined)

    @Composable
    fun swapVert(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_swap_vert_outlined)

    @Composable
    fun probe(): Painter = rememberVectorPainter(Icons.Default.SyncAlt)

    @Composable
    fun autoSwap(): Painter = rememberVectorPainter(Icons.Default.Autorenew)

    @Composable
    fun apps(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_apps_outlined)

    @Composable
    fun settings(): Painter = rememberVectorPainter(Icons.Outlined.Settings)

    @Composable
    fun share(): Painter = painterResource(SymbolR.drawable.materialsymbols_ic_share_outlined)
}
