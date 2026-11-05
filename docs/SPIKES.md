# Pruebas de factibilidad (Fase 0)

> Fecha: 2026-11-05. Dispositivo: **POCO X7 Pro** (`2412DPC0AG`), HyperOS **OS3.0.302.0.WOJMIXM**, Android 16
> (API 36), pantalla 1220×2712, densidad física 520, sin root. App: `pe.vega.escritorioglass` (debug).
> El código de prueba vive solo en la variante debug: `app/src/debug/java/cu/axel/smartdock/spikes/`
> (pantalla "EG Pruebas", pantalla "EG Vertical" y servicio de accesibilidad "EG Pruebas (ventanas)").
> Las capturas se tomaron con `adb shell screencap`; los registros usan la etiqueta `EG`.

## Resumen

| Spike | Pregunta | Resultado | Sin Shizuku | Con Shizuku/ADB |
|---|---|---|---|---|
| a | Desenfoque detrás de overlays con bordes redondeados | ✅ Viable | ✅ `BackgroundBlurDrawable` | — |
| b | Ventanas libres y redimensionar tareas | ⚠️ Viable con límites | ✅ Abrir en ventana (HyperOS elige el tamaño) | ✅ Redimensionar exacto |
| c | Densidad al instante y restaurar | ✅ Viable | ✅ `IWindowManager` + `WRITE_SECURE_SETTINGS` | ✅ `wm density` |
| d | Clave de "Modo lectura" | ✅ Encontrada | ⚠️ Solo luz nocturna AOSP | ✅ Modo lectura de HyperOS |
| e | Seguir ventanas libres para barras de título propias | ❌ No viable | — | — |
| f | Rango real de brillo | ✅ Medido | ✅ `WRITE_SETTINGS` | — |
| g | Todo en horizontal, incluso apps solo verticales | ✅ Viable | ✅ Overlay con `screenOrientation` | ❌ `ignore-orientation-request` no funciona |
| h | Que las apps dejen libre el espacio de la barra (Fase 2) | ❌ No viable sin root | ❌ | ❌ Android rechaza la superposición fabricada |

---

## a) Desenfoque entre ventanas

| Comando / acción | Resultado |
|---|---|
| `adb shell getprop ro.surface_flinger.supports_background_blur` | `1` |
| `adb shell settings get global disable_window_blurs` | `0` |
| App: `WindowManager.isCrossWindowBlurEnabled()` | `true` |
| App: overlay `TYPE_APPLICATION_OVERLAY` + `FLAG_BLUR_BEHIND`, `blurBehindRadius` = 30 dp | Se difumina **toda la pantalla** detrás del overlay, no solo su rectángulo. |
| App: overlay + `ViewRootImpl#createBackgroundBlurDrawable()` (reflexión; RestrictionBypass activo) con `setBlurRadius(30 dp)`, `setCornerRadius(16 dp)`, `setColor(#66121824)` | Se difumina **solo el panel**, con esquinas redondeadas. Registro: `BackgroundBlurDrawable aplicado (com.android.internal.graphics.drawable.BackgroundBlurDrawable)`. |
| Ahorro de batería activado por Daniel desde el centro de control | `dumpsys power`: `mIsPowerSaveModeEnabled: true`; HyperOS usa `system/POWER_SAVE_MODE_OPEN=1` (la clave estándar `global/low_power` sigue en `0`). `dumpsys window`: `mBlurEnabled=true`. El listener `addCrossWindowBlurEnabledListener` **no se disparó**. |
| `adb shell cmd power set-mode 1` | Sin efecto en HyperOS (`isPowerSaveMode` siguió en `false`). |

> **Actualización (Fase 1, 2026-11-05):** con el ahorro de batería de HyperOS activo, `PowerManager.isPowerSaveMode()`
> devuelve **`false`** a las apps (aunque `dumpsys power` muestre `mIsPowerSaveModeEnabled: true`), y el aviso que se
> envía es `miui.intent.action.POWER_SAVE_MODE_CHANGED` en lugar del estándar. El estado fiable es
> `Settings.System` **`POWER_SAVE_MODE_OPEN`** (1/0), que la app puede leer y vigilar con un `ContentObserver`.
> `GlassController` usa las tres señales; probado en el POCO: al activar el ahorro la barra pasa al nivel 1 y al
> desactivarlo vuelve el desenfoque.

