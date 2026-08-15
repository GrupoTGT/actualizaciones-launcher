# Dashboard migration report - 2026-08-15

Target: `TGT MDM CONTROL - BASE DEFINITIVA`

Backup created before edits: `TGT MDM CONTROL - BASE DEFINITIVA - COPIA 20260815 2242`

## Reconciliation

| Entity | Expected | Canonical | Duplicates | Result |
| --- | ---: | ---: | ---: | --- |
| Contacts | 26 | 26 | 0 normalized phone numbers | OK |
| Profile/contact relationships | 115 | 115 | 0 profile/contact pairs | OK |
| Applications | 2 | 2 | 0 packages | OK |
| Known terminals | 10 | 10 | 0 device IDs | OK |

The reconciliation is also calculated in `_MDM_MIGRATION_REPORT`. No source
row was deleted. The former visible sheets were preserved as hidden
`ARCHIVO_CUTOVER_*_20260815` sheets and the former technical list as
`_MDM_LISTAS_LEGACY`.

## Provenance

- Nine legacy terminals are displayed as `DATO IMPORTADO / SIN DATO ACTUAL`.
- Sala 3 is the only pilot entry eligible for live telemetry.
- Stale telemetry is displayed as `SIN CONEXION - DATOS CADUCADOS` rather than
  as a current device state.
- Application permission and installation state are distinct.
- Communication sending and remote log requests remain disabled until the
  authenticated SAFE BRIDGE is deployed and tested.

## External preservation evidence

- Official V64 commit: `dd190e6a7688906c91dac38c0e7837374b1247da`.
- Validated UI/call base: `1711f2f26646023e0bb1f44480a7704f60f1f16a`.
- Official V64 APK SHA-256 remains
  `d718d4ed1dc624a74ca5c5088a382c97d23f14b40a0a1b74eeac25aac53aaac9`.
- The previous V64 dashboard was read only during migration.
- No Release, tag, OTA metadata or fleet command was created.
