package br.com.poronde.dados

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Modelos das respostas da API Por Onde (mesmos nomes de campo do JSON).

@Serializable
data class Municipio(
    @SerialName("codigo_ibge") val codigoIbge: Int,
    val nome: String,
    val uf: String,
    val estado: String? = null,
    val populacao: Int? = null,
)

@Serializable
data class Relevo(
    val nome: String,
    val descricao: String? = null,
)

@Serializable
data class HoraClima(
    val hora: String,
    val temperatura: Int,
    val condicao: String,
    @SerialName("chance_chuva") val chanceChuva: Int? = null,
)

@Serializable
data class Clima(
    val temperatura: Int,
    val condicao: String,
    val codigo: Int? = null,
    val minima: Int? = null,
    val maxima: Int? = null,
    @SerialName("chance_chuva") val chanceChuva: Int? = null,
    @SerialName("proximas_horas") val proximasHoras: List<HoraClima> = emptyList(),
)

@Serializable
data class ProximaCidade(
    @SerialName("codigo_ibge") val codigoIbge: Int,
    val nome: String,
    val uf: String,
    val lat: Double? = null,   // centro da cidade (APIs antigas não mandam)
    val lon: Double? = null,
    @SerialName("distancia_reta_km") val distanciaRetaKm: Double,
    @SerialName("distancia_estrada_km") val distanciaEstradaKm: Double,
    val minutos: Int,
    val estimado: Boolean = true,
    val clima: Clima? = null,
)

/** Resposta de GET /contexto — a chamada principal, feita enquanto o carro anda. */
@Serializable
data class Contexto(
    val modo: String,                 // "cidade" ou "estrada"
    val municipio: Municipio,
    val zona: String,                 // "urbana", "povoado" ou "rural"
    @SerialName("altitude_m") val altitudeM: Int? = null,
    val bioma: String? = null,
    val relevo: Relevo? = null,
    val clima: Clima? = null,
    @SerialName("proxima_cidade") val proximaCidade: ProximaCidade? = null,
    val fontes: List<String> = emptyList(),
) {
    val naCidade: Boolean get() = modo == "cidade"
}

@Serializable
data class Foto(
    val url: String,
    val credito: String? = null,
    val licenca: String? = null,
)

@Serializable
data class Destaque(
    val nome: String,
    val tipo: String? = null,
    val lat: Double,
    val lon: Double,
    @SerialName("distancia_m") val distanciaM: Double? = null,
)

@Serializable
data class Artigo(
    val titulo: String,
    @SerialName("distancia_m") val distanciaM: Double? = null,
    val url: String,
)

/** Resposta de GET /cidade/{codigo} — dados do modo cidade. */
@Serializable
data class Cidade(
    @SerialName("codigo_ibge") val codigoIbge: Int,
    val nome: String,
    val uf: String,
    val estado: String? = null,
    val populacao: Int? = null,
    val descricao: String? = null,
    val resumo: String? = null,
    val foto: Foto? = null,
    @SerialName("wiki_url") val wikiUrl: String? = null,
    val destaques: List<Destaque> = emptyList(),
    @SerialName("artigos_proximos") val artigosProximos: List<Artigo> = emptyList(),
    val clima: Clima? = null,
    val fontes: List<String> = emptyList(),
)

/** Item de GET /lugares. */
@Serializable
data class Lugar(
    val nome: String? = null,
    val tipo: String? = null,
    val lat: Double,
    val lon: Double,
    @SerialName("distancia_m") val distanciaM: Double,
    val horario: String? = null,
    val telefone: String? = null,
    val cozinha: String? = null,
)

enum class Categoria(val chave: String, val rotulo: String) {
    VER("ver", "Ver"),
    COMER("comer", "Comer"),
    DORMIR("dormir", "Dormir"),
    ABASTECER("abastecer", "Abastecer"),
}
