# Escritorio Glass — guía del proyecto para Claude Code

> Responde siempre en **español**. El código (nombres, comentarios) va en inglés; los textos de la interfaz, en `values-es` y `values` (inglés).

## Qué es este proyecto

Fork de **Smart Dock** (https://github.com/axel358/smartdock, licencia GPL-3.0, autor Axel358) para crear
**Escritorio Glass** (nombre provisional): un escritorio estilo Windows 11 con efecto vidrio líquido
para usar el celular como si fuera una PC.

- Diseño aprobado: `docs/DISENO.md` y las imágenes `docs/mockup/*.png` (la fuente HTML está en `docs/mockup/*.dc.html`).
- Plan por fases y criterios de aceptación: `docs/PLAN.md`.
- Hallazgos técnicos del dispositivo (se crea en la Fase 0): `docs/SPIKES.md`.
- Arquitectura real del código (se crea en la Fase 0): `docs/ARQUITECTURA.md`.

## Dueño y entorno

- Desarrollador: Daniel (PC con **Windows 11, Ryzen 3 3200G, 8 GB de RAM**). Usa PowerShell.
- **No usar emulador** (poca RAM). Se compila desde la terminal y se prueba en el celular por USB.
- Si Android Studio está abierto, ciérralo antes de compilar con Gradle desde la terminal.
- Mantén `org.gradle.jvmargs` en un valor modesto (p. ej. `-Xmx2g`) en `gradle.properties`.
- **JDK para compilar: Java 21** (`JAVA_HOME` = Microsoft OpenJDK 21). El JBR de Android Studio 2026.2 es Java 25 y
  Gradle 8.13 no funciona con él (`Unsupported class file major version 69`).
- Windows solo deja 5.9 GB de RAM utilizables (la gráfica integrada reserva el resto).

## Dispositivo objetivo

- **POCO X7 Pro** (Xiaomi, HyperOS), pantalla 2712×1220, MediaTek Dimensity 8400-Ultra (GPU Mali).
- **Sin salida de video por cable** (USB-C 2.0): el escritorio se usa en la propia pantalla, en horizontal,
  o transmitiendo la pantalla de forma inalámbrica.
- **Sin root.** Permisos elevados solo por ADB (`pm grant ... WRITE_SECURE_SETTINGS`) o **Shizuku** (opcional).
- En Xiaomi, para que `adb install` y `pm grant` funcionen hay que activar en Opciones de desarrollador:
  "Instalar vía USB" y "Depuración USB (ajustes de seguridad)".

## Identidad de la app

- `applicationId`: `pe.vega.escritorioglass` (confirmado en la Fase 0; el `namespace` sigue siendo `cu.axel.smartdock`).
- Nombre visible: "Escritorio Glass".
- Mantener la licencia **GPL-3.0** (`LICENSE`), el aviso de copyright original y el crédito a Axel358 en
  "Acerca de" y en el README. El código fuente del fork debe seguir siendo público si se distribuye el APK.
- Marca propia: **no** usar logos, nombres ni fuentes de Microsoft (Windows, Segoe UI) ni de Apple
  ("Liquid Glass"). "Estilo Windows 11" solo como descripción interna. El botón Inicio usa el glifo propio
  de cuatro rombos del mockup.
- Fuente: **Inter** (SIL OFL 1.1) incluida en `res/font`, con su licencia en `app/src/main/assets/licenses/`.

## Comandos (PowerShell, desde la raíz del repo)

```powershell
.\gradlew.bat assembleDebug                                   # compilar
adb devices                                                   # ver el celular conectado
adb install -r app\build\outputs\apk\debug\app-debug.apk      # instalar: Daniel debe aceptar el aviso de Xiaomi (pantalla desbloqueada)
adb shell appops set pe.vega.escritorioglass SYSTEM_ALERT_WINDOW allow   # SIEMPRE tras reinstalar: HyperOS lo revoca
adb shell pm grant pe.vega.escritorioglass android.permission.WRITE_SECURE_SETTINGS
adb shell appops set pe.vega.escritorioglass ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set pe.vega.escritorioglass GET_USAGE_STATS allow
adb shell appops set pe.vega.escritorioglass WRITE_SETTINGS allow
adb shell cmd notification allow_listener pe.vega.escritorioglass/cu.axel.smartdock.services.NotificationService
adb shell pidof pe.vega.escritorioglass                       # PID para filtrar logs
adb logcat --pid <PID>                                        # logs de la app
adb shell wm density                                          # densidad física y forzada
adb shell wm density reset                                    # RESCATE si la escala queda mal
```

Etiqueta de logs de la app: `EG`. Nunca registres contenido de notificaciones ni texto de otras apps en los logs.

Tras reinstalar, si el dock no aparece: concede `SYSTEM_ALERT_WINDOW` (arriba) y reinicia el servicio de accesibilidad
(desactivar y activar en la app). HyperOS a veces revoca el permiso **unos segundos después** de instalar: espera ~30 s
y comprueba con `adb shell appops get pe.vega.escritorioglass SYSTEM_ALERT_WINDOW` que diga `allow`. El `applicationId` es `pe.vega.escritorioglass`, pero el paquete de las clases
sigue siendo `cu.axel.smartdock` (para facilitar traer cambios del original).

## Reglas de seguridad (no negociables)

La versión original de Smart Dock **no tiene permiso de internet**; esa es la principal garantía de privacidad.
Debe seguir así.

1. **Nunca** agregar `android.permission.INTERNET` ni dependencias que lo agreguen (analítica, anuncios,
   reportes de fallos, Firebase, etc.). En la Fase 0 se agrega una verificación de Gradle que **hace fallar
   el build** si el manifest fusionado contiene `INTERNET`. No la desactives.
2. Sin `WebView`, sin carga dinámica de código (`DexClassLoader` y similares), sin servidores ni sockets,
   sin plugins que ejecuten scripts de terceros.
3. Receivers dinámicos siempre con `RECEIVER_NOT_EXPORTED`. Exportar solo lo imprescindible
   (actividades `MAIN`/`HOME`). Ningún componente exportado acepta extras que disparen acciones privilegiadas.
4. Comandos privilegiados (Shizuku, root o `WRITE_SECURE_SETTINGS`) solo desde **una clase central** con
   una lista blanca de comandos. Los nombres de paquete se validan con regex
   (`^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$`) y los números con rangos fijos. Nunca concatenar
   texto libre del usuario en un comando de shell.
5. Accesibilidad: no leer el contenido de pantalla de otras apps (no extraer texto con
   `getRootInActiveWindow`). Las teclas solo se usan para atajos; nunca se guardan.
6. Notificaciones: solo se muestran y se descartan; no se guardan en disco ni se envían a ningún lado.
7. Todo cambio del sistema debe ser **reversible**. Densidad/escala: guardar el valor original antes de cambiarlo
   y restaurarlo al salir del modo escritorio, al desactivar el servicio y al arrancar si quedó un cambio
   pendiente. Lo mismo para filtros de pantalla y rotación.

## Decisiones tomadas

- **Sin clima** en la barra (necesitaría internet). En su lugar, el bloque izquierdo de la barra muestra la fecha
  y, si Daniel da el permiso opcional `READ_CALENDAR`, el próximo evento del calendario.
- **"Recomendado"** en el menú Inicio muestra las apps usadas recientemente (UsageStats), no archivos, para no
  pedir acceso amplio al almacenamiento.
- **Escala del contenido:** Android no permite escalar el contenido de una sola ventana de otra app. Se hace con
  una **escala global del escritorio** (densidad de pantalla), con control en Ajustes rápidos y un indicador
  flotante, aplicada al entrar al modo escritorio y restaurada al salir.
- **Barras de título de ventanas ajenas:** el sistema las dibuja. El estilo Win11 del mockup para las ventanas
  solo se intentará si el spike de la Fase 0 muestra que se puede seguir la posición de las ventanas con fluidez;
  si no, se usa la barra del sistema y se agregan controles de tamaño en la barra de tareas y por teclado.
- **Snap Layouts:** fuera del alcance de la versión 1 (Daniel prefirió solo redimensionar).
- **Todo en horizontal en modo escritorio:** mientras el modo escritorio esté activo, el escritorio y **todas las
  apps** (incluidas las que solo admiten vertical) se ven en horizontal. Al salir del modo escritorio, al desactivar
  el servicio o al arrancar con un cambio pendiente, se restaura la rotación original (regla 7). La forma de lograrlo
  sale del spike (g) de la Fase 0 (`docs/SPIKES.md`).

## Notas técnicas (validar en la Fase 0, ver `docs/SPIKES.md`)

- **Desenfoque entre ventanas (API 31+):** `WindowManager.isCrossWindowBlurEnabled()` y
  `addCrossWindowBlurEnabledListener` (se apaga con el ahorro de batería). Para overlays:
  `LayoutParams.FLAG_BLUR_BEHIND` + `blurBehindRadius`. Para bordes redondeados por vista, la API oculta
  `ViewRootImpl#createBackgroundBlurDrawable()` (el proyecto ya incluye `org.chickenhook.restrictionbypass`).
  Si no hay soporte: vidrio sin desenfoque (tinte más opaco).
- **Refracción (API 33+):** `RuntimeShader` (AGSL) aplicado solo sobre el fondo del escritorio que dibuja la
  propia app (fondo elegido dentro de la app), nunca sobre otras apps.
- **Escala:** `IWindowManager.setForcedDisplayDensityForUser` (requiere `WRITE_SECURE_SETTINGS`) o, con Shizuku,
  `wm density <n>`. Smart Dock hoy escribe `display_density_forced` y pide reiniciar; hay que mejorarlo para
  que sea inmediato.
- **Protección visual:** primero descubrir la clave de HyperOS ("Modo lectura") comparando
  `adb shell settings list system|secure|global` antes y después de activarlo desde el centro de control.
  Alternativa AOSP: `Settings.Secure` `night_display_activated`. Si nada funciona: overlay propio no táctil
  con tinte ámbar.
- **Brillo:** `Settings.System.SCREEN_BRIGHTNESS` con permiso `WRITE_SETTINGS` (el usuario lo concede).
  Verificar el rango real de brillo en HyperOS. **Volumen:** `AudioManager`.
- **Ventanas libres:** `enable_freeform_support` y `force_resizable_activities` (globales); lanzar con límites y
  modo de ventana libre (Smart Dock ya lo hace). Redimensionar tareas existentes: con Shizuku,
  `am task resize <taskId> <l> <t> <r> <b>`. HyperOS tiene su propio sistema de ventanas flotantes: comprobar
  qué funciona.

## Forma de trabajar

- Una rama por fase (`fase-0-preparacion`, `fase-1-glass`, ...). Commits pequeños y descriptivos en español.
- Antes de escribir código en cada fase: **muestra un plan breve y espera el OK de Daniel**.
- Después de cada cambio importante: compila, instala en el celular y pide a Daniel que pruebe lo concreto
  (dile exactamente qué tocar y qué debería ver).
- Mantén `upstream` apuntando a `https://github.com/axel358/smartdock` para traer correcciones del original.
- La firma de versiones release usa la clave propia de Daniel. **Nunca** subas al repo un keystore, contraseñas
  ni `local.properties`.
