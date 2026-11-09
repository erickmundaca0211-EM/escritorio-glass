# Plan por fases — Escritorio Glass v1

Cada fase termina con: APK instalado en el POCO, Daniel probó lo indicado, commit en la rama de la fase
y merge a `main`. Si una fase revela un bloqueo, se anota en `docs/SPIKES.md` y se ajusta el plan antes de seguir.

## Fase 0 — Preparación y pruebas de factibilidad
**Objetivo:** el fork compila, se instala con su nueva identidad y sabemos qué permite HyperOS.

1. Explorar el código y escribir `docs/ARQUITECTURA.md` (servicios, actividades, cómo se dibujan los overlays,
   ajustes, integración con Shizuku y root).
2. Renombrar: `applicationId` `pe.vega.escritorioglass`, nombre "Escritorio Glass", ícono provisional.
   Mantener GPL-3.0 y créditos.
3. Verificación de Gradle que hace fallar el build si el manifest fusionado incluye `android.permission.INTERNET`.
4. Compilar, instalar y conceder permisos (accesibilidad, notificaciones, `WRITE_SECURE_SETTINGS`).
5. **Pruebas de factibilidad** (código mínimo, se pueden descartar después), resultados en `docs/SPIKES.md`:
   - a) ¿`isCrossWindowBlurEnabled()` es verdadero en el POCO? ¿Se ve el desenfoque detrás de un overlay de accesibilidad con bordes redondeados?
   - b) ¿Funcionan las ventanas libres? ¿Se pueden redimensionar tareas existentes (con y sin Shizuku)?
   - c) ¿Se puede cambiar la densidad al instante sin reiniciar y restaurarla?
   - d) ¿Qué clave usa HyperOS para "Modo lectura"? ¿Se puede activar con `WRITE_SECURE_SETTINGS`?
   - e) ¿Se puede seguir la posición de las ventanas libres (para barras de título propias) con fluidez?
   - f) Rango real de brillo.
   - g) ¿Se puede forzar **todo en horizontal** (escritorio y todas las apps, también las que solo admiten
     vertical) y restaurar la rotación original al salir? Comparar: overlay con `screenOrientation`, rotación fija
     del sistema (`user_rotation`) e ignorar la orientación pedida por las apps (`wm set-ignore-orientation-request`,
     requiere ADB/Shizuku).

**Aceptación:** la app nueva convive instalada con cualquier otra; el build falla si alguien agrega INTERNET;
`SPIKES.md` responde a–g con evidencia (comando + resultado).

## Fase 1 — Sistema de diseño "vidrio"
- Tokens de `docs/DISENO.md` en recursos (colores, dimensiones, radios), fuente Inter con su licencia.
- `GlassDrawable` (nivel 1) y ayudante de desenfoque (nivel 2) con respaldo automático; refracción AGSL (nivel 3)
  solo en el fondo del escritorio.
- Ajustes: calidad del vidrio, tinte, desenfoque, color de acento; fondo de escritorio elegido dentro de la app.
- Aplicar el vidrio a los paneles existentes de Smart Dock sin cambiar aún su estructura.

**Aceptación:** con calidad Estándar se ve el desenfoque detrás de la barra; con ahorro de batería cae a nivel 1
sin errores; el texto se lee bien sobre fondos claros y oscuros.

## Fase 2 — Barra de tareas estilo Windows 11
- Barra flotante de 50 dp: izquierda (fecha / próximo evento), centro (Inicio, Buscar, Vista de tareas, ancladas
  y abiertas con indicadores), derecha (íconos ocultos, grupo Wi-Fi/volumen/batería, hora, notificaciones).
- Iconos centrados que se reacomodan al abrir/cerrar apps; clic derecho / pulsación larga con menú contextual.
- Versión compacta automática en vertical.

**Aceptación:** se ve como `Main.png` (sin el clima); abrir y cerrar apps actualiza los indicadores.

