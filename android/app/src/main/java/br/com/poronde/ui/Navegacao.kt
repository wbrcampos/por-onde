package br.com.poronde.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

private const val PACOTE_MAPS = "com.google.android.apps.maps"
private const val PACOTE_WAZE = "com.waze"

/**
 * Abre um app de navegação já com a rota de carro até o lugar.
 *
 * - Google Maps: entra direto no modo navegação.
 * - Waze: se também estiver instalado, o Android pergunta qual usar.
 * - Sem nenhum dos dois: qualquer app de mapas (geo:) ou, por fim, o navegador.
 *
 * Os pacotes do Maps e do Waze estão em <queries> no AndroidManifest (exigido
 * a partir do Android 11 para saber se estão instalados).
 */
fun abrirRota(contexto: Context, lat: Double, lon: Double, nome: String?) {
    val destino = "$lat,$lon"
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destino&mode=d"))
        .setPackage(PACOTE_MAPS)
    val waze = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$destino&navigate=yes"))
        .setPackage(PACOTE_WAZE)
    val pm = contexto.packageManager
    val instalados = listOf(maps, waze).filter { it.resolveActivity(pm) != null }

    val rotulo = Uri.encode(nome ?: "Destino")
    val intent = when (instalados.size) {
        0 -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:$destino?q=$destino($rotulo)"))
        1 -> instalados[0]
        else -> Intent.createChooser(instalados[0], "Abrir rota com")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, instalados.drop(1).toTypedArray())
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        contexto.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        val web = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destino&travelmode=driving")
        try {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(contexto, "Nenhum app de mapas encontrado", Toast.LENGTH_SHORT).show()
        }
    }
}
