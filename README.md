# FireAirPlay

[![Android TV / Fire TV Compatible](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Fire%20TV-vibrantgreen.svg?style=flat-square)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg?style=flat-square)](#)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=flat-square)](#)

Un receptor de audio **AirPlay 1 (RAOP)** nativo y de alto rendimiento para dispositivos **Amazon Fire TV** y **Android TV**, desarrollado en **Kotlin puro** sin dependencias nativas (C/NDK).

Cuenta con un diseño de interfaz de usuario de vanguardia e implementaciones robustas de decodificación y seguridad de red.

---

## Características Principales

### Experiencia Visual Premium (Liquid Glass)
* **Diseño Dividido Minimalista (Split-Pane):** Interfaz adaptada al formato de pantalla de Smart TVs (16:9), con la carátula a la izquierda y los detalles en la derecha.
* **Fondo Gradiente Líquido Dinámico:** El fondo reacciona y fluye suavemente extrayendo la paleta cromática dominante de la portada del álbum en reproducción.
* **Tipografía Geométrica de Alta Gama:** Implementación de la fuente premium `Montserrat` para lograr textos estilizados con sombras legibles sobre cualquier tonalidad.
* **Animaciones Cinematográficas:** Transición de entrada con escalados y deslizamientos dinámicos que dan vida a la pantalla en cada cambio de canción.
* **Distintivo Lossless:** Indicador visual esmerilado que destaca la calidad sin pérdidas del stream de audio.
* **Fácil Acceso a Ajustes:** Botón enfocado e interactivo en la esquina superior para personalizar el nombre del receptor directamente en la pantalla con el control remoto.
* **Iconos y Banners Optimizados para TV:** Incorporación de un banner panorámico 16:9 (`app_banner.png`) y un icono adaptativo con gradiente moderno y logotipo estilizado de transmisión (AirPlay), asegurando una excelente integración visual en la pantalla de inicio de Android TV y Amazon Fire TV.

### Motor de Audio y Red (Core)
* **Decodificador ALAC en Kotlin Puro:** Decodificación eficiente de tramas Apple Lossless Audio Codec (ALAC) sin necesidad de librerías nativas.
* **Autocontrol de Red y mDNS:** Registro Bonjour automático bajo la especificación `_raop._tcp` para una detección inmediata en dispositivos iOS/macOS.
* **Mitigaciones de Seguridad Robustas:**
  * Prevención de crashes por `SecurityException` en Android 10+ al acceder al número de serie de hardware.
  * Límites estrictos de lectura RTSP en búferes de red y validación de cabeceras `Content-Length` para prevenir Denegación de Servicio (DoS) por falta de memoria (OOM).
  * Control de colisión de conexiones y cierre de sockets antiguos para evitar corrupción de estado en transmisiones concurrentes.
  * Validación segura contra desbordamientos enteros al parsear metadatos binarios DMAP.

---

## Arquitectura del Sistema

El flujo de señal desde el dispositivo emisor (iOS) hasta el reproductor de la TV se estructura de la siguiente manera:

```
                  ┌──────────────────────┐
                  │  Dispositivo Emisor  │
                  └──────────┬───────────┘
                             │  mDNS Discovery
                             ▼
 ┌────────────────────────────────────────────────────────┐
 │                      FIREAIRPLAY                       │
 │                                                        │
 │ ┌──────────────────┐             ┌───────────────────┐ │
 │ │    mDNS / NSD    │◄───────────►│    RTSP Server    │ │
 │ │  (Discovery)     │             │    (Port 5000)    │ │
 │ └──────────────────┘             └─────────┬─────────┘ │
 │                                            │ RTP Stream│
 │                                            ▼           │
 │ ┌──────────────────┐             ┌───────────────────┐ │
 │ │   Audio Track    │             │   ALAC Decoder    │ │
 │ │  (PCM Playback)  │◄────────────┤ (AES Decryption)  │ │
 │ └──────────────────┘             └───────────────────┘ │
 │                                                        │
 └────────────────────────────────────────────────────────┘
```

---

## Guía de Compilación e Instalación

### Requisitos Previos
* **JDK 21** configurado en tu variable de entorno `JAVA_HOME`.
* **Android SDK** con soporte de herramientas de compilación para la API 34.

### Compilar el proyecto
Para generar el archivo APK ejecutable en tu entorno de desarrollo, ejecuta en la raíz del proyecto:

```bash
# En Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug

# En macOS / Linux
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

El APK de desarrollo compilado se generará en la ruta:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Hoja de Ruta de Desarrollo (Roadmap)

El desarrollo del proyecto está estructurado bajo las siguientes metas de expansión:

* **Fase 1 (Completada):** Personalización del nombre de receptor, distintivo Lossless y diseño tipográfico Montserrat adaptado.
* **Fase 2 (Próxima):** Robustez de red y auto-reconexión Bonjour al cambiar o reestablecerse la red Wi-Fi.
* **Fase 3:** Visualizador de ondas de espectro de audio reactivo en tiempo real en la pantalla.
* **Fase 4:** Letras de canciones dinámicas sincronizadas en un panel lateral y screensaver anti-quemado OLED.
* **Fase 5:** Seguridad de conexión con protección de PIN/Contraseña.

---

## Licencia

Este proyecto está bajo la Licencia MIT. Consulta el archivo `LICENSE` para más información.
