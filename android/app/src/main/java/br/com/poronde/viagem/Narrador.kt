package br.com.poronde.viagem

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import br.com.poronde.dados.Cidade
import br.com.poronde.dados.Contexto
import br.com.poronde.dados.ProximaCidade
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fala em português usando o motor de voz do próprio Android.
 * Usa o canal de "orientação de navegação": a música abaixa enquanto ele fala.
 */
class Narrador(contexto: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(contexto.applicationContext, this)
    private var pronto = false
    private val pendentes = mutableListOf<String>()
    private val contador = AtomicInteger()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setLanguage(Locale("pt", "BR"))
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        pronto = true
        pendentes.forEach(::falarAgora)
        pendentes.clear()
    }

    fun falar(texto: String) {
        if (pronto) falarAgora(texto) else pendentes += texto
    }

    private fun falarAgora(texto: String) {
        tts.speak(texto, TextToSpeech.QUEUE_ADD, null, "fala-${contador.incrementAndGet()}")
    }

    fun encerrar() {
        tts.stop()
        tts.shutdown()
    }
}

/** Textos falados. Curtos, para não distrair quem dirige. */
object Falas {

    fun chegada(ctx: Contexto, cidade: Cidade?): String = buildString {
        append("Você está chegando em ${ctx.municipio.nome}. ")
        primeiraFrase(cidade?.resumo)?.let { append(it).append(' ') }
        ctx.clima?.let { append("Agora faz ${it.temperatura} graus, ${it.condicao}.") }
    }

    fun saida(ctx: Contexto, saiuDe: String): String = buildString {
        append("Saindo de $saiuDe. ")
        ctx.proximaCidade?.let { append(proxima(it)) }
    }

    fun proxima(p: ProximaCidade): String = buildString {
        append("Próxima cidade: ${p.nome}, em cerca de ${tempo(p.minutos)}.")
        p.clima?.let { append(" Lá faz ${it.temperatura} graus, ${it.condicao}.") }
    }

    fun municipio(ctx: Contexto): String =
        "Você entrou no município de ${ctx.municipio.nome}."

    fun paisagem(bioma: String): String {
        val masculino = listOf("cerrado", "pampa", "pantanal").any { bioma.lowercase().startsWith(it) }
        return "A paisagem muda: você entrou ${if (masculino) "no" else "na"} $bioma."
    }

    fun sobreCidade(cidade: Cidade): String = buildString {
        append(cidade.nome).append(". ")
        append(cidade.resumo?.take(600) ?: cidade.descricao.orEmpty())
    }

    private fun tempo(min: Int): String = when {
        min < 2 -> "1 minuto"
        min < 60 -> "$min minutos"
        else -> {
            val h = min / 60
            val m = min % 60
            (if (h == 1) "1 hora" else "$h horas") + (if (m > 0) " e $m minutos" else "")
        }
    }

    private fun primeiraFrase(texto: String?): String? {
        if (texto.isNullOrBlank()) return null
        val fim = texto.indexOf(". ")
        return if (fim in 1..240) texto.substring(0, fim + 1) else texto.take(200)
    }
}
