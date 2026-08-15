# TGT MDM canonical data model

This document records the dashboard-side source of truth introduced by
`MDM-V64-SIMPLE-CUTOVER-01`. It intentionally contains no phone numbers,
credentials, tokens, nonces or device secrets.

## Visible operator views

| Sheet | Canonical key | Purpose |
| --- | --- | --- |
| `0_INICIO` | Derived | Fleet totals and explicit data provenance. |
| `1_TERMINALES` | `device_id` | One row per managed device. Unknown devices start as `PENDIENTE DE CLASIFICAR`, profile `PENDIENTE_SEGURO`, mode `BLINDADO`, with commands disabled. |
| `2_AGENDA` | `contact_id` | One row per normalized phone number. Profile permissions are edited without duplicating phone records. |
| `3_APLICACIONES` | package | One row per Android package. `PERMITIDA` and installed state are separate fields. |
| `4_COMUNICACIONES` | `communication_id` | Communication requests and their ACK-derived state. `ENVIADO` is not final without ACK. |
| `5_CONFIGURACION` | `config_id` | Desired values with `GLOBAL -> PERFIL -> TERMINAL` inheritance, separate from device-reported values. |
| `6_LOGS` | append-only event | Filtered operator view of authenticated device logs. Remote log requests remain disabled until the bridge is deployed. |
| `7_PERFILES` | `profile_id` | Profile identity, explicit mode and derived terminal/contact/application counts. |

## Hidden canonical and bridge tables

| Sheet | Role |
| --- | --- |
| `_MDM_CONTACT_PROFILE` | Canonical profile/contact relationships. |
| `_MDM_APP_PROFILE` | Canonical profile/application relationships. |
| `_MDM_CONFIG_DESIRED` | Versioned desired configuration, separate from reported state. |
| `_MDM_ENUMS` | Validation lists used by operator views. |
| `_MDM_MIGRATION_REPORT` | In-sheet reconciliation counts and duplicate checks. |
| `_SB_DEVICES` | Bridge-maintained latest device registration/state. |
| `_SB_TELEMETRY` | Append-only raw telemetry. |
| `_SB_COMMANDS` | Independent command queue. |
| `_SB_ACKS` | Independent command acknowledgements. |
| `_SB_LOGS` | Append-only authenticated logs. |
| `_SB_NONCES` | Replay-prevention state. |
| `_SB_AUDIT` | Security and mutation audit trail. |

## Safety invariants

- Imported legacy values are labelled `DATO IMPORTADO` and never presented as current telemetry.
- Empty or unknown mode values resolve to `BLINDADO`; they never release a device.
- `PENDIENTE_SEGURO` never enables critical commands.
- Desired configuration and device-reported configuration remain separate.
- A command is not confirmed until an authenticated ACK is recorded.
- The IT password is never stored as visible sheet text.
- Legacy source sheets remain archived, hidden and warning-protected.
- The previous V64 fleet dashboard remains read-only and unchanged.
- OTA metadata, tags and releases are outside this block.
