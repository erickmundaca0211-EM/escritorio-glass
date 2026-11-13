# Arquitectura de Smart Dock (punto de partida de Escritorio Glass)

> Estado: análisis del código original de Smart Dock 1.15.0 (`upstream/main`, commit `517604a`), hecho en la
> Fase 0 antes del renombrado. Las rutas son relativas a `app/src/main/java/cu/axel/smartdock/`.

## Resumen

Smart Dock es un **launcher + servicio de accesibilidad** que dibuja toda la interfaz de escritorio (dock, menú de
apps, ajustes rápidos, notificaciones, menús contextuales) como **ventanas superpuestas** sobre las demás apps.
Casi toda la lógica vive en un único archivo: `services/DockService.kt` (~2 600 líneas).

```
LauncherActivity (HOME) ──broadcast LAUNCHER_ACTION──▶ DockService (AccessibilityService)
        ▲                                                   │  WindowManager.addView(...)  ──▶ overlays
        └──────broadcast DOCK_SERVICE_ACTION────────────────┤
NotificationService (NotificationListener) ◀──AIDL bind─────┘
MainActivity (ajustes y permisos) ── SharedPreferences ──▶ DockService.onSharedPreferenceChanged
```

## Componentes declarados en el manifest

| Componente | Tipo | Exportado | Qué hace |
|---|---|---|---|
| `App` | `Application` | — | Aplica colores dinámicos y captura errores no controlados: guarda un informe y abre `DebugActivity` un segundo después. |
| `activities/MainActivity` | Actividad `LAUNCHER` | Sí (obligatorio) | Pantalla de ajustes (`PreferencesFragment` + fragmentos de `fragments/`) y diálogo de permisos. |
| `activities/LauncherActivity` | Actividad `HOME` | Sí (obligatorio) | Escritorio: fondo de pantalla, cuadrícula de apps ancladas al escritorio, notas. Avisa al servicio con `LAUNCHER_RESUMED` y le pide abrir apps con `ACTION_LAUNCH_APP`. |
| `activities/SecondaryLauncherActivity` | Actividad `SECONDARY_HOME` | Sí | Subclase vacía de `LauncherActivity` para pantallas secundarias. |
| `activities/DebugActivity` | Actividad | No | Muestra el informe de error y lo guarda con `Utils.saveLog` en el almacenamiento propio de la app. |
| `services/DockService` | `AccessibilityService` | No (protegido por `BIND_ACCESSIBILITY_SERVICE`) | Todo el escritorio: crea y actualiza los overlays, atajos de teclado, lista de tareas, ajustes rápidos y menú de apagado. |
| `services/NotificationService` | `NotificationListenerService` | No (protegido por `BIND_NOTIFICATION_LISTENER_SERVICE`) | Expone las notificaciones activas al `DockService` mediante AIDL (`INotificationServiceBridge`/`INotificationCallback`). No guarda nada. |
| `rikka.shizuku.ShizukuProvider` | Proveedor | Sí, protegido por `INTERACT_ACROSS_USERS_FULL` | Proveedor estándar de Shizuku para recibir su binder. |

### Comunicación interna

- **Broadcasts** con `setPackage(packageName)` y receptores registrados con `ContextCompat.RECEIVER_NOT_EXPORTED`
  (`DockService.kt:271-379`, `LauncherActivity.kt:120`). Las acciones son cadenas cortas sin prefijo de paquete:
  `launcher_action`, `dock_service_action`, `notification_service_action`, etc.
- **AIDL** entre `DockService` y `NotificationService`: el servicio de notificaciones solo responde a `onBind` si la
  acción es `ACTION_BIND_NOTIFICATION_SERVICE`.
- **SharedPreferences** por defecto como fuente de configuración. `DockService` escucha cambios en
  `onSharedPreferenceChanged` (`DockService.kt:1361`).

## Ciclo de vida de `DockService`

1. `onCreate` (`:217`): obtiene los servicios del sistema y, si Shizuku tiene permiso, crea los wrappers
   (`initWrappers`). Se suscribe a la aparición y desaparición del binder de Shizuku.
2. `onServiceConnected` (`:261`): `createViews()` y luego registra los receptores (launcher, notificaciones,
   sonidos, fondo, paquetes desinstalados, batería, Wi-Fi, Bluetooth, pantallas), enlaza `NotificationService`
   y muestra el dock.
3. `onAccessibilityEvent` (`:513`): con `TYPE_WINDOWS_CHANGED` recalcula las tareas abiertas. Si el ajuste
   `custom_toasts` está activo, con `TYPE_NOTIFICATION_STATE_CHANGED` toma `event.text[0]` (el texto del *toast*
   de otra app) y lo muestra en un toast propio (no lo guarda).
