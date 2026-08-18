# Launcher Kiosco TGT 65.0 — piloto Sala 3

## Alcance

Esta candidata integra el núcleo mínimo SAFE Bridge/MDM validado sobre la
interfaz V64. Mantiene Device Owner, launcher HOME, modo kiosco, agenda,
aplicaciones, caché offline y motor OTA. SAFE Bridge separa la sincronización
de configuración de la autorización de comandos y conserva estos últimos
desactivados para Sala 3 durante la preparación.

Quedan fuera del núcleo mínimo la megafonía y comunicados remotos, TTS remoto,
peticiones remotas de logs, monitor VoWiFi, controles remotos de hardware,
modo robo, localización, borrado remoto y cualquier OTA general.

## Identidad de la candidata

- Package: `com.grupotgt.launcherkioscotgt`
- `versionCode`: `65`
- `versionName`: `65.0-pilot`
- APK: `LauncherKioscoTGT-v65.0-pilot.apk`
- SHA-256: `A94FC101D169EAD0BFF13877C528530CB868D4F88B847C923DEFE0343D9C5BA5`
- Tamaño: `6630440` bytes
- Certificado SHA-256: `7f92a9d9930d4e6ae633bdee8a0cedc18b6d213ea96200ab98ca67f22ed54751`

## SAFE Bridge

- Deployment: `23`
- Servicio: `3.4.0-device-scoped-pilot-ota`
- Contrato: `1`
- Sala 3: `APPROVED`, `commands_enabled=FALSE`, modo deseado y aplicado
  `BLINDADO` antes de activar la prueba.

La asignación OTA piloto viaja dentro de la respuesta HMAC del heartbeat y
queda ligada al `device_id`, versión, URL, SHA-256, tamaño y caducidad. Una
asignación para Sala 3 no se entrega a ningún otro identificador. El APK
comprueba además hash, tamaño, package, versión superior y certificado antes
de abrir la sesión de PackageInstaller.

## Rollback preparado

- APK: `LauncherKioscoTGT-v66-rollback-to-v64.apk`
- Código funcional: V64 oficial basado en `dd190e6`
- `versionCode`: `66`
- `versionName`: `64.0-rollback`
- SHA-256: `F5A789478703735753874B9641E4599C556039666E6896AEFEBE34097D5CFB82`
- Firma y package: idénticos a V64/V65.

El rollback es exclusivamente de emergencia mediante USB y no se publica en
el canal estable ni se instala salvo fallo acreditado de Sala 3.

## Validación previa

- SAFE Bridge: `SAFE_BRIDGE_TESTS_OK`.
- JVM: 23 pruebas, 0 fallos.
- Build V65: `BUILD SUCCESSFUL`.
- Lint: 0 errores, 236 avisos.
- Permisos: 22 en V64 y V65, diferencia 0.
- Firma APK v2 verificada.
- `MyAdminReceiver` y launcher HOME presentes.
- Canal OTA público conservado en V64.

## Restricciones

Pre-release exclusiva para Sala 3. No distribuir. No instalar manualmente
antes de comprobar el recorrido OTA V64 → V65. La publicación o activación de
una OTA general requiere un bloque y una autorización diferentes.

**OTA GENERAL NO ACTIVADA**
