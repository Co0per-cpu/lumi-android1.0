# Lumi para Android (Samsung S26 FE)

Versión de teléfono de Lumi, en Kotlin nativo. Mantiene las reglas del PC: nada NSFW, el personaje es tu render (no se cambia el diseño), Lumi nunca dice que hizo algo que no hizo, tú tienes el control total y todo queda en el teléfono.

> **Importante:** este proyecto se escribió sin poder compilarlo (no había SDK de Android disponible). El cerebro de órdenes sí se probó. Lo más probable es que Android Studio marque algunos errores pequeños la primera vez: pégamelos y los corrijo.

## Compilar e instalar

1. Instala **Android Studio** (versión reciente) en el PC.
2. *File > Open* y elige esta carpeta. Deja que haga el *Gradle Sync* (descarga lo necesario). Si ofrece actualizar el plugin de Android o Kotlin, acepta.
3. En el teléfono: *Ajustes > Acerca del teléfono > Información de software* y toca 7 veces "Número de compilación". Luego *Opciones de desarrollador > Depuración USB*.
4. Conecta el teléfono por USB y presiona ▶ (Run).

## Primeros pasos en el teléfono

1. Abre Lumi y da los permisos de la sección **Permisos del teléfono**:
   - *Mostrarse sobre otras apps* (obligatorio para la burbuja).
   - *Micrófono* y *Notificaciones*.
   - *Acceso al uso*: sirve para **comprobar** que una app se abrió. Sin él, Lumi te avisa que no puede confirmarlo.
   - *Alarmas exactas*: para que los recordatorios lleguen a la hora.
2. **Personaje:** ya viene incluido (tus GIF convertidos a WebP con transparencia, sin cambiar el diseño). Para reemplazarlo, usa *Importar personaje (.zip)*.
3. Toca **Iniciar Lumi**.

## Gestos

| Gesto sobre Lumi | Qué hace |
|---|---|
| Tocar | Abre o cierra la barra para escribir |
| Doble toque | Escucha (el micrófono se apaga solo al terminar) |
| Mantener presionada | **Freno de emergencia**: detiene voz, micrófono, acciones y preguntas |
| Arrastrar | La mueve; si la sueltas en un borde, se esconde asomándose |

La notificación fija también tiene **Frenar**, **Ocultar/Mostrar** y **Cerrar Lumi**.

**Ocultar:** el botón del ojo en la barra la oculta 15 minutos, 1 hora o hasta que la llames. En Ajustes puedes elegir apps donde se esconde sola (requiere *Acceso al uso*), cambiar su tamaño (de Mini a Muy grande) y apagar el globo de texto, el botón de micrófono o la etiqueta de qué IA respondió.

## IA: PC por Wi-Fi + teléfono

En *Ajustes > Inteligencia artificial* eliges Automático (PC si está, si no el teléfono), solo PC o solo teléfono. La burbuja muestra abajo cuál respondió.

**Ollama del PC** (el mismo de la versión de Windows):
1. En Windows, crea la variable de entorno `OLLAMA_HOST` con el valor `0.0.0.0` y reinicia Ollama.
2. Permite el puerto 11434 en el firewall de Windows (solo para redes privadas).
3. Busca la IP del PC (`ipconfig`, "Dirección IPv4") y ponla en Lumi: `http://192.168.x.x:11434`. Toca *Probar conexión*.

**Modelo en el teléfono** (funciona sin PC ni internet):
1. Descarga un modelo `.litertlm` de la comunidad LiteRT en Hugging Face. Para empezar: **Gemma 3 1B int4** (`gemma3-1b-it-int4.litertlm`, ~600 MB).
2. Pásalo al teléfono y elígelo en *Elegir modelo del teléfono*.

## Spotify

1. En developer.spotify.com, en tu app (puede ser la misma del PC), agrega el Redirect URI `http://127.0.0.1:8765/callback`.
2. Pega tu Client ID en Lumi y toca *Conectar Spotify*. Se abre el login; al aceptar, vuelve a Lumi.
3. Los tokens quedan cifrados con una llave del Android Keystore. Controlar la música requiere Premium.

## Qué entiende (ejemplos)

- "Oye, ¿me puedes abrir WhatsApp?" · "abre música" (con un atajo propio)
- "pon Despacito" · "ponme música de Mon Laferte" · "pon mi playlist Para Estudiar" · "pon el álbum …" · "pon mis me gusta"
- "pausa" · "siguiente" · "¿qué está sonando?"
- "sube el volumen" · "volumen al 40" · "silencio"
- "despiértame a las 7" · "temporizador de 5 minutos" · "recuérdame en 10 minutos que saque la ropa"
- "¿cómo está el clima en Valparaíso?" · "qué es la fotosíntesis" · "busca recetas de sopaipillas" · "busca gatitos en YouTube"
- Cualquier otra cosa va a la conversación con la IA.

## Qué se comprueba y qué no

| Acción | Cómo se comprueba |
|---|---|
| Abrir app | Android confirma que pasó a primer plano (requiere *Acceso al uso*) |
| Volumen | Se lee el volumen después de cambiarlo |
| Música con Spotify | Spotify confirma qué suena y si está sonando |
| Música sin Spotify | Se comprueba si suena algo; no se puede saber qué canción |
| Alarma | Se revisa la próxima alarma del sistema |
| Recordatorio | Android confirma que quedó programado |
| Temporizador | **No se puede comprobar**: Lumi lo dice |
| Cerrar una app | Android no lo permite: Lumi lo explica y ofrece ir al inicio |

Cada acción tiene un nivel en *Qué puede hacer Lumi*: **Permitido**, **Preguntar antes** o **Bloqueado**. Todo queda en el *Registro de acciones*.

## Qué cambió respecto al PC

- **Voz:** usa el texto a voz del teléfono; en Ajustes eliges la voz en español que más te guste (velocidad y tono ajustables). Piper no tiene versión oficial para Android.
- **Oído:** reconocimiento de voz del teléfono, preferentemente sin internet.
- **No incluidas** (no aplican o Android no lo permite): las "manos" de diseño, apagar o bloquear el teléfono y la captura de pantalla.

## Mapa del código

```
app/src/main/java/cl/lumi/app/
  MainActivity.kt          Ajustes
  core/Brain.kt            Entiende órdenes (equivalente a brain.js)
  core/Ajustes.kt          Preferencias, permisos por acción, atajos, temas
  core/Freno.kt            Freno de emergencia
  core/Registro.kt         Registro de acciones
  acciones/Acciones.kt     Ejecuta y comprueba cada acción
  acciones/Recordatorios.kt
  ia/Ia.kt                 Ollama del PC + LiteRT-LM en el teléfono
  voz/Voz.kt, voz/Oido.kt  Hablar y escuchar
  spotify/                 PKCE, servidor 127.0.0.1:8765, tokens cifrados
  overlay/OverlayService.kt  Burbuja, globo, gestos, paseos
  overlay/Personaje.kt     Sprites WebP con doble búfer y entradas _in
app/src/test/.../BrainTest.kt  Pruebas del cerebro
```

## Puntos a revisar al compilar

- **LiteRT-LM** está en versión 0.x y su API puede cambiar. Si `Ia.kt` marca error en `Engine`, `ConversationConfig` o en `m.toString()`, revisa la guía oficial "Get Started with LiteRT-LM on Android".
- Si el reconocimiento de voz falla cuando Lumi está sobre otra app, inicia Lumi **después** de dar el permiso de micrófono (el servicio necesita arrancar con ese permiso).