4. `onKeyEvent` (`:564-758`): atajos (Alt+L bloquear, Alt+P ajustes, Alt+T terminal, Meta, redimensionar con
   flechas, etc.). Solo se reaccionan, no se guardan.
5. `onDestroy` (`:2048`): quita receptores y vistas.

## Cómo se dibujan los overlays

- Todos los parámetros salen de `Utils.makeWindowParams` (`utils/Utils.kt:124`):
  - `type = TYPE_APPLICATION_OVERLAY`. Existe la opción `accessibilityWindow = true`
    (`TYPE_ACCESSIBILITY_OVERLAY`), pero **ninguna llamada la usa**.
  - `format = PixelFormat.TRANSLUCENT`, `flags = FLAG_NOT_FOCUSABLE`, ancho y alto limitados al tamaño de la pantalla.
- El `WindowManager` se obtiene de un **contexto de pantalla** (`DeviceUtils.getDisplayContext`, `:2075`), no del
  contexto propio del servicio de accesibilidad. Esto permite dibujar en la pantalla secundaria, pero hace que hoy
  no se aprovechen las ventajas de `TYPE_ACCESSIBILITY_OVERLAY`, que necesita el token del servicio.
- Requiere el permiso **"Mostrar sobre otras apps"** (`SYSTEM_ALERT_WINDOW`).

| Overlay | Dónde se crea | Tamaño / gravedad | Flags extra |
|---|---|---|---|
| Dock (`R.layout.dock`, `HoverInterceptorLayout`) | `createDock` `:2099-2219` | Ancho completo × `dock_height` dp (56 por defecto), `BOTTOM\|START`; puede forzar horizontal con `screenOrientation` | — |
| Asa del dock | `createDock` `:2222` | 22 dp × `WRAP_CONTENT` | — |
| Esquinas activas | `createHotCorners` `:2230` | 2 dp, `TOP\|END` y `BOTTOM\|END` | — |
| Menú de apps | `createAppMenu` `:2249`, `showAppMenu` `:931-1034` | Variable según ajustes | `FLAG_NOT_TOUCH_MODAL \| FLAG_WATCH_OUTSIDE_TOUCH` (se quita `NOT_FOCUSABLE` para poder escribir en el buscador) |
| Menús contextuales | `:459`, `:1082`, `:1195` | `WRAP_CONTENT` junto al ícono | `FLAG_NOT_FOCUSABLE \| FLAG_WATCH_OUTSIDE_TOUCH` |
| Ajustes rápidos | `showQuickSettingsPanel` `:1650-1847` | Panel con pestañas (ajustes / notificaciones) | `FLAG_NOT_FOCUSABLE \| FLAG_WATCH_OUTSIDE_TOUCH` |
| Menú de apagado | `showPowerMenu` `:1878-1919` | Centrado | `FLAG_NOT_FOCUSABLE \| FLAG_WATCH_OUTSIDE_TOUCH` |
| Notificación emergente | `components/NotificationLayout.kt:211` | Esquina | — |
| Toast propio | `showToast` `:535` | Encima del dock | — |
| Diálogos del servicio | `dialogs/DockDialog.kt:23` | Diálogo Material | `window.setType(TYPE_APPLICATION_OVERLAY)` |

No hay ningún uso de desenfoque (`FLAG_BLUR_BEHIND`, `blurBehindRadius`, `RenderEffect`,
`createBackgroundBlurDrawable`). El color y la transparencia del dock se aplican con `ColorUtils` y `applyDockAlpha`.

## Apertura de apps y ventanas libres

- `AppUtils.makeActivityOptions` (`utils/AppUtils.kt:350`): `ActivityOptions` con `launchBounds` y, por reflexión,
  `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM = 5)` o `FULLSCREEN`. El modo de apertura por app se guarda en
  SQLite (`db/DBHelper`, tabla `table_launch_modes`).
- `AppUtils.makeLaunchBounds` (`:285`) calcula los modos `standard`, `maximized`, `portrait` y `tiled-*`, con un
  `scale_factor` configurable.
- **Redimensionar tareas existentes**, dos caminos:
  - Con **Shizuku**: `ActivityManagerWrapper.resizeTask` → `IActivityManager.resizeTask` (`DockService.kt:1146-1182`).
  - Con **root**: `AppUtils.resizeTask` → `su` `am task resize <id> <l> <t> <r> <b>` (`DockService.kt:637-699`, atajos).
- La app **no** activa `enable_freeform_support` ni `force_resizable_activities`: lo debe hacer el usuario desde
  Opciones de desarrollador.
- La lista de tareas abiertas (`updateRunningTasks`, `:1470`) usa `getRunningTasks` real si la app es de sistema o
  tiene Shizuku. Si no, aproxima con `UsageStatsManager` (apps usadas recientemente).
