# Launcher minimalista

Launcher para Android pensado para usar menos el celular: inicio limpio con reloj y favoritos, buscador de apps por texto, y pausas antes de abrir las apps que más te distraen.

## Funciones

- **Inicio**: reloj, fecha y resumen de aperturas del día. Favoritos en tres formatos: **Lista**, **Abanico** (rayos desde la esquina, para el pulgar) o **Curva**. Se pueden usar con la mano derecha o la izquierda, y el abanico y la curva se pueden mover.
- **Buscador**: deslizá hacia arriba y escribí. Si queda una sola app, o el texto coincide exacto con un nombre o alias, se abre sola. Barra de letras al costado para saltar por la lista.
- **Widgets**: deslizá hacia los costados. Inicio y widgets funcionan como un carrusel, con el fondo de pantalla desenfocado detrás.
- **Pausas**: cuenta regresiva antes de abrir ciertas apps, que crece con cada apertura del día. Si ya pasaste la pausa y volvés a la app al rato, no vuelve a esperar hasta que apagues la pantalla.
- **Protección**: apps ocultas (se accede esperando en Ajustes) y apps bloqueadas (diez minutos de espera para abrirlas). Un servicio de accesibilidad opcional impide abrirlas desde Ajustes de Android y permite abrir los ajustes rápidos deslizando hacia abajo.
- **Estadísticas**: aperturas, pausas completadas y canceladas, y uso estimado de pantalla.
- **Apariencia**: estilo Clásico o Retro y cinco temas de color (Café, Azul, Verde, Rojizo, Morado).
- **Copia de seguridad**: exporta e importa en un `.json` los favoritos, alias, demoras, bloqueos, tema, historial y la ubicación de los widgets.

En la app, **Ajustes › Ayuda › Gestos y funciones** explica todos los gestos.

## Compilar

Requisitos: Android Studio reciente (AGP 9.4, Gradle 9.8) y el SDK de Android 37. La app requiere Android 14 o superior.

1. Abrí la carpeta del proyecto en Android Studio y esperá a que termine la sincronización de Gradle.
2. En **Build Variants** elegí `release` para el uso diario (es mucho más fluida que `debug`).
3. Corré la app en un emulador o un celular, o generá el APK con:

   ```
   ./gradlew assembleRelease
   ```

   El APK queda en `app/build/outputs/apk/release/launcher-minimalista-release.apk`.

4. En el celular, elegila como app de inicio predeterminada.

La versión `release` se firma con la clave de debug de cada computadora. Un APK compilado en otra computadora no se puede instalar encima del tuyo (Android exige la misma firma): exportá los ajustes antes de cambiar de compilación.

## Permisos

- **Vincular widgets**: Android lo pide la primera vez que se agrega o restaura un widget.
- **Acceso a datos de uso** (opcional): para el uso estimado de pantalla y para detectar si una app sigue abierta.
- **Servicio de accesibilidad** (opcional): solo mira qué app pasa a primer plano. No lee el contenido de la pantalla ni envía datos.

## Créditos

- Valen ([@valenpf](https://github.com/valenpf)): autor original.
- Santiago ([@SantiagoSQR](https://github.com/SantiagoSQR)).
- Tipografía [Inter](https://github.com/rsms/inter), bajo SIL Open Font License 1.1 (`app/src/main/assets/licenses/inter.txt`).

## Licencia

Copyright © 2026 Valen ([@valenpf](https://github.com/valenpf)) y Santiago ([@SantiagoSQR](https://github.com/SantiagoSQR)).

Este programa es software libre: podés redistribuirlo y modificarlo bajo los términos de la [GNU General Public License versión 3](LICENSE), o (a tu elección) cualquier versión posterior. Si publicás una versión modificada, tiene que estar bajo la misma licencia y con su código fuente disponible.

Se distribuye con la esperanza de que sea útil, pero SIN NINGUNA GARANTÍA. Ver el archivo [LICENSE](LICENSE) para más detalles.
