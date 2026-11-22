package br.com.poronde.dados

import br.com.poronde.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiErro(val codigo: Int, mensagem: String) : IOException(mensagem)

/** Cliente da API Por Onde (endereço em app/build.gradle.kts → API_URL). */
object ApiCliente {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val base = BuildConfig.API_URL.trimEnd('/')

    suspend fun contexto(lat: Double, lon: Double, rumo: Float?, velocidadeKmh: Float?): Contexto {
        val texto = buscar(
            "/contexto",
            "lat" to lat, "lon" to lon,
            "rumo" to rumo?.let { ((it % 360f) + 360f) % 360f },
            "velocidade" to velocidadeKmh,
        )
        return json.decodeFromString(Contexto.serializer(), texto)
    }

    suspend fun cidade(codigoIbge: Int): Cidade =
        json.decodeFromString(Cidade.serializer(), buscar("/cidade/$codigoIbge"))

    suspend fun lugares(
        lat: Double, lon: Double, categoria: Categoria, raioM: Int = 5000, limite: Int = 20,
    ): List<Lugar> {
        val texto = buscar(
            "/lugares",
            "lat" to lat, "lon" to lon, "cat" to categoria.chave,
            "raio_m" to raioM, "limite" to limite,
        )
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Lugar.serializer()), texto,
        )
    }

    private suspend fun buscar(caminho: String, vararg params: Pair<String, Any?>): String =
        withContext(Dispatchers.IO) {
            val url = (base + caminho).toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
            }.build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val corpo = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = when (r.code) {
                        404 -> "Lugar fora da área coberta pela API"
                        else -> "Erro ${r.code} na API"
                    }
                    throw ApiErro(r.code, msg)
                }
                corpo
            }
        }
}
