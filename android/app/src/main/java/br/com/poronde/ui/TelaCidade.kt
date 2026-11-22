package br.com.poronde.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.dados.ApiCliente
import br.com.poronde.dados.Categoria
import br.com.poronde.dados.Cidade
import br.com.poronde.dados.Contexto
import br.com.poronde.dados.Foto
import br.com.poronde.ui.tema.Cores
import br.com.poronde.viagem.EstadoViagem
import br.com.poronde.viagem.Falas
import br.com.poronde.viagem.Posicao
import br.com.poronde.viagem.Viagem
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import br.com.poronde.BuildConfig
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Item já pronto para a lista (vem de /lugares ou dos destaques de /cidade). */
private data class ItemLugar(
    val titulo: String, val apoio: String?, val distanciaM: Double?,
    val lat: Double, val lon: Double,
)

private sealed interface Carga {
    data object Carregando : Carga
    data class Pronto(val itens: List<ItemLugar>) : Carga
    data object Falhou : Carga
}

@Composable
fun TelaCidade(estado: EstadoViagem, ctx: Contexto, largo: Boolean) {
    val cidade = estado.cidade?.takeIf { it.codigoIbge == ctx.municipio.codigoIbge }
    var categoria by rememberSaveable { mutableStateOf(Categoria.VER) }
    val carga = carregarLugares(estado.posicao, categoria, cidade)

    if (largo) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cabecalho(ctx, cidade)
                cidade?.foto?.let { FotoCidade(it) }
                cidade?.resumo?.let { Text(it, fontSize = 15.sp, color = Cores.TextoSuave, lineHeight = 22.sp) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                (ctx.clima ?: cidade?.clima)?.let { FaixaClima(it) }
                DadosCidade(ctx, cidade)
                ctx.proximaCidade?.let { CartaoProximaCidade(it, "Ao sair · próxima cidade") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AbasCategoria(categoria, { categoria = it })
                ListaLugares(carga)
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingTela,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Cabecalho(ctx, cidade) }
            cidade?.foto?.let { f -> item { FotoCidade(f) } }
            (ctx.clima ?: cidade?.clima)?.let { c -> item { FaixaClima(c) } }
            item { AbasCategoria(categoria, { categoria = it }) }
            when (carga) {
                is Carga.Pronto -> {
                    if (carga.itens.isEmpty()) item { TextoVazio() }
                    itemsIndexed(carga.itens) { i, item -> ItemDaLista(item, i) }
                }
                Carga.Carregando -> item { Carregando() }
                Carga.Falhou -> item { TextoFalha() }
            }
            cidade?.resumo?.let { r ->
                item { Text(r, fontSize = 15.sp, color = Cores.TextoSuave, lineHeight = 22.sp) }
            }
            item { DadosCidade(ctx, cidade) }
            ctx.proximaCidade?.let { p -> item { CartaoProximaCidade(p, "Ao sair · próxima cidade") } }
        }
    }
}

@Composable
private fun carregarLugares(posicao: Posicao?, categoria: Categoria, cidade: Cidade?): Carga {
    // Recarrega a cada ~1 km andado (grade de 0,01°), não a cada leitura do GPS
    val chave = posicao?.let { (it.lat * 100).roundToInt() to (it.lon * 100).roundToInt() }
    val destaques = if (categoria == Categoria.VER) cidade?.destaques.orEmpty() else emptyList()
    val carga by produceState<Carga>(Carga.Carregando, categoria, chave, destaques) {
        if (destaques.isNotEmpty()) {
            value = Carga.Pronto(
                destaques.map { ItemLugar(it.nome, tipoEmPortugues(it.tipo), it.distanciaM, it.lat, it.lon) },
            )
            return@produceState
        }
        if (posicao == null) return@produceState
        value = Carga.Carregando
        value = try {
            val lista = ApiCliente.lugares(posicao.lat, posicao.lon, categoria)
            Carga.Pronto(
                lista.map {
                    ItemLugar(
                        it.nome ?: tipoEmPortugues(it.tipo) ?: "Sem nome",
                        listOfNotNull(tipoEmPortugues(it.tipo), it.cozinha, it.horario).joinToString(" · "),
                        it.distanciaM,
                        it.lat,
                        it.lon,
                    )
                },
            )
        } catch (e: CancellationException) {
            throw e // cancelado (troca de aba/tela): não é falha
        } catch (e: Exception) {
            Carga.Falhou
        }
    }
    return carga
}

