package br.com.poronde.viagem

import android.content.Context
import br.com.poronde.dados.ApiCliente
import br.com.poronde.dados.Cidade
import br.com.poronde.dados.Contexto
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Últimos dados recebidos da API, guardados no aparelho para a estrada sem sinal:
 * ao abrir o app sem internet, a tela mostra o último lugar conhecido; ao chegar
 * numa cidade sem internet, os dados dela já foram baixados antes (a próxima cidade
 * é buscada assim que vira a "próxima").
 */
object Memoria {

    @Serializable
    data class Salvo(
        val horaMs: Long,
        val contexto: Contexto,
        val cidades: List<Cidade> = emptyList(),
    )

    private const val ARQUIVO = "ultimo_contexto.json"
    private const val MAX_CIDADES = 6

    fun carregar(ctx: Context): Salvo? = try {
        File(ctx.filesDir, ARQUIVO).takeIf { it.exists() }
            ?.readText()
            ?.let { ApiCliente.json.decodeFromString(Salvo.serializer(), it) }
    } catch (e: Exception) {
        null // arquivo de versão antiga ou corrompido: ignora
    }

    fun salvar(ctx: Context, horaMs: Long, contexto: Contexto, cidades: Collection<Cidade>) {
        try {
            val salvo = Salvo(horaMs, contexto, cidades.toList().takeLast(MAX_CIDADES))
            val tmp = File(ctx.filesDir, "$ARQUIVO.tmp")
            tmp.writeText(ApiCliente.json.encodeToString(Salvo.serializer(), salvo))
            tmp.renameTo(File(ctx.filesDir, ARQUIVO))
        } catch (e: Exception) {
            // sem espaço/permissão: só perde o cache, o app segue
        }
    }
}
