# Por Onde — app Android

App que acompanha a viagem de carro e mostra o que tem por onde você passa.
Kotlin + Jetpack Compose, ligado à API em `https://vps70367.publiccloud.com.br/por-onde/`.

## O que esta versão faz

- **Modo cidade e modo estrada automáticos**, pelo GPS e pela API (`/contexto`).
  O seletor **Cidade | Estrada** troca na hora; o **AUTO** volta para o automático,
  que também é retomado sozinho ao chegar numa cidade nova.
- **Modo cidade:** boas-vindas, foto com crédito, clima das próximas horas, abas
  **Ver · Comer · Dormir · Abastecer** (dados de `/cidade` e `/lugares`), resumo,
  altitude, população, bioma e a próxima cidade ao sair.
- **Modo estrada:** zona rural ou povoado, altitude, clima, relevo, vegetação,
  "agora na paisagem", **próxima cidade com tempo, horário de chegada e clima**,
  e postos, restaurantes e atrativos até 15 km.
- **Diário da rota** (aba Rota): monta sozinho a linha do tempo da viagem — início,
  chegadas, saídas, mudança de município e de bioma — e mostra a próxima cidade prevista.
- **Narração por voz** em português (botão de alto-falante no topo): ao chegar numa
  cidade, ao sair, ao mudar de município e de bioma. A música abaixa enquanto fala.
- **Continua com a tela apagada**: um serviço em primeiro plano com notificação
  ("Na estrada · Bezerros em 9 min"), com botão **Encerrar**.
- **Celular e tablet**: em telas largas (≥ 720 dp, como tablet ou celular deitado)
  o conteúdo vai para colunas lado a lado.
- A tela não apaga enquanto o app está aberto (para uso no suporte do carro).

## Como compilar

**Pelo GitHub (sem instalar nada):** cada push que mexe em `android/` roda o workflow
`.github/workflows/android.yml`. Na aba **Actions**, abra a execução mais recente e baixe
o artifact **por-onde-debug-apk**. Instale o APK no celular (permitir "fontes desconhecidas").

**No Android Studio:** abra a pasta `android/` (não a raiz do repositório), espere o
Gradle sincronizar e rode no celular ou no emulador.

## Testar sem sair de casa

No emulador do Android Studio: **⋮ → Location → Routes**, trace uma rota (ex.: Recife →
Caruaru pela BR-232) e aperte **Play route**. O app vai trocando de modo, preenchendo o
diário e narrando como se você estivesse viajando.

## Estrutura

```
app/src/main/java/br/com/poronde/
├── MainActivity.kt          permissões e tela principal
├── dados/                   modelos da API e cliente HTTP (OkHttp + kotlinx.serialization)
├── viagem/
│   ├── Viagem.kt            estado da viagem (StateFlow) compartilhado entre serviço e telas
│   ├── ViagemService.kt     GPS, consultas à API, diário, narração e notificação
│   └── Narrador.kt          voz (TextToSpeech) e textos falados
└── ui/                      telas em Compose: cidade, estrada, diário, permissão, componentes
```

## Detalhes

- A API é consultada quando o carro anda 300 m ou a cada 45 s (o que vier primeiro).
- O endereço da API está em `app/build.gradle.kts` (`API_URL`).
- Fontes dos dados: IBGE, OpenStreetMap (© colaboradores do OpenStreetMap),
  Open-Meteo (CC BY 4.0) e Wikipedia/Wikimedia Commons (CC BY-SA).

## Próximos passos

- Guardar o diário no celular (Room) para rever viagens antigas.
- Pré-carregar os dados da rota para trechos sem sinal.
- Android Auto.
