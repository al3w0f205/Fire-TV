# FireAirPlay — Documentación del Proyecto

> **Receptor AirPlay 1 (RAOP) para Amazon Fire TV y Android TV**
> Implementación completa en Kotlin puro — sin dependencias nativas (NDK).

---

## Tabla de Contenidos

1. [Resumen del Proyecto](#resumen-del-proyecto)
2. [Arquitectura General](#arquitectura-general)
3. [Flujo de Datos Completo](#flujo-de-datos-completo)
4. [Estructura de Archivos](#estructura-de-archivos)
5. [Capa de Red y Protocolo](#capa-de-red-y-protocolo)
6. [Capa de Audio](#capa-de-audio)
7. [Capa de UI](#capa-de-ui)
8. [Modelo de Datos](#modelo-de-datos)
9. [Servicio en Primer Plano](#servicio-en-primer-plano)
10. [Recursos Android](#recursos-android)
11. [Dependencias](#dependencias)
12. [Configuración de Build](#configuración-de-build)
13. [Guía de Desarrollo](#guía-de-desarrollo)
14. [Problemas Conocidos y Limitaciones](#problemas-conocidos-y-limitaciones)

---

## Resumen del Proyecto

FireAirPlay convierte un Amazon Fire TV Stick (o cualquier dispositivo Android TV) en un **receptor AirPlay de audio**. Los dispositivos Apple (iPhone, iPad, Mac) pueden enviar música directamente al televisor vía AirPlay, como si fuera un HomePod o AirPort Express.

### Características Principales

- 📡 **Descubrimiento automático** vía mDNS/Bonjour — aparece en el menú AirPlay de iOS
- 🔐 **Descifrado AES-128-CBC** de audio encriptado + autenticación RSA Apple-Challenge
- 🎵 **Decodificación ALAC** (Apple Lossless) en Kotlin puro, sin código nativo
- 🖼️ **Metadatos y artwork** — título, artista, álbum, carátula del álbum
- 📊 **Progreso en tiempo real** — posición y duración de la canción
- 🎨 **UI Liquid Glass** — interfaz premium con blur, gradientes animados y panel de vidrio esmerilado
- 🔊 **Control de volumen** remoto desde el dispositivo Apple

---

## Arquitectura General

```
┌─────────────────────────────────────────────────────────────────┐
│                     Dispositivo Apple (iPhone)                  │
│                                                                 │
│  1. Descubre el servicio via mDNS (_raop._tcp)                 │
│  2. Conecta via RTSP al puerto 5000                            │
│  3. Negocia sesión (ANNOUNCE → SETUP → RECORD)                 │
│  4. Envía audio encriptado via UDP (RTP)                       │
│  5. Envía metadatos via RTSP SET_PARAMETER                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │ WiFi
┌───────────────────────────▼─────────────────────────────────────┐
│                     Fire TV / Android TV                        │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   AirPlayService                          │   │
│  │              (Foreground Service)                          │   │
│  │                                                           │   │
│  │  ┌─────────────────────┐  ┌─────────────────────────┐    │   │
│  │  │ AirPlayService      │  │ RaopServer               │    │   │
│  │  │ Registrar (mDNS)    │  │ (RTSP + UDP)             │    │   │
│  │  │                     │  │                           │    │   │
│  │  │ • Registra           │  │ • Maneja RTSP commands   │    │   │
│  │  │   _raop._tcp        │  │ • Descifra AES audio     │    │   │
│  │  │ • Multicast Lock    │  │ • Parsea metadatos DMAP  │    │   │
│  │  └─────────────────────┘  │ • Recibe paquetes RTP    │    │   │
│  │                           └──────────┬────────────────┘    │   │
│  │                                      │                     │   │
│  │  ┌──────────────────┐  ┌─────────────▼──────────────┐     │   │
│  │  │ AlacDecoder      │  │ AudioPlayer                 │     │   │
│  │  │                  │◄─┤                             │     │   │
│  │  │ • Rice decoding  │  │ • AudioTrack (44100Hz)     │     │   │
│  │  │ • LPC prediction │  │ • Channel buffer (100)     │     │   │
│  │  │ • Stereo unmix   │  │ • Coroutine playback       │     │   │
│  │  └──────────────────┘  └─────────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   MainActivity                            │   │
│  │                (Now Playing UI)                            │   │
│  │                                                           │   │
│  │  ┌───────────────────┐  ┌──────────────────────────┐     │   │
│  │  │ NowPlayingVM      │  │ UI Components             │     │   │
│  │  │                   │  │                            │     │   │
│  │  │ • metadata LD     │  │ • BlurHelper              │     │   │
│  │  │ • status LD       │  │ • AnimatedGradientView    │     │   │
│  │  │ • artwork LD      │  │ • GlassPanelLayout       │     │   │
│  │  │ • progress ticker │  │ • CardView (album art)    │     │   │
│  │  └───────────────────┘  └──────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Patrón de Comunicación

```
RaopServer (hilo IO) ──callback──► AirPlayService ──callback──► NowPlayingViewModel ──LiveData──► MainActivity
```

Los callbacks son funciones lambda estáticas en `AirPlayService.Companion`. El ViewModel usa `postValue()` para cruzar del hilo de fondo al hilo principal de forma segura.

---

## Flujo de Datos Completo

### 1. Inicio de la App

```
MainActivity.onCreate()
  → setupImmersiveMode()         // Pantalla completa
  → setContentView()             // Infla el layout XML
  → bindViews()                  // Encuentra referencias a Views
  → ViewModelProvider()          // Crea NowPlayingViewModel
  → observeViewModel()           // Suscribe a LiveData
  → startAirPlayService()        // Lanza foreground service
      → AirPlayService.onCreate()
          → AlacDecoder.initializeDefault()   // Config: 44100Hz, 16bit, stereo
          → AudioPlayer()                      // Instancia (no inicializada aún)
          → AirPlayServiceRegistrar()          // Instancia mDNS
          → RaopServer(audioPlayer, alacDecoder)  // Crea servidor RAOP
      → AirPlayService.onStartCommand()
          → createNotificationChannel()        // Android 8+
          → startForeground()                  // Notificación persistente
          → acquireWakeLock()                  // Mantiene CPU activa
          → raopServer.start()                 // Abre TCP puerto 5000
          → serviceRegistrar.register()        // Anuncia via mDNS
```

### 2. Conexión AirPlay (Handshake RTSP)

```
iPhone descubre "_raop._tcp" via Bonjour
  → Conecta TCP al puerto 5000

OPTIONS  ← responde con métodos soportados + Apple-Response (RSA firmado)
ANNOUNCE ← parsea SDP: extrae fmtp (config ALAC), rsaaeskey, aesiv
SETUP    ← abre sockets UDP, responde con server_port/control_port/timing_port
RECORD   ← audioPlayer.initialize() + .start(), inicia recepción UDP
```

### 3. Streaming de Audio

```
iPhone envía paquetes RTP via UDP (puerto negociado en SETUP)
  → RaopServer.startAudioReceiver() (corrutina en Dispatchers.IO)
      → Lee DatagramPacket
      → Extrae payload type (96=audio, 86=reenvío)
      → Extrae sequence number del header RTP
      → decryptAudio() — AES-128-CBC, bloques de 16 bytes
      → Almacena en packetBuffer[seqNo % 256] (jitter buffer)
      → Reproduce en orden secuencial:
          → alacDecoder.decode(data) → ShortArray (PCM 16-bit)
          → audioPlayer.enqueuePcmBlocking(pcmSamples)
              → pcmChannel.trySend() (Channel<ShortArray>, capacity=100)
              → Corrutina de playback: pcmChannel.receive() → AudioTrack.write()
```

### 4. Metadatos

```
iPhone envía SET_PARAMETER con diferentes Content-Types:

"text/parameters" → parseTextParameters()
  → "progress: start/current/end"  (timestamps RTP → segundos via sampleRate)
  → "volume: -30.0 a 0.0 dB"      (convertido a escala lineal 0.0-1.0)

"application/x-dmap-tagged" → parseDmapMetadata()
  → Formato binario DMAP: [4-byte tag][4-byte size][data]
  → "minm" = título, "asar" = artista, "asal" = álbum

"image/jpeg" o "image/png" → parseArtwork()
  → BitmapFactory.decodeByteArray() (target 600px con inSampleSize)

Resultado → publishMetadata() → onMetadataUpdate callback
  → NowPlayingViewModel.updateMetadata(TrackMetadata)
      → _metadata.postValue()  → UI observa y actualiza textos/progreso
      → _artwork.postValue()   → UI actualiza fondo blur + gradiente + glass panel
```

### 5. Fin de Sesión

```
iPhone envía TEARDOWN
  → stopAudioReceiver()           // Cancela corrutina UDP
  → audioPlayer.stop()            // Para AudioTrack
  → Reset metadatos               // Limpia título, artista, artwork, etc.
  → publishMetadata()             // Notifica UI del reset
  → onStatusUpdate("Esperando…")  // Vuelve al estado inicial
```

---

## Estructura de Archivos

```
Fire TV/
├── build.gradle.kts                    # Root: AGP 8.4.2, Kotlin 1.9.24
├── settings.gradle.kts                 # rootProject.name = "FireAirPlay"
├── gradle.properties                   # JVM args
│
└── app/
    ├── build.gradle.kts                # minSdk=22, targetSdk=34, viewBinding
    ├── src/main/
    │   ├── AndroidManifest.xml         # Permisos, TV launcher, service
    │   │
    │   ├── java/com/fireairplay/receiver/
    │   │   ├── MainActivity.kt         # UI principal, observa ViewModel
    │   │   │
    │   │   ├── server/
    │   │   │   └── RaopServer.kt       # Servidor RTSP + recepción UDP (900 líneas)
    │   │   │
    │   │   ├── network/
    │   │   │   └── AirPlayServiceRegistrar.kt  # mDNS/Bonjour registration
    │   │   │
    │   │   ├── service/
    │   │   │   └── AirPlayService.kt   # Foreground service lifecycle
    │   │   │
    │   │   ├── audio/
    │   │   │   ├── AlacDecoder.kt      # Decodificador ALAC puro Kotlin (546 líneas)
    │   │   │   ├── AudioPlayer.kt      # AudioTrack + Channel buffer
    │   │   │   └── BitReader.kt        # Lector a nivel de bits para ALAC
    │   │   │
    │   │   ├── model/
    │   │   │   └── TrackMetadata.kt    # Data class: título, artista, artwork, progreso
    │   │   │
    │   │   └── ui/
    │   │       ├── NowPlayingViewModel.kt      # ViewModel con LiveData
    │   │       ├── BlurHelper.kt               # Blur cross-API (RenderEffect/RenderScript)
    │   │       ├── AnimatedGradientView.kt     # Gradiente rotatorio con Palette
    │   │       └── GlassPanelLayout.kt         # Panel liquid glass con Canvas custom
    │   │
    │   └── res/
    │       ├── layout/
    │       │   └── activity_main.xml           # Layout principal liquid glass
    │       │
    │       ├── drawable/
    │       │   ├── ic_album_placeholder.xml    # Placeholder vinilo vectorial
    │       │   ├── glass_panel_liquid.xml      # (legacy, reemplazado por Canvas)
    │       │   ├── glass_panel_highlight.xml   # (legacy, reemplazado por Canvas)
    │       │   ├── progress_bar_liquid.xml     # Progress bar estilo pill
    │       │   ├── album_shadow.xml            # Sombra oval bajo album art
    │       │   ├── bottom_gradient_overlay.xml # Viñeta inferior
    │       │   ├── top_gradient_overlay.xml    # Viñeta superior
    │       │   └── bg_glassmorphism.xml        # (legacy, no usado)
    │       │
    │       ├── values/
    │       │   ├── colors.xml      # Paleta oscura + liquid glass + gradientes
    │       │   ├── dimens.xml      # Tamaños: album art, tipografía, spacing, glass
    │       │   ├── strings.xml     # Textos UI (español)
    │       │   └── themes.xml      # Tema NoActionBar, fullscreen, transparente
    │       │
    │       └── mipmap*/            # Iconos de launcher
```

---

## Capa de Red y Protocolo

### AirPlayServiceRegistrar.kt

**Propósito**: Registra el servicio mDNS para que dispositivos Apple descubran el Fire TV.

| Aspecto | Detalle |
|---------|---------|
| Tipo de servicio | `_raop._tcp` |
| Nombre de instancia | `<MAC_ADDRESS>@FireTV AirPlay` |
| API Android | `NsdManager` (NSD = Network Service Discovery) |
| Multicast Lock | Requerido para que Android no filtre paquetes mDNS |
| MAC fallback | Genera pseudo-MAC determinista si `wlan0` no está disponible |

**TXT Records clave** (simulan un AirPort Express):
- `cn=0,1` — códecs: PCM, ALAC
- `et=0,1` — tipos de encriptación: none, RSA
- `sr=44100` — sample rate
- `ss=16` — sample size
- `tp=UDP` — transporte
- `am=AirPort4,107` — modelo del dispositivo (emulado)
- `pw=false` — sin contraseña

### RaopServer.kt (900 líneas)

**Propósito**: El corazón del proyecto. Implementa el protocolo RTSP de AirPlay 1.

#### Comandos RTSP manejados

| Comando | Función | Qué hace |
|---------|---------|----------|
| `OPTIONS` | `handleOptions()` | Responde métodos soportados + firma Apple-Challenge con RSA |
| `ANNOUNCE` | `handleAnnounce()` | Parsea SDP: extrae config ALAC (fmtp), clave AES encriptada, IV |
| `SETUP` | `handleSetup()` | Abre 3 sockets UDP (audio/control/timing), responde con puertos |
| `RECORD` | `handleRecord()` | Inicializa AudioTrack, arranca recepción UDP |
| `SET_PARAMETER` | `handleSetParameter()` | Despacha por Content-Type: texto, DMAP, imagen |
| `FLUSH` | `handleFlush()` | Limpia buffers de audio (seek/cambio de canción) |
| `TEARDOWN` | `handleTeardown()` | Para todo, resetea metadatos |
| `GET_PARAMETER` | `handleGetParameter()` | Heartbeat keep-alive, responde volumen |

#### Criptografía

1. **RSA (Apple-Challenge)**: La clave privada del AirPort Express está embebida en Base64 PKCS#1. Se convierte a PKCS#8 para Java's KeyFactory. Se usa para firmar el challenge de autenticación.

2. **AES-128-CBC**: La clave AES de sesión viene encriptada con RSA (OAEP SHA-1) en el ANNOUNCE. Se desencripta y usa para descifrar cada frame de audio. El IV se resetea por frame.

#### Jitter Buffer

- Array circular de 256 slots indexado por `seqNo % 256`
- Reproduce paquetes en orden secuencial
- Descarta paquetes tardíos (distancia negativa)
- Fuerza avance si el gap > 32 paquetes (~250ms)
- Reset completo si gap > 256

#### Parseo de Metadatos DMAP

Formato binario: `[4-byte ASCII tag][4-byte big-endian size][UTF-8 data]`

| Tag | Campo |
|-----|-------|
| `minm` | Título de la canción |
| `asar` | Nombre del artista |
| `asal` | Nombre del álbum |
| `mlit`, `mcon` | Tags contenedores (se procesan recursivamente) |

#### Progreso

Formato: `progress: RTP_START/RTP_CURRENT/RTP_END`

Conversión: `seconds = (timestamp - start) / sampleRate`

---

## Capa de Audio

### AlacDecoder.kt (546 líneas)

**Propósito**: Decodifica frames ALAC (Apple Lossless) a PCM 16-bit en Kotlin puro.

#### Estructura de un Frame ALAC

```
[3 bits tag][4 bits unused][12 bits unused]
[1 bit hasSize][2 bits uncompressedBytes][1 bit isNotCompressed]
[if hasSize: 32 bits outputSamples]

Si comprimido:
  [8 bits interlacingShift][8 bits interlacingLeftWeight]
  Por cada canal:
    [4 bits mode][4 bits denShift][3 bits riceModifier][5 bits predOrder]
    [predOrder × 16 bits coeficientes]
  [Rice-coded residuals]

Si no comprimido:
  [outputSamples × sampleSize bits raw PCM]
```

#### Pipeline de Decodificación

```
Frame ALAC comprimido
  → decodeRiceResiduals()     # Decodifica residuales con Rice/Golomb adaptativo
  → applyLpcPrediction()      # Reconstruye samples con predicción LPC
  → unmixStereo()             # Decorrelación estéreo (mid/side → L/R)
  → clamp16()                 # Limita a rango [-32768, 32767]
  → ShortArray               # PCM 16-bit interleaved (L, R, L, R, ...)
```

#### Parámetros Típicos AirPlay

| Parámetro | Valor | Descripción |
|-----------|-------|-------------|
| frameLength | 352 | Samples por frame |
| sampleSize | 16 | Bits por sample |
| numChannels | 2 | Estéreo |
| sampleRate | 44100 | Hz |
| riceHistoryMult | 40 | Multiplicador historia Rice |
| riceInitialHistory | 10 | Historia inicial |
| riceLimit | 14 | Límite k Rice |

### AudioPlayer.kt (207 líneas)

**Propósito**: Reproduce PCM via `AudioTrack` con un buffer desacoplado.

#### Arquitectura del Buffer

```
Hilo del RaopServer    ──trySend()──►  Channel<ShortArray>  ──receive()──►  Corrutina de playback
(decodifica ALAC)                      (capacity = 100)                     (AudioTrack.write())
                                       ≈ 0.8 seg de audio
```

| Configuración | Valor |
|--------------|-------|
| Sample rate | 44100 Hz |
| Canales | CHANNEL_OUT_STEREO |
| Formato | ENCODING_PCM_16BIT |
| Buffer size | 2× minBufferSize (mínimo 8192) |
| Modo | MODE_STREAM |
| Channel capacity | 100 frames ≈ 0.8 segundos |

#### Control de Volumen

El volumen llega de AirPlay en dB (-144 = mute, -30 = mínimo, 0 = máximo):

```kotlin
// Normalizar a 0.0–1.0 con curva cúbica para respuesta natural
val normalized = 1.0f - (volumeDb / -30.0).toFloat()
val linear = normalized³
audioTrack.setVolume(linear)
```

### BitReader.kt (122 líneas)

**Propósito**: Lee bits individuales de un byte array para el decodificador ALAC.

| Método | Uso |
|--------|-----|
| `readBit()` | Lee 1 bit (0 o 1) |
| `readBits(n)` | Lee n bits como entero unsigned (MSB first) |
| `readUnary(limit)` | Cuenta ceros consecutivos antes de un uno (Rice coding) |
| `unreadBit()` | Retrocede 1 bit (usado por bloques de ceros ALAC) |
| `readSignedModified(n)` | Lee n bits con extensión de signo ALAC (LSB = signo) |

---

## Capa de UI

### Jerarquía de Capas (bottom → top)

```
Layer 1: ImageView (ivBackgroundBlur)      → Album art, blur 200px, saturación 1.4x
Layer 2: AnimatedGradientView              → Gradiente rotatorio, colores del Palette
Layer 3: View (top vignette)               → Gradiente oscuro arriba
Layer 3b: View (bottom vignette)           → Gradiente oscuro abajo
Layer 4: ConstraintLayout (content)
  ├── TextView (tvStatus)                  → "Esperando conexión AirPlay…" (top-left)
  ├── TextView (tvBranding)                → "AirPlay" (top-right)
  ├── View (albumShadow)                   → Sombra oval debajo del album
  ├── CardView (cardAlbumArt)              → Album art, 1:1, corners 24dp, max 380dp
  │   └── ImageView (ivAlbumArt)
  └── GlassPanelLayout (glassPanel)        → Panel liquid glass, max 680dp
      └── ConstraintLayout
          ├── TextView (tvTrackTitle)       → 24sp, bold, blanco
          ├── TextView (tvArtistName)       → 16sp, #B3FFFFFF
          ├── TextView (tvAlbumName)        → 13sp, #66FFFFFF, GONE por defecto
          ├── ProgressBar (progressBar)     → 4dp, drawable custom pill
          ├── TextView (tvTimeElapsed)      → 12sp, "0:00"
          └── TextView (tvTimeRemaining)    → 12sp, "-0:00"
```

### BlurHelper.kt

**Propósito**: Aplica blur cross-API al fondo y genera bitmaps blurreados.

| API | Estrategia |
|-----|------------|
| 31+ (Android 12) | `RenderEffect.createBlurEffect()` — GPU, aplicado al View |
| 17–30 | `RenderScript` — CPU, downscale 25% → multi-pass blur (max 25px × N passes) |

Además aplica **saturación 1.4x** (`ColorMatrix.setSaturation()`) antes del blur para colores más vibrantes.

### AnimatedGradientView.kt

**Propósito**: Overlay con gradiente rotatorio que extrae colores del album art.

| Aspecto | Valor |
|---------|-------|
| Opacidad | 22% (~55/255) |
| Velocidad rotación | 30 segundos / vuelta completa |
| Colores por defecto | Pink `#E91E8C`, Red `#B91C4F`, Purple `#7B2FBE` |
| Extracción colores | `Palette.from(bitmap).generate()` → Vibrant, Dominant, DarkMuted |
| Shader | `LinearGradient` de 3 colores con `TileMode.MIRROR` |

### GlassPanelLayout.kt

**Propósito**: Panel de vidrio esmerilado con rendering custom Canvas.

#### Capas dibujadas en onDraw()

| # | Capa | Paint | Descripción |
|---|------|-------|-------------|
| 1 | Frosted backdrop | `backdropPaint` alpha=130 | Bitmap blurreada del album art, escalada y clipped al RoundRect |
| 2 | Glass body tint | `#14FFFFFF` fill | Capa blanca sutil que da el aspecto lechoso |
| 3 | Refraction gradient | LinearGradient vertical | `#1A` → `#08` → `#03` → `#00` — luz doblándose por el vidrio |
| 4 | Horizontal sheen | LinearGradient horizontal | `#06` → transparent → `#04` — reflejo lateral |
| 5 | Specular rim | Stroke 1.5dp, gradient | `#3A` → `#1A` → `#0D` → `#15` — borde brillante que varía |
| 5b | Inner rim | Stroke 0.75dp, `#0D` | Segundo borde interno para profundidad |
| 6 | Top highlight | LinearGradient, top 35% | `#18` → `#06` → `#00` — punto especular superior |

El `dispatchDraw()` también clipea los hijos al RoundRect para que nada se salga.

### NowPlayingViewModel.kt

**Propósito**: Bridge reactivo entre el servidor RAOP y la UI.

| LiveData | Tipo | Publicador |
|----------|------|------------|
| `metadata` | `TrackMetadata` | `RaopServer` via callback |
| `status` | `String` | `RaopServer` via callback |
| `artwork` | `Bitmap?` | Extraído de metadata cuando cambia |

**Progress ticker**: Corrutina que cada segundo incrementa `positionSeconds` si `isPlaying == true`, para que la barra de progreso avance suavemente entre actualizaciones del servidor.

### MainActivity.kt

**Propósito**: Activity principal, observa LiveData y actualiza la UI.

#### Animaciones

| Animación | Trigger | Efecto |
|-----------|---------|--------|
| Entrance (album) | Primera vez que llega artwork | Scale 0.8→1.0 + fade in, 600ms, OvershootInterpolator |
| Entrance (panel) | Primera vez que llega artwork | TranslateY 80→0 + fade in, 500ms, delay 200ms |
| Play/Pause | `isPlaying` cambia | Scale 1.0↔0.85, 400ms, OvershootInterpolator |
| Status fade | Primer metadata con título | Alpha → 0, 300ms |

---

## Modelo de Datos

### TrackMetadata (data class)

```kotlin
data class TrackMetadata(
    val title: String = "",           // DMAP "minm"
    val artist: String = "",          // DMAP "asar"
    val album: String = "",           // DMAP "asal"
    val artwork: Bitmap? = null,      // JPEG/PNG decodificado
    val durationSeconds: Double = 0.0,  // Calculado de RTP timestamps
    val positionSeconds: Double = 0.0,  // Calculado de RTP timestamps
    val isPlaying: Boolean = false      // Estado del AudioPlayer
)
```

**Propiedades computadas**:
- `elapsedFormatted` → `"3:45"` formato minutos:segundos
- `remainingFormatted` → `"-1:15"` tiempo restante negativo
- `progressFraction` → `0.0f..1.0f` para la barra de progreso

---

## Servicio en Primer Plano

### AirPlayService.kt

| Aspecto | Detalle |
|---------|---------|
| Tipo | `foregroundServiceType="mediaPlayback"` |
| WakeLock | `PARTIAL_WAKE_LOCK`, máximo 10 horas |
| Notification | Canal de baja importancia, icono `ic_media_play` |
| START_STICKY | Se reinicia si Android lo mata |
| Callbacks | Lambdas estáticas en `Companion` (no inyección de dependencias) |

### Lifecycle

```
onCreate: crea AlacDecoder, AudioPlayer, ServiceRegistrar, RaopServer
onStartCommand: foreground + wakelock + server.start() + mDNS register
onDestroy: mDNS unregister + server.stop() + audioPlayer.release() + wakelock release
```

---

## Recursos Android

### Permisos (AndroidManifest.xml)

| Permiso | Uso |
|---------|-----|
| `INTERNET` | Conexiones RTSP y RTP |
| `ACCESS_WIFI_STATE` | Obtener MAC address |
| `ACCESS_NETWORK_STATE` | Estado de conectividad |
| `CHANGE_WIFI_MULTICAST_STATE` | Multicast lock para mDNS |
| `WAKE_LOCK` | Mantener CPU activa |
| `FOREGROUND_SERVICE` | Servicio en primer plano |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Tipo específico (API 34) |

### Features

```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
```

Ambas `required="false"` para que funcione tanto en Fire TV como en teléfonos/tablets de prueba.

### Strings (español)

| Key | Valor |
|-----|-------|
| `status_waiting` | Esperando conexión AirPlay… |
| `track_title_placeholder` | Sin reproducción |
| `track_artist_placeholder` | Abre AirPlay en tu dispositivo Apple |

---

## Dependencias

```kotlin
// AndroidX Core
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1

// Layout
androidx.constraintlayout:constraintlayout:2.1.4
androidx.cardview:cardview:1.0.0

// Color extraction
androidx.palette:palette-ktx:1.0.0       // Extrae colores dominantes del album art

// Lifecycle (MVVM)
androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0
androidx.lifecycle:lifecycle-livedata-ktx:2.7.0
androidx.lifecycle:lifecycle-runtime-ktx:2.7.0

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
```

> **Sin dependencias externas de red o audio** — todo el protocolo AirPlay/RTSP/RTP, la criptografía AES/RSA, el decodificador ALAC y la reproducción de audio usan APIs de Android/Java estándar.

---

## Configuración de Build

| Configuración | Valor |
|---------------|-------|
| AGP | 8.4.2 |
| Kotlin | 1.9.24 |
| Gradle | 9.3.0 |
| compileSdk | 34 |
| minSdk | 22 (Fire OS legacy) |
| targetSdk | 34 |
| JVM target | 1.8 |
| viewBinding | Habilitado (pero se usa `findViewById`) |
| applicationId | `com.fireairplay.receiver` |

---

## Guía de Desarrollo

### Requisitos

- Android Studio (con JDK integrado en `jbr/`)
- SDK de Android 34
- Dispositivo Fire TV o emulador Android TV para pruebas

### Build desde terminal

```bash
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug

# El APK se genera en:
# app/build/outputs/apk/debug/app-debug.apk
```

### Deploy a Fire TV

```bash
# Conectar via ADB (activar depuración en Fire TV → Settings → My Fire TV → Developer Options)
adb connect <IP_DEL_FIRETV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Archivos clave para modificaciones comunes

| Quiero... | Archivo |
|-----------|---------|
| Cambiar el nombre del dispositivo AirPlay | `AirPlayServiceRegistrar.kt` → `DEVICE_NAME` |
| Cambiar el puerto RTSP | `RaopServer.kt` → `DEFAULT_RTSP_PORT` |
| Ajustar el efecto liquid glass | `GlassPanelLayout.kt` → valores de alpha/color en las Paints |
| Cambiar colores del gradiente | `AnimatedGradientView.kt` → `color1/2/3` |
| Ajustar blur del fondo | `BlurHelper.kt` → `DOWNSCALE_FACTOR`, pasar radius distinto |
| Cambiar layout/tamaños de la UI | `activity_main.xml` + `dimens.xml` |
| Agregar nuevos metadatos DMAP | `RaopServer.kt` → `parseDmapMetadata()`, agregar tags |
| Ajustar buffer de audio | `AudioPlayer.kt` → `BUFFER_CAPACITY` |
| Cambiar textos de la UI | `strings.xml` |

---

## Problemas Conocidos y Limitaciones

### Protocolo
- **Solo AirPlay 1** — No soporta AirPlay 2 (HAP, HomeKit pairing). iOS puede mostrar un diálogo de "verificación" que se puede ignorar.
- **Un cliente a la vez** — El servidor RTSP acepta conexiones seriales, no simultáneas.
- **POST rechazado** — Se responde 501 a POST para forzar el fallback no-pareado (`handleClient` → POST case).

### Audio
- **Solo ALAC** — No soporta AAC (codec `cn=0,1` anuncia PCM y ALAC solamente).
- **Decodificador Kotlin** — Más lento que implementación nativa C. Puede tener issues en Fire TV Stick de primera generación.
- **Latencia** — ~0.8 segundos de buffer + latencia de red. No apto para sincronización labial con video.

### UI
- **RenderScript deprecated** — El blur en APIs < 31 usa RenderScript que está deprecated. Funciona pero puede desaparecer en futuras versiones del NDK.
- **`glass_panel_liquid.xml` y `glass_panel_highlight.xml`** — Son archivos legacy; el efecto real lo dibuja `GlassPanelLayout` via Canvas. Se pueden eliminar sin afectar la app.
- **`bg_glassmorphism.xml`** — Archivo legacy no utilizado.

### Platform
- **minSdk 22** — Necesario para Fire TV Sticks antiguos pero limita APIs modernas disponibles.
- **MAC address** — Android 6+ restringe acceso a la MAC real; el fallback genera una pseudo-MAC.
- **Multicast lock** — Si el WiFi se desconecta/reconecta, puede ser necesario reiniciar la app para re-adquirir el lock.
