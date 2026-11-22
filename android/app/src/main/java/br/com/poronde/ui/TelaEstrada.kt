package br.com.poronde.ui

import androidx.compose.ui.unit.Dp
import br.com.poronde.viagem.EventoDiario
import br.com.poronde.viagem.Trecho
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
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
    val adiante = carregarAdiante(estado.posicao, online = estado.erro == null)

    if (largo) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Esquerda: o que mais importa dirigindo — a próxima cidade (sem rolagem)
            Column(
                Modifier.weight(1.1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProximaCidadeDestaque(ctx.proximaCidade, estado.trecho)
            }
            // Meio: onde estou (sem rolagem: "Na viagem" usa só o espaço que sobra)
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CabecalhoEstrada(ctx)
                FaixaEstrada(ctx)
                ctx.clima?.let { FaixaClima(it) }
                UltimosRegistros(estado.diario, Modifier.weight(1f))
            }
            // Direita: por perto, separado por tipo (a única coluna com rolagem)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ListaAdiante(adiante, estado.semSinal)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaddingTela),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CabecalhoEstrada(ctx)
            ProximaCidadeDestaque(ctx.proximaCidade, estado.trecho)
            FaixaEstrada(ctx)
            ctx.clima?.let { FaixaClima(it) }
            ListaAdiante(adiante, estado.semSinal)
            UltimosRegistros(estado.diario)
        }
    }
}

@Composable
private fun CabecalhoEstrada(ctx: Contexto) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Estado junto do rótulo: economiza uma linha na coluna
            Rotulo("Onde estou · ${ctx.municipio.estado ?: ctx.municipio.uf}", cor = Cores.Verde)
            val titulo = when (ctx.zona) {
                "povoado" -> "Povoado em ${ctx.municipio.nome}"
                else -> "Zona rural de ${ctx.municipio.nome}"
            }
            Text(titulo, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        }
        // A velocidade já aparece no topo da tela; aqui fica só o botão de ouvir
        BotaoOuvir("Ouvir sobre este trecho", onClick = { Viagem.falar(descricaoPaisagem(ctx)) })
    }
}

