# Managed telemetry heartbeat

## Runtime

- Component: `MdmHeartbeatJobService` (`exported=false`, protected by
  `android.permission.BIND_JOB_SERVICE`).
- Scheduler: persisted periodic job every 15 minutes with network required and
  exponential retry; an independent immediate job is used after foreground sync.
- Authentication: device-specific HMAC-SHA256 credential stored encrypted with
  Android Keystore, timestamp window, one-use nonce, canonical body SHA-256 and
  signed server acknowledgement.
- The heartbeat never changes HOME, LockTask, Device Owner or OTA state directly.
  It may persist a signed agenda/application snapshot and request idempotent launcher
  reconciliation. Managed-mode directives are accepted only when commands are enabled.

## Measured data

The payload includes battery and charging state, hardware/Android/app versions,
active IPv4 address, Wi-Fi and validated Internet state, SSID/RSSI when permitted,
mobile transport, airplane mode, brightness, media volume, Device Owner,
LockTask, uptime, storage, persisted transition state/error, validated agenda cache
counts and configured-versus-installed managed applications.

VoWiFi does not have a reliable public API for this device-owner application and
is therefore reported as `NO VERIFICABLE`. Missing, restricted or unavailable
values are never converted into an affirmative state.

## Server storage

The active device-scoped pilot deployment is version `23`, service
`3.4.0-device-scoped-pilot-ota`. It updates the latest state in
`_SB_DEVICES` and `1_TERMINALES`, appends normalized records to `_SB_TELEMETRY`,
and records only a compact success/error audit entry. Secrets and raw signed
requests are not written to spreadsheet cells or logs.

Approved heartbeat responses carry a canonical agenda/application snapshot even
with commands disabled. When commands are enabled they may additionally carry the
current mode revision and command identity. A changed snapshot or directive is
persisted before Android opens the launcher for idempotent reconciliation. The next
measured heartbeat is the mode ACK; receipt alone never closes a command.

The V65 pilot extension `3.4.0-device-scoped-pilot-ota` may additionally return
one HMAC-protected `pilot_ota` object for the exact approved `device_id`. The
heartbeat persists only a valid, unexpired assignment and requests the internal
one-use `APPLY_PILOT_OTA` action. No matching row means no OTA action.
