# Luna para Android

Explorador de archivos para el móvil, hermano de [Luna de escritorio](https://github.com/julssvanderhaack/luna)
(C++/Qt6). Mismo nombre, mismo gato siamés y los mismos temas de Omarchy que
[Sonata para Android](../sonata-android), pero pensado para el dedo: accesos rápidos al estilo
de «Mis archivos» de Samsung y un menú de pulsación larga con las opciones del escritorio.

| Inicio | Pulsación larga | Servidor SFTP |
|---|---|---|
| ![](docs/capturas/inicio.png) | ![](docs/capturas/menu.png) | ![](docs/capturas/servidor.png) |

## Qué hace (v0.1)

- **Inicio**: recientes (sin archivos de apps, ocultos ni `Android/`), accesos rápidos a
  Cámara, Descargas, Imágenes, Vídeos, Audio, Documentos, APK y Comprimidos, almacenamiento
  con espacio libre, favoritos y red.
- **Pendrives, discos USB y tarjetas SD** aparecen solos al conectarlos (y se van al
  quitarlos), con su espacio. Se navegan y escriben como el almacenamiento interno.
  Android no deja a las apps desmontar: el botón de expulsar abre los ajustes de almacenamiento.
- **Pulsación larga** (o ⋮ en cada fila): Abrir, Abrir con…, **Abrir como…** (texto, imagen,
  audio, vídeo, PDF u otro), Compartir, Seleccionar, Copiar, Cortar, Renombrar, Eliminar,
  Extraer aquí (zip), Comprimir…, favoritos, Abrir la carpeta que lo contiene, Copiar ruta y
  Propiedades. Selección múltiple con barra de Copiar / Mover / Compartir / Eliminar / Más.
- Copiar y mover entre cualquier sitio: interno ↔ USB ↔ servidores SFTP. Los nombres que ya
  existen pasan a «nombre (2)», como al pegar en el escritorio. Progreso con cancelar.
- Lista o cuadrícula (recordado aparte para fotos/vídeos y para carpetas), ordenar por
  nombre natural / fecha / tamaño / tipo, ocultos, búsqueda en subcarpetas sin distinguir
  acentos, migas de pan, tirar para recargar. Miniaturas de fotos, vídeos, carátulas, PDF y APK.
- **Cliente SFTP**: servidores guardados con contraseña (o pedida cada vez) o clave privada
  (`id_ed25519`, `id_rsa`… con frase opcional). La huella del servidor se enseña la primera
  vez y se avisa si cambia. Los archivos remotos se abren descargándolos a la caché.
- **Servidor SFTP en el móvil** para que el PC entre: `sftp://luna@<ip>:2222/` en Nautilus o
  en Luna de escritorio, o `sftp -P 2222 luna@<ip>`. Contraseña aleatoria fácil de teclear,
  claves públicas autorizadas, carpeta raíz (interno o un USB), modo solo lectura. Solo SFTP:
  sin terminal ni redirección de puertos. Notificación con «Detener».
- Los 27 temas de Omarchy, editor de temas e importar/exportar `colors.toml` (de Sonata). Los
  colores de cada tipo de archivo salen de los colores de terminal del tema.

## Compilar

Android 11+ (minSdk 30: el permiso «acceso a todos los archivos» es lo que deja usar los
USB por ruta). Kotlin + Compose, [Apache MINA SSHD](https://mina.apache.org/sshd-project/)
para el cliente y el servidor SFTP.

```sh
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug testDebugUnitTest
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease   # firma con ~/.android/luna-release.properties
```

Los tests de JVM levantan el servidor de Luna y conectan el cliente de Luna contra él
(contraseña, clave pública, huella nueva y cambiada, solo lectura), además de copiar,
mover, comprimir y extraer de verdad.

`tools/import-omarchy-themes.sh` vuelve a copiar los temas instalados.

## Pendiente

- Papelera (MediaStore la tiene para fotos/vídeos/audio).
- Discos NTFS que el móvil no monta (haría falta libaums o similar).
- Copias largas en un servicio en primer plano (ahora siguen al salir de la pantalla, no si
  Android mata la app).
- Más protocolos (SMB/WebDAV), textos en inglés.

## Licencia

GPL-3.0, como Luna de escritorio. Los temas son de sus autores en Omarchy.
