# Regras do R8 (só valem se isMinifyEnabled = true no build de release).
# kotlinx.serialization: manter os serializers gerados dos modelos da API.
-keepclassmembers class br.com.poronde.dados.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
