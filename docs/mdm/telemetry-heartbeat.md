# Managed telemetry heartbeat

## Runtime

- Component: `MdmHeartbeatJobService` (`exported=false`, protected by
  `android.permission.BIND_JOB_SERVICE`).
- Scheduler: persisted periodic job every 15 minutes with network required and
  exponential retry; an independent immediate job is used after foreground sync.
- Authentication: device-specific HMAC-SHA256 credential stored encrypted with
  Android Keystore, timestamp window, one-use nonce, canonical body SHA-256 and
  signed server acknowledgement.
- The heartbeat never changes managed mode, HOME, LockTask, Device Owner,
  configuration, OTA state or command state.

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

The active SAFE BRIDGE deployment version is 6. It updates the latest state in
`_SB_DEVICES` and `1_TERMINALES`, appends normalized records to `_SB_TELEMETRY`,
and records only a compact success/error audit entry. Secrets and raw signed
requests are not written to spreadsheet cells or logs.