@Composable
private fun Cabecalho(ctx: Contexto, cidade: Cidade?) = Column {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Bem-vindo a", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Cores.Verde)
            Text(ctx.municipio.nome, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
            Text(
                cidade?.descricao ?: "${ctx.municipio.estado ?: ctx.municipio.uf}",
                fontSize = 14.sp, color = Cores.TextoSuave,
            )
        }
        if (cidade != null) {
            // Botão compacto (só o ícone) ao lado do nome: sobra espaço para o texto da cidade
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Cores.Superficie)
                    .border(1.dp, Cores.Borda, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Ouvir sobre ${cidade.nome}") {
                        Viagem.falar(Falas.sobreCidade(cidade))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Ouvir sobre ${cidade.nome}",
                    tint = Cores.Ambar, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private const val USER_AGENT_FOTOS =
    "PorOnde/${BuildConfig.VERSION_NAME} (Android; https://github.com/wbrcampos/por-onde)"

@Composable
private fun FotoCidade(foto: Foto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val contexto = LocalContext.current
        AsyncImage(
            // A Wikimedia recusa (403) o User-Agent padrão "okhttp/x.y" do Coil:
            // é preciso identificar o app (política de User-Agent da Wikimedia).
            model = remember(foto.url) {
                ImageRequest.Builder(contexto)
                    .data(foto.url)
                    .addHeader("User-Agent", USER_AGENT_FOTOS)
                    .crossfade(true)
                    .build()
            },
            contentDescription = "Foto da cidade",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp)),
        )
        val credito = listOfNotNull(foto.credito, foto.licenca).joinToString(" · ")
        Text(
            "Foto: ${credito.ifBlank { "Wikimedia Commons" }}",
            fontSize = 11.sp, color = Cores.TextoMudo,
        )
    }
}

@Composable
private fun DadosCidade(ctx: Contexto, cidade: Cidade?) {
    // Um card só, valores numa linha (sem quebra), relevo embaixo quando houver
    val populacao = (cidade?.populacao ?: ctx.municipio.populacao)?.let(::formatarPopulacao)
    Cartao {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DadoCompacto("Altitude", ctx.altitudeM?.let { "$it m" } ?: "—", Modifier.weight(0.8f))
            Divisoria()
            DadoCompacto("Habitantes", populacao ?: "—", Modifier.weight(1.1f))
            Divisoria()
            DadoCompacto("Bioma", ctx.bioma ?: "—", Modifier.weight(1.3f))
        }
        ctx.relevo?.nome?.let { relevo ->
            HorizontalDivider(color = Cores.Linha, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rotulo("Relevo")
                Text(
                    listOfNotNull(relevo, ctx.relevo.descricao).joinToString(" · "),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DadoCompacto(rotulo: String, valor: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Rotulo(rotulo)
        Text(
            valor, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Divisoria() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(Cores.Linha))
}

@Composable
private fun ItemDaLista(item: ItemLugar, indice: Int) {
    val contexto = LocalContext.current
    ItemLista(
        titulo = item.titulo,
        apoio = item.apoio,
        distancia = formatarDistancia(item.distanciaM),
        marcador = "${indice + 1}",
        onClick = { abrirRota(contexto, item.lat, item.lon, item.titulo) },
    )
}

@Composable
private fun ListaLugares(carga: Carga) {
    when (carga) {
        Carga.Carregando -> Carregando()
        Carga.Falhou -> TextoFalha()
        is Carga.Pronto -> {
            if (carga.itens.isEmpty()) TextoVazio()
            carga.itens.forEachIndexed { i, it -> ItemDaLista(it, i) }
        }
    }
}

@Composable
private fun Carregando() {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Cores.Ambar)
    }
}

@Composable
private fun TextoVazio() =
    Text("Nada cadastrado por perto nessa categoria.", fontSize = 14.sp, color = Cores.TextoMudo)

@Composable
private fun TextoFalha() =
    Text("Não consegui carregar agora. Tento de novo quando você andar.", fontSize = 14.sp, color = Cores.TextoMudo)

fun formatarPopulacao(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale("pt", "BR"), "%.1f mi", n / 1_000_000.0)
    n >= 1_000 -> "${(n / 1000.0).roundToInt()} mil"
    else -> "$n"
}
