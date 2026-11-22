package br.com.poronde.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.dados.ApiCliente
import br.com.poronde.dados.Categoria
import br.com.poronde.dados.Contexto
import br.com.poronde.dados.Lugar
import androidx.compose.ui.platform.LocalContext
import br.com.poronde.dados.ProximaCidade
import br.com.poronde.ui.tema.Cores
import br.com.poronde.viagem.EstadoViagem
import br.com.poronde.viagem.Posicao
import br.com.poronde.viagem.Viagem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TelaEstrada(estado: EstadoViagem, ctx: Contexto, largo: Boolean) {
    val adiante = carregarAdiante(estado.posicao)

    if (largo) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CabecalhoEstrada(estado, ctx)
                GradeDados(ctx)
            }
            Column(
                Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CartaoPaisagem(ctx)
                ctx.proximaCidade?.let { CartaoProximaCidade(it, "Próxima cidade", grande = true) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ListaAdiante(adiante)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaddingTela),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CabecalhoEstrada(estado, ctx)
            ctx.proximaCidade?.let { CartaoProximaCidade(it, "Próxima cidade") }
            CartaoPaisagem(ctx)
            GradeDados(ctx)
            ListaAdiante(adiante)
        }
    }
}

@Composable
private fun CabecalhoEstrada(estado: EstadoViagem, ctx: Contexto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Rotulo("Na estrada", cor = Cores.Verde)
        val titulo = when (ctx.zona) {
            "povoado" -> "Povoado em ${ctx.municipio.nome}"
            else -> "Zona rural de ${ctx.municipio.nome}"
        }
        Text(titulo, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
        val linha = listOfNotNull(
            ctx.municipio.estado ?: ctx.municipio.uf,
            estado.posicao?.velocidadeKmh?.takeIf { it > 5 }?.let { "${it.roundToInt()} km/h" },
        ).joinToString(" · ")
        Text(linha, fontSize = 15.sp, color = Cores.TextoSuave)
    }
}

@Composable
private fun GradeDados(ctx: Contexto) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dado(
                "Altitude", ctx.altitudeM?.let { "$it m" } ?: "—",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Dado(
                "Clima", ctx.clima?.let { "${it.temperatura}°" } ?: "—",
                apoio = ctx.clima?.condicao,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dado("Relevo", ctx.relevo?.nome ?: "—", modifier = Modifier.weight(1f).fillMaxHeight(), tamanhoValor = 16.sp)
            Dado("Vegetação", ctx.bioma ?: "—", modifier = Modifier.weight(1f).fillMaxHeight(), tamanhoValor = 16.sp)
        }
    }
}

@Composable
private fun CartaoPaisagem(ctx: Contexto) {
    Cartao(fundo = Cores.VerdeFundo, borda = Cores.VerdeBorda) {
        Rotulo("Agora na paisagem", cor = Cores.Verde)
        Text(
            ctx.relevo?.nome ?: ctx.bioma ?: ctx.municipio.nome,
            fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp,
        )
        Text(descricaoPaisagem(ctx), fontSize = 14.sp, color = Cores.TextoSuave, lineHeight = 20.sp)
        BotaoSecundario(
            "Ouvir sobre este trecho", Icons.AutoMirrored.Filled.VolumeUp,
            onClick = { Viagem.falar(descricaoPaisagem(ctx)) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun descricaoPaisagem(ctx: Contexto): String = buildString {
    append(if (ctx.zona == "povoado") "Povoado em " else "Zona rural de ")
    append(ctx.municipio.nome).append('.')
    ctx.altitudeM?.let { append(" Altitude de $it metros.") }
    ctx.relevo?.let { r ->
        append(" Relevo: ${r.nome}")
        r.descricao?.takeIf { it.isNotBlank() }?.let { append(", ${it.lowercase()}") }
        append('.')
    }
    ctx.bioma?.let { append(" Vegetação de $it.") }
}

/** Cartão da próxima cidade: distância, tempo, horário de chegada e clima de lá. */
@Composable
fun CartaoProximaCidade(p: ProximaCidade, titulo: String, grande: Boolean = false) {
    val chegada = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        .format(Date(System.currentTimeMillis() + p.minutos * 60_000L))
    Cartao {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Rotulo(titulo)
                Text(p.nome, fontSize = if (grande) 32.sp else 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "${p.distanciaEstradaKm.roundToInt()} km",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cores.TextoSuave,
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cartao(Modifier.weight(1f).fillMaxHeight(), fundo = Cores.Fundo, padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(22.dp))
                    Column {
                        Text("${p.minutos} min", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cores.Ambar)
                        Text("chega às $chegada", fontSize = 12.sp, color = Cores.TextoSuave)
                    }
                }
            }
            p.clima?.let { c ->
                Cartao(Modifier.weight(1f).fillMaxHeight(), fundo = Cores.Fundo, padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Thermostat, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(22.dp))
                        Column {
                            Text("${c.temperatura}°", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(c.condicao, c.minima?.let { m -> c.maxima?.let { "$m°–$it°" } })
                                    .joinToString(" · "),
                                fontSize = 12.sp, color = Cores.TextoSuave,
                            )
                        }
                    }
                }
            }
        }
        if (p.estimado) Text("Tempo estimado pela distância", fontSize = 11.sp, color = Cores.TextoMudo)
    }
}

// -------------------------------------------------------- nos próximos km

private data class Adiante(val lugar: Lugar, val categoria: Categoria)

@Composable
private fun carregarAdiante(posicao: Posicao?): List<Adiante>? {
    // Recarrega a cada ~2 km (grade de 0,02°)
    val chave = posicao?.let { (it.lat * 50).roundToInt() to (it.lon * 50).roundToInt() }
    val lista by produceState<List<Adiante>?>(null, chave) {
        if (posicao == null) return@produceState
        value = try {
            val postos = ApiCliente.lugares(posicao.lat, posicao.lon, Categoria.ABASTECER, raioM = 15_000, limite = 3)
            val comer = ApiCliente.lugares(posicao.lat, posicao.lon, Categoria.COMER, raioM = 15_000, limite = 2)
            val mirantes = ApiCliente.lugares(posicao.lat, posicao.lon, Categoria.VER, raioM = 15_000, limite = 2)
            (postos.map { Adiante(it, Categoria.ABASTECER) } +
                comer.map { Adiante(it, Categoria.COMER) } +
                mirantes.map { Adiante(it, Categoria.VER) })
                .sortedBy { it.lugar.distanciaM }
        } catch (e: CancellationException) {
            throw e // cancelado (troca de aba/tela): não é falha
        } catch (e: Exception) {
            value // mantém a lista anterior se a conexão falhar
        }
    }
    return lista
}

@Composable
private fun ListaAdiante(itens: List<Adiante>?) {
    val contexto = LocalContext.current
    Rotulo("Por perto (até 15 km)")
    when {
        itens == null -> Text("Buscando…", fontSize = 14.sp, color = Cores.TextoMudo)
        itens.isEmpty() -> Text("Nada cadastrado por perto.", fontSize = 14.sp, color = Cores.TextoMudo)
        else -> itens.forEach { a ->
            val titulo = a.lugar.nome ?: tipoEmPortugues(a.lugar.tipo) ?: "Sem nome"
            ItemLista(
                titulo = titulo,
                apoio = tipoEmPortugues(a.lugar.tipo),
                distancia = formatarDistancia(a.lugar.distanciaM),
                icone = iconeCategoria(a.categoria),
                onClick = { abrirRota(contexto, a.lugar.lat, a.lugar.lon, titulo) },
            )
        }
    }
}
