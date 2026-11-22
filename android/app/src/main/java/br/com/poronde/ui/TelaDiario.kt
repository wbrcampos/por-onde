package br.com.poronde.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.ui.tema.Cores
import br.com.poronde.viagem.EstadoViagem
import br.com.poronde.viagem.EventoDiario
import br.com.poronde.viagem.TipoEvento
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diário da rota: vai sendo montado sozinho conforme você passa pelos lugares. */
@Composable
fun TelaDiario(estado: EstadoViagem, largo: Boolean, onEncerrar: (() -> Unit)? = null) {
    val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    val eventos = estado.diario
    val cidades = eventos.count { it.tipo == TipoEvento.CHEGADA }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = if (largo) 48.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Rotulo("Diário da rota")
                Text("Por onde você passou", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (eventos.isEmpty()) "Os lugares aparecem aqui conforme a viagem acontece."
                    else "$cidades ${if (cidades == 1) "cidade" else "cidades"} · ${eventos.size} registros",
                    fontSize = 14.sp, color = Cores.TextoSuave,
                )
            }
        }
        val proxima = estado.contexto?.proximaCidade
        itemsIndexed(eventos) { i, e ->
            LinhaDiario(
                e, hora.format(Date(e.horaMs)),
                atual = i == eventos.lastIndex,
                ultimo = i == eventos.lastIndex && proxima == null,
            )
        }
        proxima?.let { p ->
            item {
                LinhaDiario(
                    EventoDiario(0, TipoEvento.CHEGADA, p.nome, "Próxima cidade · ${p.distanciaEstradaKm.toInt()} km"),
                    "~${hora.format(Date(System.currentTimeMillis() + p.minutos * 60_000L))}",
                    atual = false, ultimo = true, futuro = true,
                )
            }
        }
        if (onEncerrar != null && estado.ativa) {
            item {
                BotaoSecundario(
                    "Encerrar viagem", Icons.Filled.Stop, onEncerrar,
                    Modifier.padding(vertical = 20.dp).widthIn(min = 220.dp),
                )
            }
        }
    }
}

@Composable
private fun LinhaDiario(e: EventoDiario, hora: String, atual: Boolean, ultimo: Boolean, futuro: Boolean = false) {
    val cor = when {
        atual -> Cores.Ambar
        futuro -> Cores.Inativo
        else -> Cores.Verde
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(if (atual) 16.dp else 12.dp)
                    .background(if (futuro) Cores.Fundo else cor, CircleShape)
                    .border(2.dp, cor, CircleShape),
            )
            if (!ultimo) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(if (futuro || atual) Cores.Linha else Cores.Verde),
                )
            }
        }
        Row(Modifier.weight(1f).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    e.titulo,
                    fontSize = if (atual) 17.sp else 15.sp,
                    fontWeight = if (atual) FontWeight.Bold else FontWeight.SemiBold,
                    color = when {
                        atual -> Cores.Ambar
                        futuro -> Cores.TextoSuave
                        else -> Cores.Texto
                    },
                )
                if (e.detalhe.isNotBlank()) Text(e.detalhe, fontSize = 13.sp, color = Cores.TextoMudo)
            }
            Text(hora, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cores.TextoSuave)
        }
    }
}