## Fase 3 — Menú Inicio
- Panel de 560 × 434 dp centrado con buscador, Ancladas (6 × 3, anclar/desanclar), Todas las apps (A–Z),
  Recomendado (apps recientes) y pie con usuario y apagado.
- Tecla Windows/Meta del teclado abre y cierra el menú; Esc lo cierra.

**Aceptación:** buscar filtra al escribir; anclar/desanclar persiste tras reiniciar; se ve como `Main.png`.

### Hecho el 2026-11-05 (rama `fase-3-inicio`)
- Probado en el POCO: panel centrado sobre la barra (se achica si no cabe y muestra solo filas completas de
  Ancladas), búsqueda sin acentos que filtra al escribir (Enter abre la primera), Todas las apps A–Z con letras,
  Recomendado con "Hace N min" y "Más" (hasta 6), anclar/desanclar desde el menú contextual del menú y de la barra
  (persiste tras reiniciar el servicio), pie con inicial y nombre.
- La primera vez se anclan hasta 18 apps comunes que el usuario ya tenga (en el POCO: 13).
- Se quitó "Buscar en Google": lo que se escribe no sale del teléfono. El teclado en pantalla solo aparece al
  tocar el buscador.
- Botón de apagado: Bloquear · **Salir del modo escritorio** (pedido de Daniel: apaga el servicio, quita la
  barra y el horizontal y restaura la escala) · Apagar o reiniciar. Al abrir la app con el escritorio apagado
  ofrece "Entrar al modo escritorio".
- Pendiente de probar con un teclado físico: Windows/Meta abre y cierra, Esc cierra (las teclas inyectadas con
  adb no llegan al servicio de accesibilidad).
- Conocido: con el teclado en pantalla abierto, HyperOS sube la barra y el menú por encima del teclado.

## Fase 4 — Ajustes rápidos y centro de notificaciones
- Panel de ajustes rápidos 3 × 3 con: Wi-Fi, Bluetooth, Modo avión, Protección visual, Ahorro de energía,
  Horizontal fijo, No molestar, Linterna, Transmitir. Si un botón no puede cambiar el estado directamente
  (sin Shizuku), abre el panel del sistema correspondiente.
- Sliders de Brillo, Volumen y **Escala del escritorio** (aplicación inmediata y reversible).
- **Horizontal en todo el modo escritorio** (decisión de Daniel): al entrar al modo escritorio todas las apps se
  ven en horizontal, con el método elegido en el spike (g); al salir se restaura la rotación original. El botón
  "Horizontal fijo" de Ajustes rápidos lo activa o desactiva.
- Centro de notificaciones con tarjetas agrupadas, descartar y "Borrar todo"; calendario mensual compacto.

**Aceptación:** volumen y brillo cambian en vivo; Protección visual se activa y desactiva; la escala cambia
y se restaura al salir del modo escritorio; se ve como `QuickSettings.png`.

### Hecho el 2026-11-08 (rama `fase-4-ajustes`)
- **Ajustes rápidos** (grupo Wi-Fi/volumen/batería de la barra): 3 × 3 botones, Brillo, Volumen, Escala del
  escritorio y pie con batería, Ajustes de Escritorio Glass y Ajustes. Mantener presionado un botón abre su página
  de ajustes.

  | Botón | Sin Shizuku |
  |---|---|
  | Wi-Fi, Bluetooth, Modo avión, Transmitir | Abren el panel o la página del sistema (Android no deja cambiarlos). |
  | Ahorro de energía | Abre los ajustes de batería (escribir `global low_power` no activa el ahorro de HyperOS). |
  | Protección visual | Cambia directo: luz nocturna AOSP vía `PrivilegedActions` (spike d). |
  | Horizontal fijo | Cambia directo (`lock_landscape`). |
  | No molestar | Cambia directo: el lector de notificaciones da acceso a la política. |
  | Linterna | Cambia directo (`CameraManager.setTorchMode`). |

  Brillo 1–255 con "Modificar ajustes del sistema" (si falta, se pide). La escala usa `DesktopScale` y se aplica
  al soltar el deslizador.
