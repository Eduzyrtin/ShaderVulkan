# Google Play: ShaderVulkan

Paquete `com.sanabria.shadervulkan` (sabor `play`) · móvil y tableta · ficha en [`fastlane/metadata/android/`](../fastlane/metadata/android)
(formato estándar, ver §8 de `HERRAMIENTAS/GUIA_PROYECTOS.md`). Idiomas: los 9 de la app; `en-US` es el predeterminado.

- **Precio**: de pago en Google Play, en formato donación (la única app de pago). En GitHub (Releases) es gratuita.

## Qué subir y dónde (Play Console › Crecimiento › Presencia en la tienda › Ficha principal)

1. **Idiomas**: Gestionar idiomas → añade `es-ES`, `fr-FR`, `de-DE`, `it-IT`, `pt-PT`, `ca`, `eu-ES`, `gl-ES`. Pega en cada uno
   `title.txt`, `short_description.txt` y `full_description.txt` de su carpeta. El título no se traduce.
2. **Icono**: `en-US/images/icon.png`.
3. **Gráfico de funciones** (obligatorio): `en-US/images/featureGraphic.png` (vale para todos los idiomas).
4. **Capturas de teléfono**: las 8 de `en-US/images/phoneScreenshots/` (1080×1920 y dos apaisadas de 1920×1080).
5. **Novedades** de cada versión: `<idioma>/changelogs/<versionCode>.txt`.

## Reglas de la ficha

- Sin «RetroArch», «libretro» ni «ShaderGlass» en el título; «compatible con presets slang» solo de forma descriptiva.
- Sin marcas de consolas, emuladores ni apps con DRM; sin enlaces a pagos externos ni «gratis en GitHub».
- Sin promesas que la app no cumple: en Captura se comparte **una sola app**.

## Antes de publicar (lo que Play pedirá)

- **Política de privacidad (URL)**: obligatoria.
- **Seguridad de los datos**: sin permiso de internet; las imágenes de la pantalla se procesan en el dispositivo y no se
  guardan. Se puede declarar «no se recogen datos».
- **Servicio de accesibilidad**: divulgación destacada dentro de la app con aceptación explícita y formulario de
  declaración con vídeo (`docs/play-videos/1_accessibility_disclosure.mp4`). Contar con un primer rechazo y apelar.
  Rechazo del 2026-10-21: «la descripción no refleja el uso de AccessibilityService». Por eso `full_description.txt` lleva,
  justo tras la introducción, el bloque «ACCESSIBILITY SERVICE API» (qué hace, qué no lee, consentimiento, desactivable).
  Hay que pegar esas descripciones en Play Console (el fastlane no las sube solo) y reenviar con el vídeo actualizado.
- **Servicio en primer plano** `mediaProjection` (vídeo `2_media_projection_capture.mp4`) y `specialUse` para Glass
  (vídeo `3_special_use_glass.mp4`). Los vídeos están en `docs/play-videos/`, fuera de git: se suben a YouTube.
- **Clasificación de contenido**: todos los públicos. **Anuncios**: ninguno. Categoría: Herramientas. App de pago.
