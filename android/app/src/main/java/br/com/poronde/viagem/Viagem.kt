package br.com.poronde.viagem

import br.com.poronde.dados.Cidade
import br.com.poronde.dados.Contexto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Modo { CIDADE, ESTRADA }

/** O que o usuário escolheu no seletor: automático (pelo GPS) ou um modo fixo. */
enum class Escolha { AUTO, CIDADE, ESTRADA }

data class Posicao(
    val lat: Double,
    val lon: Double,
    val rumo: Float?,           // graus, 0 = norte (só quando o carro está andando)
    val velocidadeKmh: Float?,
    val horaMs: Long,
)

enum class TipoEvento { INICIO, CHEGADA, SAIDA, MUNICIPIO, PAISAGEM }

data class EventoDiario(
    val horaMs: Long,
    val tipo: TipoEvento,
    val titulo: String,
    val detalhe: String,
)

data class EstadoViagem(
    val ativa: Boolean = false,
    val posicao: Posicao? = null,
    val contexto: Contexto? = null,
    val cidade: Cidade? = null,
    val escolha: Escolha = Escolha.AUTO,
    val diario: List<EventoDiario> = emptyList(),
    val narracaoLigada: Boolean = true,
    val consultando: Boolean = false,
    val erro: String? = null,
) {
    /** Modo que o GPS indica. */
    val modoAuto: Modo get() = if (contexto?.naCidade == true) Modo.CIDADE else Modo.ESTRADA

    /** Modo mostrado na tela (o automático, a não ser que o usuário tenha escolhido outro). */
    val modo: Modo
        get() = when (escolha) {
            Escolha.AUTO -> modoAuto
            Escolha.CIDADE -> Modo.CIDADE
            Escolha.ESTRADA -> Modo.ESTRADA
        }
}

/**
 * Estado da viagem compartilhado entre o serviço de GPS (que escreve) e as telas (que leem).
 */
object Viagem {
    private val _estado = MutableStateFlow(EstadoViagem())
    val estado: StateFlow<EstadoViagem> = _estado.asStateFlow()

    // Pedidos de fala vindos das telas (ex.: botão "Ouvir sobre Bezerros")
    private val _falar = MutableSharedFlow<String>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pedidosDeFala: SharedFlow<String> = _falar.asSharedFlow()

    fun atualizar(f: (EstadoViagem) -> EstadoViagem) = _estado.update(f)

    fun escolher(escolha: Escolha) = _estado.update { it.copy(escolha = escolha) }

    fun alternarNarracao() = _estado.update { it.copy(narracaoLigada = !it.narracaoLigada) }

    fun registrar(evento: EventoDiario) = _estado.update { it.copy(diario = it.diario + evento) }

    fun falar(texto: String) {
        if (texto.isNotBlank()) _falar.tryEmit(texto)
    }

    fun encerrar() = _estado.update {
        it.copy(ativa = false, consultando = false)
    }
}