**Conclusión:** el nivel 2 del vidrio es viable con `BackgroundBlurDrawable` en los overlays que ya usa Smart Dock.
`FLAG_BLUR_BEHIND` solo sirve como fondo difuminado de pantalla completa (por ejemplo, detrás del menú Inicio).
En HyperOS **el ahorro de batería no apaga el desenfoque**, así que la app debe bajar sola al nivel 1 consultando
`PowerManager.isPowerSaveMode()` y escuchando `ACTION_POWER_SAVE_MODE_CHANGED`, además de
`isCrossWindowBlurEnabled()`.

## b) Ventanas libres

| Comando / acción | Resultado |
|---|---|
| `adb shell pm has-feature android.software.freeform_window_management` | `true` |
| `adb shell settings get global enable_freeform_support` / `force_resizable_activities` | `null` / `0` (no hace falta activarlos) |
| `adb shell am start --windowingMode 5 -n com.miui.calculator/.cal.CalculatorActivity` | Tarea `mode=freeform`, ventana flotante de HyperOS con asa "•••". Usó el último tamaño que recordaba HyperOS: `Rect(1192, 218 - 1521, 1080)`. |
| Mismo comando con una app que ya tenía tarea (Ajustes) | Reutiliza la tarea existente en pantalla completa e ignora el modo ventana. |
| App: el dock abre Chrome con `setLaunchWindowingMode(5)` + `launchBounds` (modo `standard`) | `mode=freeform`, pero con límites `Rect(130, 130 - 1350, 1960)` (tamaño "teléfono" vertical de HyperOS), **no** los que pidió la app. |
| Escala visual | HyperOS dibuja las ventanas libres a **≈0.57**: una tarea de 1220×1830 se ve de 692×1038 px. |
| `adb shell am task resize 819 400 150 1500 950` (equivale a Shizuku) | Límites exactos `Rect(400, 150 - 1500, 950)` (~174 ms con el viaje por adb). También se dibuja escalada. |

**Conclusión:** las ventanas libres funcionan sin Opciones de desarrollador, pero **HyperOS decide el tamaño
inicial y aplica su propia escala**. Para un tamaño inicial razonable hay que redimensionar con Shizuku justo
después de abrir. Sin Shizuku no hay API para redimensionar tareas existentes (requiere `MANAGE_ACTIVITY_TASKS`).

## c) Densidad (escala del escritorio)

| Comando / acción | Resultado |
|---|---|
| App (con `WRITE_SECURE_SETTINGS`): `IWindowManager.setForcedDisplayDensityForUser(0, 440, usuario)` por reflexión | Volvió en **55 ms**. `adb shell wm density` → `Physical density: 520` / `Override density: 440`. Cambio inmediato, sin reiniciar. Las actividades se recrean por el cambio de configuración. |
| A los 10 s: `clearForcedDisplayDensityForUser(0, usuario)` (había `display_density_forced` vacío) | `wm density` → `Physical density: 520`; `display_density_forced` vacío. Restaurado (probado 2 veces). |

**Conclusión:** viable sin Shizuku. Sustituye el método actual de Smart Dock (escribir `display_density_forced` y
pedir reinicio). Hay que guardar el valor original antes de cambiarlo (regla 7).

## d) Modo lectura / Protección visual

| Comando / acción | Resultado |
|---|---|
| Vigía `settings list system/secure/global` cada 0.7 s mientras Daniel activaba "Modo lectura" en Ajustes | Única clave relacionada: `system/screen_paper_mode_enabled: 0 -> 1` (y de vuelta a `0`). |
| `dumpsys SurfaceFlinger` / `dumpsys color_display` con Modo lectura activo | No aparece en las matrices de color: lo aplica el hardware de pantalla de MediaTek. |
| App (`WRITE_SECURE_SETTINGS` + `WRITE_SETTINGS`): `Settings.System.putString("screen_paper_mode_enabled", "0")` | `IllegalArgumentException: You cannot keep your settings in the secure settings.` Una app con `targetSdk` ≥ 23 no puede escribir claves no públicas de `system`. |
| `adb shell settings put system screen_paper_mode_enabled 0` (equivale a Shizuku) | El Modo lectura **se apagó** (confirmado por Daniel). |
| App: `Settings.Secure.putString("night_display_activated", "1")` y luego `"0"` | Escritura correcta; `dumpsys color_display` → `Night display: Activated: true` (temperatura 2850 K) y luego `false`. Daniel confirmó que **la pantalla se vio cálida**. |

