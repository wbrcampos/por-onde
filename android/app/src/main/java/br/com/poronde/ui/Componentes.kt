package br.com.poronde.ui

import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Directions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.poronde.dados.Categoria
import br.com.poronde.dados.Clima
import br.com.poronde.ui.tema.Cores
import kotlin.math.roundToInt

/** Rótulo pequeno em maiúsculas ("ALTITUDE", "PRÓXIMA CIDADE"). */
@Composable
fun Rotulo(texto: String, cor: Color = Cores.TextoMudo, modifier: Modifier = Modifier) {
    Text(
        texto.uppercase(), color = cor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, modifier = modifier,
    )
}

@Composable
fun Cartao(
    modifier: Modifier = Modifier,
    fundo: Color = Cores.Superficie,
    borda: Color? = null,
    padding: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    conteudo: @Composable ColumnScope.() -> Unit,
) {
    val forma = RoundedCornerShape(18.dp)
    Surface(
        color = fundo,
        shape = forma,
        border = borda?.let { BorderStroke(1.dp, it) },
        modifier = modifier
            .clip(forma)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp), content = conteudo)
    }
}

/** Bloco de dado: rótulo, valor grande e uma linha de apoio. */
@Composable
fun Dado(
    rotulo: String,
    valor: String,
    apoio: String? = null,
    modifier: Modifier = Modifier,
    tamanhoValor: TextUnit = 28.sp,
    corApoio: Color = Cores.TextoSuave,
) {
    Cartao(modifier) {
        Rotulo(rotulo)
        Text(valor, fontSize = tamanhoValor, fontWeight = FontWeight.Bold, lineHeight = tamanhoValor * 1.15f)
        if (apoio != null) Text(apoio, fontSize = 13.sp, color = corApoio)
    }
}

@Composable
fun BotaoPrincipal(
    texto: String,
    icone: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Cores.Ambar, contentColor = Cores.SobreAmbar, shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icone != null) Icon(icone, contentDescription = null)
            Text(texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BotaoSecundario(texto: String, icone: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Cores.Superficie, contentColor = Cores.Texto, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Cores.Borda),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icone != null) Icon(icone, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Faixa com o clima das próximas horas. */
@Composable
fun FaixaClima(clima: Clima, modifier: Modifier = Modifier) {
    Cartao(modifier) {
        Rotulo("Clima nas próximas horas")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ColunaHora(
                "Agora", "${clima.temperatura}°", clima.condicao,
                clima.proximasHoras.firstOrNull()?.chanceChuva, destaque = true,
            )
            clima.proximasHoras.drop(1).take(3).forEach { h ->
                ColunaHora(h.hora, "${h.temperatura}°", h.condicao, h.chanceChuva)
            }
        }
        val extremos = listOfNotNull(
            clima.minima?.let { "mín $it°" }, clima.maxima?.let { "máx $it°" },
            clima.chanceChuva?.let { "chuva $it%" },
        )
        if (extremos.isNotEmpty()) Text(extremos.joinToString(" · "), fontSize = 13.sp, color = Cores.TextoSuave)
    }
}

@Composable
private fun RowScope.ColunaHora(
    hora: String, temp: String, condicao: String, chuva: Int?, destaque: Boolean = false,
) {
    // Ícone no lugar do texto ("parcialmente nublado" não cabia e cortava)
    Column(
        Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(hora, fontSize = 12.sp, color = Cores.TextoMudo)
        Icon(
            iconeTempo(condicao), contentDescription = condicao,
            tint = if (destaque) Cores.Ambar else Cores.TextoSuave, modifier = Modifier.size(26.dp),
        )
        Text(temp, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (destaque) Cores.Ambar else Cores.Texto)
        // Chance de chuva só quando vale a pena avisar
        Text(
            chuva?.takeIf { it >= 10 }?.let { "chuva $it%" } ?: " ",
            fontSize = 11.sp, color = Cores.Verde, maxLines = 1,
        )
    }
}

/** Ícone para a condição do tempo (textos vêm da API, a partir dos códigos WMO). */
fun iconeTempo(condicao: String): ImageVector {
    val c = condicao.lowercase()
    return when {
        "trovoada" in c -> Icons.Filled.Thunderstorm
        "gelada" in c -> Icons.Filled.AcUnit
        "chuva" in c || "pancada" in c -> Icons.Filled.Umbrella
        "garoa" in c -> Icons.Filled.Grain
        "neblina" in c -> Icons.Filled.Dehaze
        "parcialmente" in c -> Icons.Filled.FilterDrama
        "nublado" in c -> Icons.Filled.Cloud
        else -> Icons.Filled.WbSunny // sol, céu limpo
    }
}