- La dependencia `com.github.ChickenHook:RestrictionBypass:2.2` no se llama desde el código: su AAR declara
  `org.chickenhook.restrictionbypass.BypassProvider` (no exportado, autoridad `${applicationId}.restrictionbypass`),
  que se ejecuta al arrancar el proceso y quita las restricciones de APIs ocultas. Eso permite la reflexión sobre
  `setLaunchWindowingMode`, `getWindowingMode` y, en adelante, `ViewRootImpl#createBackgroundBlurDrawable`.

## Ajustes

- `res/xml/preferences_*.xml` + `fragments/*Preferences.kt`: apariencia, menú de apps, dock (elementos vertical y
  horizontal), escritorio, apps predeterminadas, teclado, esquinas, notificaciones, sonidos, avanzado y ayuda.
- `preferences/`: preferencias personalizadas (selector de apps, de archivo, de paquete de íconos, slider).
- `Utils.backupPreferences`/`restorePreferences` (`:163`, `:196`): exportan e importan las preferencias a un archivo
  elegido por el usuario.
- **Densidad ("tamaño de pantalla")** en `AdvancedPreferences.kt:149-155`: escribe `Settings.Secure`
  `display_density_forced` y pide reiniciar. No guarda el valor original ni lo restaura.

## Permisos y caminos privilegiados