**Conclusión:** sin Shizuku, "Protección visual" = luz nocturna AOSP (`Settings.Secure` `night_display_activated`).
Con Shizuku, se puede usar el Modo lectura propio de HyperOS (`system/screen_paper_mode_enabled`). No hace falta el
overlay ámbar de respaldo.

## e) Seguir la posición de ventanas libres

| Comando / acción | Resultado |
|---|---|
| Servicio de accesibilidad debug (`flagRetrieveInteractiveWindows`) que registra solo `TYPE_WINDOWS_CHANGED` + `WINDOWS_CHANGE_BOUNDS` (id, tipo y límites; nunca contenido) | Activado temporalmente y desactivado después. |
| `adb shell input swipe 476 163 1500 300 1500` (arrastrar la ventana de Chrome por su asa durante 1.5 s) | 6 actualizaciones de límites (12 eventos, 2 ventanas) con intervalos de **452, 454, 168, 286 y 43 ms** → 2 o 3 por segundo. Límites finales `Rect(1154, 130 - 1846, 1168)`: los eventos informan el tamaño **visual** ya escalado. |

**Conclusión:** no es viable dibujar barras de título propias que sigan a las ventanas (harían falta ~60
actualizaciones por segundo). Se usa el asa de HyperOS y se agregan controles de tamaño en la barra de tareas y
por teclado, como prevé `CLAUDE.md`.

## f) Brillo

| Comando / acción | Resultado |
|---|---|
| Control de brillo al mínimo (Daniel) | `system/screen_brightness = 1` → `dumpsys display` `mBrightness=8.54597E-4` (= `brightnessMinimum`). |
| Control de brillo al máximo (Daniel) | `system/screen_brightness = 255` → `mBrightness=0.49993792`. |
| Rango del panel (`dumpsys display`) | `0.000855`–`1.0` (2–1401 nits). Por encima de 0.5 (~500 nits) solo lo usa el brillo automático (HBM). |
| App (`WRITE_SETTINGS`): `screen_brightness = 128` y restaurar `54` | `mBrightness=0.249969`; restaurado sin problemas. Modo manual (`screen_brightness_mode=0`). |

**Conclusión:** el slider de brillo usa `Settings.System.SCREEN_BRIGHTNESS` en el rango **1–255**, lineal hasta 0.5.

## g) Todo en horizontal (requisito agregado por Daniel)

Estado de partida: bloqueo de rotación del sistema en vertical (`accelerometer_rotation=0`, `user_rotation=0`) y
el ajuste de Smart Dock "Bloquear horizontal" (`lock_landscape=true`), que pone `screenOrientation=LANDSCAPE` en el
overlay del dock.

| Comando / acción | Resultado |
|---|---|
| Pantalla de prueba sin orientación fija | Pantalla en `ROTATION_90`; `mCurrentAppOrientation=SCREEN_ORIENTATION_LANDSCAPE` (la impone el overlay). |
| Actividad debug **"EG Vertical"** con `android:screenOrientation="portrait"` | Recibe configuración `w834dp h375dp land`, `letterBoxed=false`: **la app solo vertical queda en horizontal real**. |
| Sin el overlay (permiso de superposición revocado tras reinstalar) | La misma actividad volvió a vertical (`mRotation=0`). |
| App: `IWindowManager.setIgnoreOrientationRequest(0, true)` | `SecurityException: Requires SET_ORIENTATION permission`. |
| `adb shell wm set-ignore-orientation-request -d 0 true` | Sin efecto: `get-ignore-orientation-request` sigue en `false`. |
| `user_rotation` | No se probó aislado: el overlay ya decide la rotación, y Android deja que la orientación pedida por una app gane sobre `user_rotation`. |

**Conclusión:** el método es el overlay del dock con `screenOrientation=LANDSCAPE` mientras el modo escritorio está
activo. No requiere Shizuku y se revierte quitando la orientación del overlay. Advertencias: si se pierde el permiso
de superposición, se pierde también el horizontal. Mientras `uiautomator dump` está activo, la pantalla gira un
instante a vertical y vuelve.

## h) Reservar el espacio de la barra (requisito agregado por Daniel, Fase 2)

