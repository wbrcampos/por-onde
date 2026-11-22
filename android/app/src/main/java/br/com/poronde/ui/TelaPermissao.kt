package br.com.poronde.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.ui.tema.Cores

@Composable
fun TelaPermissao(onPermitir: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Cores.Fundo)
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(88.dp).background(Cores.AmbarFundo, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(44.dp))
        }
        Text("Por Onde", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Para contar o que tem por onde você passa, o app precisa da sua localização. " +
                "Ela é enviada ao servidor do app só para buscar as informações do lugar.",
            fontSize = 16.sp, color = Cores.TextoSuave, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        Text(
            "Também peço para mostrar uma notificação enquanto a viagem estiver ativa.",
            fontSize = 14.sp, color = Cores.TextoMudo, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        BotaoPrincipal("Permitir localização", Icons.Filled.LocationOn, onPermitir, Modifier.widthIn(min = 260.dp))
    }
}
