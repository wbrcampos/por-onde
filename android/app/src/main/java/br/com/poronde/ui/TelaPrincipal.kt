package br.com.poronde.ui

import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.ui.tema.Cores
import br.com.poronde.viagem.Escolha
import br.com.poronde.viagem.EstadoViagem
import br.com.poronde.viagem.Modo
import br.com.poronde.viagem.Viagem

private enum class Aba { AGORA, ROTA }

@Composable
fun TelaPrincipal(estado: EstadoViagem, onIniciar: () -> Unit, onEncerrar: () -> Unit) {
    var aba by rememberSaveable { mutableStateOf(Aba.AGORA) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Cores.Fundo)) {
        val largo = maxWidth >= 720.dp // tablet ou celular deitado

        Column(Modifier.fillMaxSize()) {
            BarraTopo(estado, largo, onEncerrar)
            if (estado.ativa && aba == Aba.AGORA) AvisoSemSinal(estado)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val contexto = estado.contexto
                when {
                    !estado.ativa -> TelaParada(onIniciar)
                    aba == Aba.ROTA -> TelaDiario(estado, largo, onEncerrar)
                    contexto == null -> TelaAguardando(estado.erro)
                    estado.modo == Modo.CIDADE -> TelaCidade(estado, contexto, largo)
                    else -> TelaEstrada(estado, contexto, largo)
                }
            }

            NavigationBar(
                containerColor = Cores.FundoBarra,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                ItemNav(aba == Aba.AGORA, Icons.Filled.Explore, "Agora") { aba = Aba.AGORA }
                ItemNav(aba == Aba.ROTA, Icons.Filled.Timeline, "Rota") { aba = Aba.ROTA }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ItemNav(
    selecionado: Boolean, icone: ImageVector, rotulo: String, onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selecionado,
        onClick = onClick,
        icon = { Icon(icone, contentDescription = null) },
        label = { Text(rotulo, fontWeight = FontWeight.SemiBold) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Cores.Ambar,
            selectedTextColor = Cores.Ambar,
            indicatorColor = Cores.AmbarFundo,
            unselectedIconColor = Cores.TextoMudo,
            unselectedTextColor = Cores.TextoMudo,
        ),
    )
}

@Composable
private fun BarraTopo(estado: EstadoViagem, largo: Boolean, onEncerrar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = "Por Onde", tint = Cores.Ambar, modifier = Modifier.size(26.dp))
        if (largo) {
            Text("Por Onde", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            estado.posicao?.velocidadeKmh?.let {
                Text("· ${it.toInt()} km/h", fontSize = 15.sp, color = Cores.TextoMudo)
            }
        }
        Box(Modifier.weight(1f))
        if (estado.ativa) {
            SeletorModo(estado, largo)
            IconButton(onClick = { Viagem.alternarNarracao() }) {
                Icon(
                    if (estado.narracaoLigada) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (estado.narracaoLigada) "Desligar narração" else "Ligar narração",
                    tint = if (estado.narracaoLigada) Cores.Ambar else Cores.TextoMudo,
                )
            }
            if (largo) {
                IconButton(onClick = onEncerrar) {
                    Icon(Icons.Filled.Close, contentDescription = "Encerrar viagem", tint = Cores.TextoMudo)
                }
            }
        }
    }
}

/** Seletor Cidade | Estrada com a marca AUTO quando o GPS está decidindo. */
@Composable
private fun SeletorModo(estado: EstadoViagem, largo: Boolean) {
    Surface(
        color = Cores.Superficie,
        border = BorderStroke(1.dp, Cores.Borda),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmento("Cidade", Icons.Filled.LocationCity, estado.modo == Modo.CIDADE, largo) {
                Viagem.escolher(if (estado.modoAuto == Modo.CIDADE) Escolha.AUTO else Escolha.CIDADE)
            }
            Segmento("Estrada", Icons.Filled.Explore, estado.modo == Modo.ESTRADA, largo) {
                Viagem.escolher(if (estado.modoAuto == Modo.ESTRADA) Escolha.AUTO else Escolha.ESTRADA)
            }
            val auto = estado.escolha == Escolha.AUTO
            Text(
                "AUTO",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (auto) Cores.Verde else Cores.TextoMudo,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { Viagem.escolher(Escolha.AUTO) }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Segmento(rotulo: String, icone: ImageVector, ativo: Boolean, largo: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (ativo) Cores.Ambar else Cores.Superficie)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (largo) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val cor = if (ativo) Cores.SobreAmbar else Cores.TextoSuave
        Icon(icone, contentDescription = null, tint = cor, modifier = Modifier.size(if (largo) 20.dp else 17.dp))
        Text(rotulo, color = cor, fontSize = if (largo) 15.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TelaAguardando(erro: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (erro == null) {
            CircularProgressIndicator(color = Cores.Ambar)
            Text("Procurando onde você está…", fontSize = 18.sp, color = Cores.TextoSuave)
        } else {
            Text("Não consegui buscar os dados", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(erro, fontSize = 15.sp, color = Cores.TextoSuave, textAlign = TextAlign.Center)
            Text("Tento de novo sozinho em alguns segundos.", fontSize = 14.sp, color = Cores.TextoMudo)
        }
    }
}

@Composable
private fun TelaParada(onIniciar: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(88.dp).background(Cores.AmbarFundo, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(44.dp))
        }
        Text("Viagem encerrada", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Inicie quando for pegar a estrada. O app acompanha o caminho e conta o que tem por onde você passa.",
            fontSize = 16.sp, color = Cores.TextoSuave, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        BotaoPrincipal("Iniciar viagem", Icons.Filled.PlayArrow, onIniciar, Modifier.widthIn(min = 240.dp))
    }
}

/**
 * Faixa discreta quando os dados na tela não são de agora: sem sinal (a última
 * consulta falhou) ou dados salvos de antes de abrir o app, ainda atualizando.
 */
@Composable
private fun AvisoSemSinal(estado: EstadoViagem) {
    val de = estado.dadosDeMs ?: return
    if (estado.contexto == null) return
    val antigo = System.currentTimeMillis() - de > 3 * 60_000L
    if (!estado.semSinal && !antigo) return
    val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(de))
    val texto = if (estado.semSinal) "Sem sinal · mostrando os dados das $hora" else "Dados das $hora · atualizando…"
    Row(
        Modifier.fillMaxWidth().background(Cores.AmbarFundo).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.SignalCellularConnectedNoInternet0Bar, contentDescription = null,
            tint = Cores.Ambar, modifier = Modifier.size(18.dp),
        )
        Text(texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Cores.Ambar)
    }
}