> Fecha: 2026-11-05. Pregunta: ¿se puede lograr que las apps a pantalla completa se acomoden encima de la barra,
> como con la barra de navegación, en vez de quedar tapadas?

Una ventana `TYPE_APPLICATION_OVERLAY` no aporta márgenes (insets) a las demás apps. La idea probada: hacer que
Android crea que la barra de navegación mide lo mismo que nuestra barra en horizontal.

| Comando / acción | Resultado |
|---|---|
| `cmd overlay lookup android android:dimen/navigation_bar_height[_landscape]` | `16dp` (`navigation_bar_frame_height` = 48dp). |
| `dumpsys window` en horizontal | Margen `navigationBars` = `[0,1168][2712,1220]` (52 px = 16dp); gestos obligatorios 156 px. |
| `adb shell cmd overlay fabricate ...` | La ayuda dice "Caller must be root". |
| Herramienta propia como shell (`app_process`) que llama a `IOverlayManager.commit` con una superposición fabricada (`navigation_bar_height_landscape` = 58dp) | Rechazada por el sistema: `IllegalArgumentException: Non-root shell cannot fabricate overlays`. No quedó nada registrado. |
| Barra oculta con el modo de Smart Dock (franja de 5dp abajo, método "swipe") | Las apps se ven completas, pero deslizar hacia arriba desde el borde lleva al inicio de HyperOS y la barra no vuelve: el borde inferior es de los gestos del sistema. |

**Conclusión:** sin root no hay forma de que las apps reserven el espacio de la barra (Shizuku tiene los mismos
permisos que el shell, así que tampoco sirve). Una RRO instalada no puede tocar `navigation_bar_height` (no es un
recurso público) y `wm overscan` ya no existe. Alternativas: ocultar la barra mientras hay una app al frente y
mostrarla con algo que no choque con los gestos (asa en una esquina, toque en el borde o el mouse en el borde), y
reducir la escala del escritorio (spike c) para que la barra ocupe menos alto. Las ventanas libres no sirven:
HyperOS las dibuja a ≈0.57 e ignora el tamaño pedido (spike b).

---

## Notas del entorno descubiertas en la Fase 0

- **JDK:** el JBR de Android Studio 2026.2 es Java 25 y Gradle 8.13 falla con `Unsupported class file major version 69`.
  Se compila con **Microsoft OpenJDK 21** (`JAVA_HOME`).
- **Instalar por USB en HyperOS:** `adb install` muestra un aviso en el celular que hay que aceptar con la pantalla
  desbloqueada; si no, falla con `INSTALL_FAILED_USER_RESTRICTED`.
- **Cada reinstalación revoca la superposición:** después de `adb install -r`, `SYSTEM_ALERT_WINDOW` queda en `ignore`
  y el dock desaparece. Hay que volver a conceder `appops set ... SYSTEM_ALERT_WINDOW allow` y reiniciar el servicio
  de accesibilidad.
- **Teclado en horizontal:** se abre a pantalla completa (modo extracción) y tapa la interfaz. Hay que tenerlo en
  cuenta en el buscador del menú Inicio (`flagNoExtractUi`).
- **Buscador del menú de Smart Dock:** "IR" con texto sin resultados abre una búsqueda web en el navegador. No usa
  internet desde la app, pero conviene revisarlo en la Fase 3.

## Ajustes propuestos al plan

1. **Fase 1:** nivel 2 con `BackgroundBlurDrawable`. Bajar al nivel 1 también cuando `isPowerSaveMode()` sea verdadero,
   porque HyperOS no apaga el desenfoque en ahorro de batería.
2. **Fase 4:** "Protección visual" con `night_display_activated` (sin Shizuku) y el Modo lectura de HyperOS con
   Shizuku. "Escala del escritorio" con `IWindowManager` (sin Shizuku). Brillo 1–255.
3. **Fase 5:** sin barras de título propias (spike e). HyperOS ignora `launchBounds`: con Shizuku, redimensionar justo
   después de abrir; sin Shizuku, aceptar el tamaño de HyperOS. Tener en cuenta la escala ≈0.57 de las ventanas.
4. **Horizontal en modo escritorio:** activar `screenOrientation=LANDSCAPE` en el overlay del dock al entrar al modo
   escritorio y quitarlo al salir (spike g).
5. **Limpieza:** borrar `app/src/debug/java/cu/axel/smartdock/spikes/` y sus recursos al empezar la Fase 1.
