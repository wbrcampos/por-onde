package br.com.poronde.viagem

import kotlinx.coroutines.Dispatchers
import br.com.poronde.dados.Cidade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.com.poronde.MainActivity
import br.com.poronde.R
import br.com.poronde.dados.ApiCliente
import br.com.poronde.dados.Contexto
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Acompanha a viagem: recebe o GPS, consulta a API, decide cidade/estrada,
 * registra o diário e narra as mudanças. Roda como serviço em primeiro plano
 * (com notificação), então continua funcionando com a tela apagada.
 */
class ViagemService : LifecycleService() {

    companion object {
        private const val CANAL = "viagem"
        private const val ID_NOTIFICACAO = 1
        private const val ACAO_PARAR = "br.com.poronde.PARAR"

        // Quando consultar a API de novo
        private const val DISTANCIA_MIN_M = 300f
        private const val INTERVALO_MAX_MS = 45_000L
        private const val INTERVALO_APOS_FALHA_MS = 10_000L // sem sinal: tenta de novo mais cedo

        fun iniciar(contexto: Context) {
            ContextCompat.startForegroundService(contexto, Intent(contexto, ViagemService::class.java))
        }

        fun parar(contexto: Context) {
            contexto.startService(Intent(contexto, ViagemService::class.java).setAction(ACAO_PARAR))
        }
    }

    private lateinit var gps: FusedLocationProviderClient
    private lateinit var narrador: Narrador
    private var ultimaConsulta: Location? = null
    private var ultimaConsultaMs = 0L
    private var consulta: Job? = null
    private var gpsLigado = false