/** Linha de lista: número/ícone, nome, apoio e distância. */
@Composable
fun ItemLista(
    titulo: String,
    apoio: String?,
    distancia: String?,
    marcador: String? = null,
    icone: ImageVector? = null,
    destaque: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .background(if (destaque) Cores.AmbarFundo else Cores.Superficie2, RoundedCornerShape(14.dp))
            .border(1.dp, if (destaque) Cores.AmbarBorda else Cores.Linha, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(if (destaque) Cores.Ambar else Cores.Superficie, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                icone != null -> Icon(
                    icone, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (destaque) Cores.SobreAmbar else Cores.Verde,
                )
                marcador != null -> Text(marcador, fontWeight = FontWeight.Bold, color = Cores.Verde)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!apoio.isNullOrBlank()) {
                Text(apoio, fontSize = 12.sp, color = Cores.TextoMudo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (distancia != null) {
            Text(distancia, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Cores.TextoSuave)
        }
        if (onClick != null) {
            Icon(
                Icons.Filled.Directions, contentDescription = "Abrir rota",
                modifier = Modifier.size(22.dp), tint = Cores.Ambar,
            )
        }
    }
}

/** Abas grandes de categoria (Ver, Comer, Dormir, Abastecer). */
@Composable
fun AbasCategoria(selecionada: Categoria, onSelecionar: (Categoria) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Categoria.entries.forEach { c ->
            val ativa = c == selecionada
            Surface(
                color = if (ativa) Cores.AmbarFundo else Cores.Superficie,
                contentColor = if (ativa) Cores.Ambar else Cores.TextoSuave,
                border = BorderStroke(1.dp, if (ativa) Cores.Ambar else Cores.Borda),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 68.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Tab, onClick = { onSelecionar(c) }),
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(iconeCategoria(c), contentDescription = null)
                    Text(c.rotulo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

fun iconeCategoria(c: Categoria): ImageVector = when (c) {
    Categoria.VER -> Icons.Filled.Star
    Categoria.COMER -> Icons.Filled.Restaurant
    Categoria.DORMIR -> Icons.Filled.Hotel
    Categoria.ABASTECER -> Icons.Filled.LocalGasStation
}

/** "350 m" ou "4,2 km". */
fun formatarDistancia(metros: Double?): String? {
    if (metros == null) return null
    return if (metros < 1000) "${(metros / 10).roundToInt() * 10} m"
    else String.format(java.util.Locale("pt", "BR"), "%.1f km", metros / 1000)
}

/** Tipos do OpenStreetMap em português. */
fun tipoEmPortugues(tipo: String?): String? = when (tipo) {
    null -> null
    "restaurant" -> "Restaurante"
    "cafe" -> "Café"
    "fast_food" -> "Lanchonete"
    "food_court" -> "Praça de alimentação"
    "ice_cream" -> "Sorveteria"
    "hotel" -> "Hotel"
    "motel" -> "Motel"
    "guest_house" -> "Pousada"
    "hostel" -> "Albergue"
    "chalet" -> "Chalé"
    "camp_site" -> "Camping"
    "apartment" -> "Apartamento"
    "fuel" -> "Posto de combustível"
    "charging_station" -> "Carregador elétrico"
    "tyres" -> "Borracharia"
    "car_repair" -> "Oficina"
    "attraction" -> "Atração"
    "viewpoint" -> "Mirante"
    "museum" -> "Museu"
    "artwork" -> "Obra de arte"
    "gallery" -> "Galeria"
    "theme_park" -> "Parque"
    "zoo" -> "Zoológico"
    "monument" -> "Monumento"
    "memorial" -> "Memorial"
    "castle" -> "Castelo"
    "ruins" -> "Ruínas"
    "archaeological_site" -> "Sítio arqueológico"
    "fort" -> "Forte"
    "nature_reserve" -> "Reserva natural"
    else -> tipo.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun Espaco(altura: Dp) = Spacer(Modifier.padding(top = altura))

@Composable
fun EspacoH(largura: Dp) = Spacer(Modifier.width(largura))

val PaddingTela = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** Botão redondo só com o alto-falante (48 dp: dá para tocar com o carro andando). */
@Composable
fun BotaoOuvir(descricao: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Cores.Superficie)
            .border(1.dp, Cores.Borda, CircleShape)
            .clickable(role = Role.Button, onClickLabel = descricao, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp, contentDescription = descricao,
            tint = Cores.Ambar, modifier = Modifier.size(22.dp),
        )
    }
}