- **Notificaciones y calendario** (hora y campana): tarjetas por app con título, texto y "Hace N min", tocar
  abre, la X descarta, "Borrar todo" si hay algo que borrar; calendario mensual que empieza en domingo
  (D L M X J V S), hoy en círculo de acento, flechas para cambiar de mes y la fecha abre el Calendario.
- Se quitó el panel de dos pestañas de Smart Dock.
- Probado en el POCO: Protección visual, No molestar y linterna se encienden y apagan; brillo, volumen y escala
  cambian en vivo; una notificación de prueba aparece al instante y se descarta con la X.
- Conocido: con la escala reducida la lista de notificaciones muestra pocas tarjetas (se desplaza); la barra
  tapa el borde inferior de las hojas del sistema como el panel de Wi-Fi (se cierran con Atrás).

## Fase 5 — Ventanas
- Abrir apps en ventana libre con tamaño inicial razonable para pantalla pequeña.
- Redimensionar: manija de esquina y/o menú "Tamaño" (pequeño, mediano, grande, personalizado) con el contorno
  punteado y la etiqueta de tamaño; maximizar/minimizar/cerrar desde la barra.
- Indicador flotante de Escala del escritorio.
- Barras de título propias solo si el spike (e) salió bien.

**Aceptación:** se puede trabajar con 2 ventanas a la vez en horizontal; cambiar la escala muestra más contenido.

### Adelantado el 2026-11-05 (rama `fase-5-ventanas`, a pedido de Daniel)
Sin root, Android no deja que las apps reserven el espacio de la barra (spike h). Daniel eligió: apps en ventana y
la barra que se esconde solo con apps a pantalla completa.
- **Ventanas por defecto:** lo que se abre desde Escritorio Glass usa el modo "standard" (ventana libre); los juegos
  siguen a pantalla completa. HyperOS decide el tamaño de la ventana y, si la app ya tenía una tarea a pantalla
  completa, la reutiliza (hay que cerrarla una vez desde Recientes).
- **Escala de las apps** (Apariencia > Escritorio): baja la densidad mientras el escritorio está activo para que
  apps y ventanas muestren más contenido; se restaura al apagarlo (`DesktopScale`, vía `PrivilegedActions`).
  La barra y sus paneles conservan su propia escala (no bajan de 70 %). Probado en el POCO: 62 % = 322 dpi.
  Advertencia: si se desinstala la app con la escala activa, la densidad queda cambiada (se arregla en Ajustes >
  Pantalla o con `adb shell wm density reset`).
- **Barra oculta con apps a pantalla completa:** al detectar una app a pantalla completa (tipo y tamaño de su
  ventana, nunca su contenido) la barra baja a 1 s; vuelve con el mouse en el borde de abajo y se esconde 1 s
  después de que el mouse sale; al volver al inicio o a una ventana queda fija. Con el dedo, el gesto de inicio
  la trae de vuelta.
- Pendiente para la Fase 5 completa: tamaño exacto de ventanas y maximizar desde la barra (requieren Shizuku), y el
  indicador flotante de escala. El control deslizante de la Fase 4 usará `DesktopScale`.

## Fase 6 — Pulido y versión 1.0
- Animaciones (resortes suaves), respuesta háptica, pantalla de bienvenida que guía permisos
  (configuración restringida, accesibilidad, ADB/Shizuku).
- Rendimiento: medir con el vidrio en Máxima; sin tirones al abrir el menú.
- README del fork (qué es, créditos, licencia, cómo compilar), APK release firmado con la clave de Daniel.

**Aceptación:** una semana de uso diario sin cierres inesperados; el APK release no tiene permiso de internet.
