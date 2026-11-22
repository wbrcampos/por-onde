package br.com.poronde.ui

import android.os.Bundle
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Mapa escuro (tons de cinza) do OpenFreeMap — vetorial, grátis, sem chave e com
 * uso comercial permitido. Dados © OpenMapTiles © colaboradores do OpenStreetMap
 * (o botão "i" do mapa mostra o crédito, exigido pela licença).
 */
private const val ESTILO_MAPA = "https://tiles.openfreemap.org/styles/dark"
private const val FONTE_POSICAO = "posicao"
private const val FONTE_DESTINO = "destino"
private const val ZOOM = 15.5 // nomes de ruas aparecem a partir daqui

/**
 * Mapa que mostra onde o carro está, acompanhando o GPS (sem gestos: uso no carro).
 * Com [destino] (ex.: próxima cidade), enquadra o carro e o destino juntos.
 */
@Composable
fun MapaPosicao(
    lat: Double,
    lon: Double,
    modifier: Modifier = Modifier,
    zoom: Double = ZOOM,
    destino: LatLng? = null,
) {
    val contexto = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(contexto)
        MapView(contexto).apply { onCreate(Bundle()) }
    }
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    var estilo by remember { mutableStateOf<Style?>(null) }

    // O MapView precisa acompanhar o ciclo de vida da tela
    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo, mapView) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        ciclo.addObserver(observador)
        if (ciclo.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (ciclo.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            ciclo.removeObserver(observador)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                // crédito (OpenMapTiles/OpenStreetMap) no canto inferior direito
                m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.END
                m.uiSettings.setAttributionMargins(0, 0, 16, 16)
                m.uiSettings.setAttributionTintColor(0xFF9DAAA2.toInt())
                m.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
                m.setStyle(Style.Builder().fromUri(ESTILO_MAPA)) { st ->
                    st.addSource(GeoJsonSource(FONTE_POSICAO, Point.fromLngLat(lon, lat)))
                    // destino (próxima cidade): ponto claro com borda, sob o ponto do carro
                    st.addSource(GeoJsonSource(FONTE_DESTINO))
                    st.addLayer(
                        CircleLayer("destino-ponto", FONTE_DESTINO).withProperties(
                            circleRadius(7f), circleColor("#EEF2EE"),
                            circleStrokeColor("#6FD3BC"), circleStrokeWidth(3f),
                        ),
                    )
                    // halo + ponto âmbar (cor de destaque do app)
                    st.addLayer(
                        CircleLayer("posicao-halo", FONTE_POSICAO).withProperties(
                            circleRadius(18f), circleColor("#F4B740"), circleOpacity(0.22f),
                        ),
                    )
                    st.addLayer(
                        CircleLayer("posicao-ponto", FONTE_POSICAO).withProperties(
                            circleRadius(8f), circleColor("#F4B740"),
                            circleStrokeColor("#1A1408"), circleStrokeWidth(3f),
                        ),
                    )
                    estilo = st
                }
                mapa = m
            }
            mapView
        },
        modifier = modifier,
    )

    // Move os pontos e a câmera quando a posição (ou o destino) muda
    LaunchedEffect(lat, lon, destino, estilo) {
        val st = estilo ?: return@LaunchedEffect
        val m = mapa ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(FONTE_POSICAO)?.setGeoJson(Point.fromLngLat(lon, lat))
        val fonteDestino = st.getSourceAs<GeoJsonSource>(FONTE_DESTINO)
        if (destino != null) {
            fonteDestino?.setGeoJson(Point.fromLngLat(destino.longitude, destino.latitude))
            val caixa = LatLngBounds.Builder().include(LatLng(lat, lon)).include(destino).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(caixa, 90), 800)
        } else {
            fonteDestino?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 800)
        }
    }
}
