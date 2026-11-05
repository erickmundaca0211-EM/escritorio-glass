# Diseño — Escritorio Glass

Referencia visual aprobada: `docs/mockup/Main.png`, `QuickSettings.png`, `Windows.png` (fuente HTML en los
`.dc.html` del mismo folder; los estilos inline tienen las medidas exactas).

## Lienzo de referencia

- El mockup mide **1334 × 600** y representa el POCO X7 Pro en horizontal (2712 × 1220 px).
- 1 px del mockup ≈ 1 dp con la **escala del escritorio al 85 %** (densidad ≈ 325 dpi).
  Con la densidad de fábrica el ancho útil es menor: todos los paneles deben adaptarse (anchos máximos,
  no fijos) y nunca salirse de la pantalla.
- Uso principal en **horizontal**. En vertical la barra pasa a una versión compacta (menos elementos).

## Tokens

| Token | Valor | Uso |
|---|---|---|
| `glass_tint` | `#121824` con alfa **0.62** (ajustable 0.35–0.85) | Relleno de todo panel de vidrio |
| `glass_blur` | **24 dp** (ajustable 0–40) | Desenfoque detrás del vidrio |
| `glass_border` | blanco 16–18 % · 1 dp | Borde exterior |
| `glass_highlight_top` | blanco 32–38 % · 1 dp interior arriba | Brillo del borde superior |
| `glass_specular` | radial blanco 16 % → 0 en la esquina superior izquierda | Reflejo "líquido" |
| `glass_shadow` | `0 24 60` negro 45 % (paneles), `0 10 30` negro 35 % (barra) | Sombra |
| `accent` | `#6CC4FF` (opciones: `#7FD1B9`, `#F2B66D`, `#C4A1FF`) | Botón activo, sliders, indicadores |
| `on_accent` | `#0B1726` | Texto/íconos sobre el acento |
| `text_primary` | `#FFFFFF` | Texto sobre vidrio |
| `text_secondary` | blanco 82–86 % | Subtítulos (no bajar de 82 % por contraste) |
| `surface_hover` | blanco 10–14 % | Fondo de botón presionado/activo |
| `danger` | `#C42B1C` | Botón cerrar ventana |

Radios: barra **18 dp**, menú Inicio **22 dp**, paneles **20 dp**, ventanas **12 dp**, botones **10 dp**,
íconos de app **8–12 dp**, buscador y OSD totalmente redondeados.

Tipografía: **Inter** 400/500/600/700. Tamaños: 11.5 (etiquetas), 12–12.5 (texto), 13 (títulos de sección),
14 (títulos de panel). Interletrado normal.

Colores de íconos de apps (cuando el ícono real no está disponible o en modo "íconos uniformes"):
verde `#2E9E6A`, azul `#3B82C4`, arcilla `#C2573A`, gris `#5B6170`, magenta `#B4497D`, naranja `#D2652F`,
ámbar `#D7A33A` (glifo oscuro `#1F1A10`), azul calendario `#3E7BD9`, verde azulado `#2F9C8F`,
violeta `#6C5BD4`. En el escritorio real se usan los íconos de cada app (o el paquete de íconos elegido).

## Vidrio líquido: niveles

| Nivel | Qué hace | Requisito | Dónde |
|---|---|---|---|
| 1 · Base | Tinte + borde + brillo superior + reflejo + sombra (un `GlassDrawable`) | Ninguno | Siempre |
| 2 · Desenfoque | Desenfoque real de lo que hay detrás | API 31+ y desenfoque entre ventanas activo | Barra, menú, paneles |
| 3 · Refracción | Distorsión tipo lente en los bordes + leve aberración cromática | API 33+ (AGSL) | Solo sobre el fondo propio del escritorio |

Ajuste **"Calidad del vidrio"**: Desactivado (tinte opaco) · Estándar (1+2) · Máxima (1+2+3).
Con ahorro de batería: bajar automáticamente a nivel 1.

## Componentes

### Barra de tareas (`Main.png`, abajo)
- Flotante: márgenes de 10 dp a los lados y 8 dp abajo, alto **50 dp**, radio 18.
- **Izquierda:** fecha y, si hay permiso de calendario, el próximo evento (reemplaza al clima del mockup).
- **Centro:** Inicio (glifo de 4 rombos en color de acento), Buscar, Vista de tareas, apps ancladas y abiertas.
  Botones de 44 × 42 dp, ícono de 28 dp. Indicador de app abierta: píldora de 6 × 3 dp; app activa: 16 × 3 dp
  en color de acento + fondo `surface_hover`.
- **Derecha:** ^ (íconos ocultos), grupo Wi-Fi/volumen/batería (abre Ajustes rápidos), hora/fecha
  (abre calendario), campana con contador (abre Notificaciones).

### Menú Inicio (`Main.png`, centro)
- 560 × 434 dp, centrado, 12 dp por encima de la barra.
- Buscador redondeado (36 dp) que filtra apps al escribir.
- **Ancladas:** cuadrícula de 6 columnas × 3 filas (ícono 34 dp + etiqueta 11.5). Anclar/desanclar con
  pulsación larga o clic derecho. Botón "Todas las apps ›" abre la lista A–Z.
- **Recomendado:** 2 apps usadas recientemente (ver decisiones en `CLAUDE.md`).
- Pie (52 dp): inicial + nombre del usuario (configurable) y botón de apagado (bloquear / menú de apagado).

### Ajustes rápidos (`QuickSettings.png`, centro-derecha)
- 372 × 420 dp. Cuadrícula 3 × 3 de botones (44 dp de alto, etiqueta debajo):
  Wi-Fi · Bluetooth · Modo avión · **Protección visual** · Ahorro de energía · Horizontal fijo ·
  No molestar · Linterna · Transmitir. Activo = fondo de acento + ícono `on_accent`.
- Sliders: **Brillo**, **Volumen**, **Escala del escritorio** (60–100 %), con el valor en % a la derecha.
- Pie: batería % y botones Editar / Ajustes.

### Notificaciones y calendario (`QuickSettings.png`, derecha)
- Columna de 340 dp: panel de notificaciones (tarjetas agrupadas por app, "Borrar todo") y debajo un
  calendario mensual compacto (semana empieza en domingo, día actual en círculo de acento).

### Ventanas (`Windows.png`)
- Ventana activa al frente con borde más claro; la inactiva más tenue.
- Redimensionar: contorno punteado en color de acento mientras se arrastra + etiqueta con el tamaño (p. ej. "612 × 444").
- Indicador flotante **"Escala del escritorio"** (−, valor %, +) que aparece al cambiar la escala.
- Barra de título estilo Win11 (min/max/cerrar): solo si el spike lo permite (ver `CLAUDE.md`).

### Íconos del escritorio (`Main.png`, izquierda)
- Columna de accesos (ícono 42 dp + etiqueta con sombra de texto para que se lea sobre cualquier fondo).

## Accesibilidad
- Áreas táctiles de al menos 44 dp en el eje principal.
- Texto con contraste mínimo 4.5:1 sobre el vidrio (por eso el tinte no baja de 0.35 y el texto secundario
  no baja de 82 % de opacidad).
- Todos los botones de solo ícono llevan `contentDescription`.
