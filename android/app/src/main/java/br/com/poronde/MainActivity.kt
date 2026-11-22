package br.com.poronde

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.poronde.ui.TelaPermissao
import br.com.poronde.ui.TelaPrincipal
import br.com.poronde.ui.tema.TemaPorOnde
import br.com.poronde.viagem.Viagem
import br.com.poronde.viagem.ViagemService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // O app é sempre escuro: ícones claros na barra de status mesmo com o sistema no modo claro
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // No suporte do carro a tela não deve apagar enquanto o app está aberto
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TemaPorOnde {
                var temPermissao by remember { mutableStateOf(temLocalizacao()) }
                val pedir = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { resultado ->
                    temPermissao = resultado[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        resultado[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                    if (temPermissao) ViagemService.iniciar(this)
                }

                if (!temPermissao) {
                    TelaPermissao(onPermitir = { pedir.launch(permissoes()) })
                } else {
                    val estado by Viagem.estado.collectAsStateWithLifecycle()
                    TelaPrincipal(
                        estado = estado,
                        onIniciar = { ViagemService.iniciar(this) },
                        onEncerrar = { ViagemService.parar(this) },
                    )
                }
            }
        }

        // Só na abertura do app (não ao girar a tela, para não reiniciar uma viagem encerrada)
        if (savedInstanceState == null && temLocalizacao() && !Viagem.estado.value.ativa) {
            ViagemService.iniciar(this)
        }
    }

    private fun temLocalizacao(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissoes(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
