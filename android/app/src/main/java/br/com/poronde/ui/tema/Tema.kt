package br.com.poronde.ui.tema

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Cores do protótipo: tema escuro para não ofuscar no carro. */
object Cores {
    val Fundo = Color(0xFF0E1311)
    val FundoBarra = Color(0xFF0B0F0D)
    val Superficie = Color(0xFF182019)
    val Superficie2 = Color(0xFF141B17)
    val Borda = Color(0xFF2A3D33)
    val Linha = Color(0xFF1F2A24)
    val Texto = Color(0xFFEEF2EE)
    val TextoSuave = Color(0xFFC5CFC9)
    val TextoMudo = Color(0xFF9DAAA2)
    val Ambar = Color(0xFFF4B740)
    val AmbarFundo = Color(0xFF2A2414)
    val AmbarBorda = Color(0xFF5A4A1E)
    val SobreAmbar = Color(0xFF1A1408)
    val Verde = Color(0xFF6FD3BC)
    val VerdeFundo = Color(0xFF13241F)
    val VerdeBorda = Color(0xFF24463B)
    val Inativo = Color(0xFF3A4A41)
}

private val esquema = darkColorScheme(
    primary = Cores.Ambar,
    onPrimary = Cores.SobreAmbar,
    secondary = Cores.Verde,
    onSecondary = Cores.Fundo,
    background = Cores.Fundo,
    onBackground = Cores.Texto,
    surface = Cores.Superficie,
    onSurface = Cores.Texto,
    surfaceVariant = Cores.Superficie2,
    onSurfaceVariant = Cores.TextoSuave,
    outline = Cores.Borda,
)

@Composable
fun TemaPorOnde(conteudo: @Composable () -> Unit) {
    MaterialTheme(colorScheme = esquema) {
        // Texto fora de um Surface usa LocalContentColor, que por padrão é preto:
        // no fundo escuro ficava ilegível. Claro por padrão em todo o app.
        CompositionLocalProvider(LocalContentColor provides Cores.Texto, content = conteudo)
    }
}
