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

## Fase 5 — Ventanas
- Abrir apps en ventana libre con tamaño inicial razonable para pantalla pequeña.
- Redimensionar: manija de esquina y/o menú "Tamaño" (pequeño, mediano, grande, personalizado) con el contorno
  punteado y la etiqueta de tamaño; maximizar/minimizar/cerrar desde la barra.
- Indicador flotante de Escala del escritorio.
- Barras de título propias solo si el spike (e) salió bien.

**Aceptación:** se puede trabajar con 2 ventanas a la vez en horizontal; cambiar la escala muestra más contenido.

## Fase 6 — Pulido y versión 1.0
- Animaciones (resortes suaves), respuesta háptica, pantalla de bienvenida que guía permisos
  (configuración restringida, accesibilidad, ADB/Shizuku).
- Rendimiento: medir con el vidrio en Máxima; sin tirones al abrir el menú.
- README del fork (qué es, créditos, licencia, cómo compilar), APK release firmado con la clave de Daniel.

**Aceptación:** una semana de uso diario sin cierres inesperados; el APK release no tiene permiso de internet.