/** Altitude e vegetação numa faixa só; relevo embaixo quando houver (o clima tem card próprio). */
@Composable
private fun FaixaEstrada(ctx: Contexto) {
    Cartao {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
        ) {
            ItemFaixa("Altitude", ctx.altitudeM?.let { "$it m" } ?: "—", null, Modifier.weight(0.8f))
            LinhaVertical()
            ItemFaixa("Vegetação", ctx.bioma ?: "—", null, Modifier.weight(1.5f))
        }
        ctx.relevo?.let { r ->
            HorizontalDivider(color = Cores.Linha, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Relevo")
                Text(
                    listOfNotNull(r.nome, r.descricao?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ItemFaixa(rotulo: String, valor: String, apoio: String?, modifier: Modifier, tamanho: TextUnit = 20.sp) {
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Rotulo(rotulo)
        Text(valor, fontSize = tamanho, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (apoio != null) {
            Text(apoio, fontSize = 12.sp, color = Cores.TextoSuave, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LinhaVertical() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(Cores.Linha))
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

/**
 * Registros mais recentes do diário (o completo fica na aba Rota).
 * Com altura limitada (tablet), mostra só as linhas que cabem inteiras.
 */
@Composable
private fun UltimosRegistros(diario: List<EventoDiario>, modifier: Modifier = Modifier) {
    if (diario.isEmpty()) return
    val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    val alturaLinha = 46.dp
    Cartao(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Rotulo("Na viagem", modifier = Modifier.weight(1f))
            Text("completo na aba Rota", fontSize = 12.sp, color = Cores.TextoMudo)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cabem = if (maxHeight == Dp.Infinity) 3 else (maxHeight / alturaLinha).toInt().coerceIn(0, 5)
            Column {
                diario.takeLast(cabem).asReversed().forEachIndexed { i, e ->
                    Row(
                        Modifier.fillMaxWidth().height(alturaLinha),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (i == 0) Cores.Ambar else Cores.Verde))
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.titulo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = if (i == 0) Cores.Ambar else Cores.Texto,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (e.detalhe.isNotBlank()) {
                                Text(e.detalhe, fontSize = 12.sp, color = Cores.TextoMudo, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(hora.format(Date(e.horaMs)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = Cores.TextoSuave)
                    }
                }
            }
        }
    }
}

/** Barra com um carrinho mostrando quanto do trecho até a próxima cidade já foi feito. */
@Composable
private fun BarraTrecho(t: Trecho, destino: String, kmAtual: Double) {
    val progresso = t.progresso(kmAtual)
    val animado by animateFloatAsState(progresso, animationSpec = tween(800), label = "progresso")
    val feitos = (t.kmInicial - kmAtual).coerceAtLeast(0.0)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
            val icone = 28.dp
            // trilho
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(10.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Cores.Inativo),
            )
            // parte percorrida
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(animado.coerceAtLeast(0.02f)).height(10.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Cores.Ambar),
            )
            // o carro anda sobre a barra
            Box(
                Modifier.offset(x = (maxWidth - icone) * animado).size(icone)
                    .clip(CircleShape).background(Cores.Ambar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = Cores.SobreAmbar,
                    modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.origem, fontSize = 13.sp, color = Cores.TextoMudo, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                "${formatarKm(feitos)} de ${formatarKm(t.kmInicial)} km",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cores.TextoSuave,
            )
            Text(destino, fontSize = 13.sp, color = Cores.TextoMudo, maxLines = 1, textAlign = TextAlign.End,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

private fun formatarKm(km: Double): String =
    if (km < 10) String.format(Locale("pt", "BR"), "%.1f", km) else km.roundToInt().toString()

/** Próxima cidade em destaque: distância e tempo grandes, chegada e clima de lá. */
@Composable
private fun ProximaCidadeDestaque(p: ProximaCidade?, trecho: Trecho?) {
    if (p == null) {
        Cartao {
            Rotulo("Próxima cidade")
            Text(
                "Nenhuma cidade à frente nos próximos 80 km.",
                fontSize = 16.sp, color = Cores.TextoSuave, lineHeight = 22.sp,
            )
        }
        return
    }
    val chegada = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        .format(Date(System.currentTimeMillis() + p.minutos * 60_000L))
    Cartao(borda = Cores.AmbarBorda, padding = 18.dp) {
        Rotulo("Próxima cidade", cor = Cores.Ambar)
        Text(p.nome, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NumeroGrande(formatarKm(p.distanciaEstradaKm), "km")
            NumeroGrande("${p.minutos}", "min")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(20.dp))
            Text("chega às $chegada", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        trecho?.takeIf { it.codigoDestino == p.codigoIbge }?.let { t ->
            BarraTrecho(t, p.nome, p.distanciaEstradaKm)
        }
        p.clima?.let { c ->
            HorizontalDivider(color = Cores.Linha, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Thermostat, contentDescription = null, tint = Cores.Ambar, modifier = Modifier.size(20.dp))
                Text("${c.temperatura}°", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(c.condicao, c.minima?.let { m -> c.maxima?.let { "$m°–$it°" } }).joinToString(" · "),
                    fontSize = 15.sp, color = Cores.TextoSuave, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (p.estimado) Text("Tempo estimado pela distância", fontSize = 11.sp, color = Cores.TextoMudo)
    }
}

@Composable
private fun NumeroGrande(valor: String, unidade: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(valor, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Cores.Ambar, lineHeight = 56.sp)
        Text(unidade, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cores.TextoSuave,
            modifier = Modifier.padding(bottom = 8.dp))
    }
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

// -------------------------------------------------------- por perto

/** Grupos na ordem de prioridade na estrada: abastecer, comer, ver. */
private val GRUPOS = listOf(Categoria.ABASTECER to 3, Categoria.COMER to 3, Categoria.VER to 2)

// Em "ver", obras de arte do OSM são quase sempre placas, grafites ou cadastros errados
private val TIPOS_IGNORADOS_ESTRADA = setOf("artwork")

@Composable
private fun carregarAdiante(posicao: Posicao?, online: Boolean): Map<Categoria, List<Lugar>>? {
    // Recarrega a cada ~2 km (grade de 0,02°)
    val chave = posicao?.let { (it.lat * 50).roundToInt() to (it.lon * 50).roundToInt() }
    // "online" na chave: quando a internet volta, busca de novo
    val grupos by produceState<Map<Categoria, List<Lugar>>?>(null, chave, online) {
        if (posicao == null) return@produceState
        value = try {
            GRUPOS.associate { (cat, n) ->
                cat to ApiCliente.lugares(posicao.lat, posicao.lon, cat, raioM = 15_000, limite = n + 6)
                    .filter { it.tipo !in TIPOS_IGNORADOS_ESTRADA }
                    .take(n)
            }
        } catch (e: CancellationException) {
            throw e // cancelado (troca de aba/tela): não é falha
        } catch (e: Exception) {
            value // mantém a lista anterior se a conexão falhar
        }
    }
    return grupos
}

@Composable
private fun ListaAdiante(grupos: Map<Categoria, List<Lugar>>?, semSinal: Boolean) {
    val contexto = LocalContext.current
    Rotulo("Por perto (até 15 km)")
    when {
        grupos == null && semSinal ->
            Text("Sem sinal: os lugares aparecem quando a internet voltar.", fontSize = 14.sp, color = Cores.TextoMudo)
        grupos == null -> Text("Buscando…", fontSize = 14.sp, color = Cores.TextoMudo)
        grupos.values.all { it.isEmpty() } ->
            Text("Nada cadastrado por perto.", fontSize = 14.sp, color = Cores.TextoMudo)
        else -> GRUPOS.forEach { (cat, _) ->
            val lista = grupos[cat].orEmpty()
            if (lista.isEmpty()) return@forEach
            Text(
                cat.rotulo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Cores.Verde,
                modifier = Modifier.padding(top = 6.dp),
            )
            lista.forEach { l ->
                val titulo = l.nome ?: tipoEmPortugues(l.tipo) ?: "Sem nome"
                ItemLista(
                    titulo = titulo,
                    // sem nome, o título já é o tipo: não repete embaixo
                    apoio = if (l.nome != null) tipoEmPortugues(l.tipo) else null,
                    distancia = formatarDistancia(l.distanciaM),
                    icone = iconeCategoria(cat),
                    onClick = { abrirRota(contexto, l.lat, l.lon, titulo) },
                )
            }
        }
    }
}