    // Cidades já baixadas (a atual e a próxima), para a chegada sem sinal
    private val cidades = LinkedHashMap<Int, Cidade>()
    // Contexto restaurado do aparelho: só para mostrar, não conta como "antes" no diário
    private var contextoRestaurado = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(resultado: LocationResult) {
            resultado.lastLocation?.let(::novaPosicao)
        }
    }

    override fun onCreate() {
        super.onCreate()
        gps = LocationServices.getFusedLocationProviderClient(this)
        narrador = Narrador(this)
        criarCanal()
        lifecycleScope.launch {
            Viagem.pedidosDeFala.collect { narrador.falar(it) }
        }
        restaurarUltimosDados()
        // Parado (sem posição nova do GPS), nada dispara consulta: se a última falhou,
        // tenta de novo sozinho para a faixa "Sem sinal" sumir quando a internet voltar.
        lifecycleScope.launch {
            while (true) {
                delay(INTERVALO_APOS_FALHA_MS)
                val estado = Viagem.estado.value
                val posicao = estado.posicao
                if (estado.erro != null && posicao != null && consulta?.isActive != true) {
                    ultimaConsultaMs = System.currentTimeMillis()
                    consulta = launch { consultar(posicao) }
                }
            }
        }
    }

    /** Abre com o último lugar conhecido (útil ao abrir o app já sem sinal). */
    private fun restaurarUltimosDados() {
        if (Viagem.estado.value.contexto != null) return
        val salvo = Memoria.carregar(this) ?: return
        salvo.cidades.forEach { cidades[it.codigoIbge] = it }
        contextoRestaurado = true
        Viagem.atualizar {
            it.copy(
                contexto = salvo.contexto,
                cidade = cidades[salvo.contexto.municipio.codigoIbge],
                dadosDeMs = salvo.horaMs,
            )
        }
    }

    private fun guardarUltimosDados(c: Contexto) {
        val hora = System.currentTimeMillis()
        val lista = cidades.values.toList()
        lifecycleScope.launch(Dispatchers.IO) { Memoria.salvar(this@ViagemService, hora, c, lista) }
    }

    /** Baixa a próxima cidade enquanto ainda há sinal. */
    private fun adiantarProximaCidade(c: Contexto) {
        val codigo = c.proximaCidade?.codigoIbge ?: return
        if (codigo in cidades) return
        lifecycleScope.launch {
            try {
                cidades[codigo] = ApiCliente.cidade(codigo)
                guardarUltimosDados(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // tenta de novo na próxima consulta
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACAO_PARAR) {
            Viagem.encerrar()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ID_NOTIFICACAO, notificacao("Acompanhando a viagem…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        Viagem.atualizar { it.copy(ativa = true, erro = null) }
        ligarGps()
        return START_STICKY
    }

    override fun onDestroy() {
        gps.removeLocationUpdates(callback)
        narrador.encerrar()
        Viagem.encerrar()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // a MainActivity só inicia o serviço com a permissão concedida
    private fun ligarGps() {
        if (gpsLigado) return
        val pedido = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateDistanceMeters(25f)
            .build()
        try {
            gps.requestLocationUpdates(pedido, callback, Looper.getMainLooper())
            gpsLigado = true
        } catch (e: SecurityException) {
            Viagem.atualizar { it.copy(erro = "Sem permissão de localização") }
            stopSelf()
        }
    }

    private fun novaPosicao(loc: Location) {
        val andando = loc.hasSpeed() && loc.speed > 2f // > ~7 km/h
        val posicao = Posicao(
            lat = loc.latitude,
            lon = loc.longitude,
            rumo = if (andando && loc.hasBearing()) loc.bearing else null,
            velocidadeKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null,
            horaMs = loc.time,
        )
        Viagem.atualizar { it.copy(posicao = posicao) }

        val anterior = ultimaConsulta
        val agora = System.currentTimeMillis()
        val precisa = anterior == null ||
            anterior.distanceTo(loc) >= DISTANCIA_MIN_M ||
            agora - ultimaConsultaMs >= (if (Viagem.estado.value.erro != null) INTERVALO_APOS_FALHA_MS else INTERVALO_MAX_MS)
        if (precisa && consulta?.isActive != true) {
            ultimaConsulta = loc
            ultimaConsultaMs = agora
            consulta = lifecycleScope.launch { consultar(posicao) }
        }
    }

    private suspend fun consultar(p: Posicao) {
        Viagem.atualizar { it.copy(consultando = true) }
        val novo = try {
            ApiCliente.contexto(p.lat, p.lon, p.rumo, p.velocidadeKmh)
        } catch (e: CancellationException) {
            throw e // cancelado (troca de aba/tela): não é falha
        } catch (e: Exception) {
            Viagem.atualizar { it.copy(consultando = false, erro = e.message ?: "Sem conexão") }
            return
        }
        val anterior = if (contextoRestaurado) null else Viagem.estado.value.contexto
        contextoRestaurado = false
        Viagem.atualizar {
            val trecho = it.trecho?.seguir(novo.proximaCidade, novo.municipio.nome)
                ?: Trecho.novo(novo.proximaCidade, novo.municipio.nome)
            it.copy(
                contexto = novo, consultando = false, erro = null, trecho = trecho,
                dadosDeMs = System.currentTimeMillis(),
            )
        }
        reagirMudancas(anterior, novo)
        guardarUltimosDados(novo)
        adiantarProximaCidade(novo)
        atualizarNotificacao(novo)
    }

    /** Compara o contexto novo com o anterior: diário, narração e dados da cidade. */
    private suspend fun reagirMudancas(antes: Contexto?, agora: Contexto) {
        val hora = System.currentTimeMillis()
        val narrar = Viagem.estado.value.narracaoLigada

        if (antes == null) {
            Viagem.registrar(
                EventoDiario(hora, TipoEvento.INICIO, "Início da viagem", descrever(agora)),
            )
        }

        val chegou = agora.naCidade &&
            (antes == null || !antes.naCidade || antes.municipio.codigoIbge != agora.municipio.codigoIbge)
        val saiu = antes != null && antes.naCidade && !agora.naCidade

        if (chegou) {
            // Volta para o automático ao chegar numa cidade nova
            Viagem.escolher(Escolha.AUTO)
            val cidade = carregarCidade(agora.municipio.codigoIbge)
            Viagem.registrar(
                EventoDiario(hora, TipoEvento.CHEGADA, agora.municipio.nome, descrever(agora)),
            )
            if (narrar && antes != null) narrador.falar(Falas.chegada(agora, cidade))
        } else if (saiu) {
            Viagem.registrar(
                EventoDiario(
                    hora, TipoEvento.SAIDA, "Saiu de ${antes!!.municipio.nome}",
                    agora.proximaCidade?.let { "Próxima: ${it.nome} · ${it.minutos} min" } ?: "Na estrada",
                ),
            )
            if (narrar) narrador.falar(Falas.saida(agora, antes.municipio.nome))
        } else if (antes != null && !agora.naCidade &&
            antes.municipio.codigoIbge != agora.municipio.codigoIbge
        ) {
            Viagem.registrar(
                EventoDiario(hora, TipoEvento.MUNICIPIO, "Entrou em ${agora.municipio.nome}", descrever(agora)),
            )
            if (narrar) narrador.falar(Falas.municipio(agora))
        }

        val bioma = agora.bioma
        if (antes != null && bioma != null && antes.bioma != null && bioma != antes.bioma) {
            Viagem.registrar(
                EventoDiario(hora, TipoEvento.PAISAGEM, bioma, "Antes: ${antes.bioma}"),
            )
            if (narrar) narrador.falar(Falas.paisagem(bioma))
        }

        // Garante os dados da cidade do município atual (usados também no modo cidade manual)
        if (Viagem.estado.value.cidade?.codigoIbge != agora.municipio.codigoIbge) {
            carregarCidade(agora.municipio.codigoIbge)
        }
    }

    private suspend fun carregarCidade(codigo: Int): Cidade? {
        val cidade = try {
            ApiCliente.cidade(codigo).also { cidades[codigo] = it }
        } catch (e: CancellationException) {
            throw e // cancelado (troca de aba/tela): não é falha
        } catch (e: Exception) {
            cidades[codigo] // sem sinal: usa a que foi baixada antes (quando era a "próxima")
        }
        if (cidade != null) Viagem.atualizar { it.copy(cidade = cidade) }
        return cidade
    }

    private fun descrever(c: Contexto): String = listOfNotNull(
        c.altitudeM?.let { "$it m" },
        c.clima?.let { "${it.temperatura}° ${it.condicao}" },
        c.bioma,
    ).joinToString(" · ")

    // ---------------------------------------------------------------- notificação

    private fun criarCanal() {
        val canal = NotificationChannel(CANAL, getString(R.string.canal_viagem), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacao(texto: String) = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_pino)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(texto)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0, "Encerrar",
            PendingIntent.getService(
                this, 1, Intent(this, ViagemService::class.java).setAction(ACAO_PARAR),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    @SuppressLint("MissingPermission") // sem permissão de notificação, o sistema só ignora
    private fun atualizarNotificacao(c: Contexto) {
        val texto = if (c.naCidade) {
            "Em ${c.municipio.nome}"
        } else {
            "Na estrada · " + (c.proximaCidade?.let { "${it.nome} em ${it.minutos} min" } ?: c.municipio.nome)
        }
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACAO, notificacao(texto))
    }
}