### Permisos del manifest
`SYSTEM_ALERT_WINDOW`, `GET_TASKS`, `REORDER_TASKS`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`,
`READ_EXTERNAL_STORAGE` (≤ API 32), `MANAGE_USERS`, `BLUETOOTH`, `REQUEST_DELETE_PACKAGES`, `ACCESS_SUPERUSER`,
`PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`, `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`.
**No declara `INTERNET`.** Falta confirmar que ninguna dependencia lo agregue en el manifest fusionado.

### `WRITE_SECURE_SETTINGS` (concedido por ADB)
Se usa a través de `DeviceUtils.putSecureSetting`, `putGlobalSetting` y `putSystemSetting` (`utils/DeviceUtils.kt:119-161`):
- Activar o desactivar el propio servicio de accesibilidad escribiendo `enabled_accessibility_services`
  (`enableService`/`disableService`, `:349-369`), con el nombre de componente **fijo** en el código.
- `display_density_forced`, `icon_blacklist` (íconos de la barra de estado) y `heads_up_notifications_enabled`
  (`AdvancedPreferences`).
- Brillo con `putSystemSetting` desde el panel de ajustes rápidos (`DockService.kt:1693`; usa `WRITE_SETTINGS`).

### Shizuku
- `DeviceUtils.hasShizukuPermission()` = `Shizuku.pingBinder() && checkSelfPermission()`. El permiso se pide desde
  `MainActivity.requestShizukuPermission` (`:184`).
- `wrappers/`: `ActivityManagerWrapper` (tareas, `resizeTask`, `removeTask`), `WindowOrganizerWrapper` (manda una
  ventana detrás del escritorio o la trae al frente; solo lo usa `PrivilegedActions`), `WifiManagerWrapper` (encender
  o apagar Wi-Fi, escanear) y `BluetoothManagerWrapper`. Usan `ShizukuBinderWrapper` +
  `SystemServiceHelper.getSystemService(...)` sobre las interfaces AIDL ocultas de `app/src/main/aidl/android/`
  (`WindowOrganizerWrapper` llega a las suyas por reflexión: `IActivityTaskManager`, `IWindowOrganizerController` y
  `WindowContainerTransaction`). **No ejecutan comandos de shell**: llaman a los servicios del sistema por binder.

### Root (`su`)
`DeviceUtils.runAsRoot(command)` (`:60`) abre `su` y le escribe el comando como texto. Se usa para:
- `softReboot` (`setprop ctl.restart zygote`), `reboot` y `shutdown`.
- `pm grant cu.axel.smartdock <permiso>` (`:302`).
- `pm uninstall --user 0 <paquete>` y `pm disable <paquete>` (`AppUtils.kt:401`, `DockService.kt:1295`,
  `LauncherActivity.kt:315-323`). El paquete viene de la lista de apps instaladas.
- `am task resize ...` con números calculados (`AppUtils.kt:384`).
- `cat /system/build.prop` (`AdvancedPreferences.kt:48`).

No hay una clase central ni una lista blanca: cada llamada arma su propio texto. En el POCO (sin root) todas estas
llamadas fallan sin efecto.

### Clase central de Escritorio Glass (desde la Fase 5)
`system/PrivilegedActions.kt` es el único lugar donde Escritorio Glass ejecuta acciones privilegiadas nuevas. La
lista blanca es el enum `Action`:

| Acción | Qué hace | Requiere |
|---|---|---|
| `SET_DISPLAY_DENSITY`, `CLEAR_DISPLAY_DENSITY` | Fuerza o quita la densidad de pantalla, validando el rango 50–200 % de la física. `system/DesktopScale.kt` la usa para la "Escala de las apps" y guarda la densidad previa para restaurarla. | `WRITE_SECURE_SETTINGS` |
| `SET_NIGHT_DISPLAY` | Enciende o apaga la luz nocturna de Android (`night_display_activated`): botón "Protección visual". | `WRITE_SECURE_SETTINGS` |
| `LIST_TASKS` | Lee las ventanas abiertas para la barra (solo app e id). | Shizuku |
| `CLOSE_TASK` | "Cerrar ventana" del menú de la barra, como deslizarla en recientes. | Shizuku |
| `MINIMIZE_TASK` | Clic en el botón de la app que está al frente: manda su ventana detrás del escritorio (`WindowContainerTransaction.reorder`). HyperOS deja las ventanas flotantes siempre encima, así que antes pasan a pantalla completa y se guardan sus límites. | Shizuku |
| `RESTORE_TASK` | El clic siguiente la trae al frente como desde recientes (`startActivityFromRecents`, inmediato) y, si era flotante, con sus límites. Si eso falla, reordena las ventanas directamente. | Shizuku |

Las ventanas solo se tocan con un id que el propio sistema listó y con su token (nunca armado por la app). La app
"al frente" es la que tiene el foco del sistema (`TaskInfo.isFocused`, en `AppUtils.frontTask`). Las llamadas
heredadas de Smart Dock de arriba siguen donde estaban; pasarlas a esta clase queda pendiente.

## Nombres de paquete fijos en el código (importante para el renombrado)

El `namespace` de Kotlin (`cu.axel.smartdock`) puede quedarse igual. Lo que cambia es el `applicationId`, y estas
cadenas lo suponen igual al namespace:

| Archivo | Línea | Cadena |
|---|---|---|
| `utils/DeviceUtils.kt` | 43 | `SERVICE_NAME = "cu.axel.smartdock/cu.axel.smartdock.services.DockService"` (activar el servicio por ajustes) |
| `utils/DeviceUtils.kt` | 302 | `"pm grant cu.axel.smartdock $permission"` |
| `utils/AppUtils.kt` | 195 | Compara `topActivity.className` con `cu.axel.smartdock.activities.MainActivity` / `DebugActivity` (los nombres de clase siguen siendo correctos si el namespace no cambia) |
| `res/xml/preferences_main.xml`, `preferences_dock.xml` | — | `fragment="cu.axel.smartdock.fragments..."` (nombres de clase: siguen siendo correctos si el namespace no cambia) |

## Observaciones frente a las reglas de `CLAUDE.md`

| Regla | Estado actual |
|---|---|
| 1. Sin `INTERNET` | ✅ No está declarado. Falta la verificación automática en Gradle (Fase 0). |
| 2. Sin WebView ni carga dinámica | ✅ No hay `WebView`, `DexClassLoader` ni sockets. `IconPackUtils` carga **recursos** de otros paquetes (no código). |
| 3. Receptores no exportados | ✅ Todos los receptores dinámicos usan `RECEIVER_NOT_EXPORTED`. Exportados solo `MAIN`/`HOME`/`SECONDARY_HOME` y el proveedor de Shizuku (protegido). |
| 4. Comandos privilegiados centralizados | ⚠️ `runAsRoot` recibe texto libre armado en varios lugares, sin lista blanca ni validación de paquete. Se corregirá cuando se toque (Fase 4/5). |
| 5. No leer contenido de otras apps | ⚠️ `accessibility_service.xml` pide `canRetrieveWindowContent="true"` y `flagRetrieveInteractiveWindows`. El código no usa `getRootInActiveWindow`, pero `custom_toasts` toma el texto de los toasts de otras apps para mostrarlo. |
| 6. Notificaciones sin guardar | ✅ `NotificationService` solo las expone por AIDL; no hay escritura a disco. |
| 7. Cambios reversibles | ❌ La densidad se escribe sin guardar el valor original y requiere reinicio. Pendiente para la Fase 4. |

## Compilación

- Gradle wrapper **8.13**, AGP **8.11.2**, Kotlin **1.9.20**, `compileSdk`/`targetSdk` **36**, `minSdk` **30**,
  Java/JVM target **1.8**.
- Repositorios: `google()`, `mavenCentral()`, `jcenter()` (obsoleto) y `jitpack.io` (para RestrictionBypass).
- `org.gradle.jvmargs=-Xmx1536M` (adecuado para esta PC).
- **Con el JDK de Android Studio 2026.2 (Java 25) el build falla** al arrancar:
  `Unsupported class file major version 69` (probado el 2026-11-04). Gradle 8.13 no puede ejecutarse con Java 25.
- CI: `.github/workflows/build_debug_apk.yml` y `build_project.yml` (heredados del original).
