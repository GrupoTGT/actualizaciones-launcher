# SAFE BRIDGE contract v1

## Deployment

- Apps Script project: `1qv79yC0SqqdzguF0IOgym_wK8kCBM_-tdUFCn2ecXrNLDpZ6MlRifEc0`
- Web App deployment: `AKfycby2-olpj2Y9wryLca77Jd5a01nROHf8C2XvyfU_wlk94DlAjR9mGE81uTwCPLj-x0E5`
- Preserved rollback deployment: `21`
  (`3.2.7-dashboard-complete-profile-counts`).
- Preserved minimal production deployment: `22`
  (`3.3.0-minimal-production-core`).
- Active device-scoped pilot OTA deployment: `23`
  (`3.4.0-device-scoped-pilot-ota`).
- Endpoint: `https://script.google.com/macros/s/AKfycby2-olpj2Y9wryLca77Jd5a01nROHf8C2XvyfU_wlk94DlAjR9mGE81uTwCPLj-x0E5/exec`
- Execution identity: deploying operator.
- Access: public endpoint; application-level access is restricted by the signed
  contract and unknown devices remain pending with commands disabled.

## Enrollment boundary

Enrollment is deliberately split into two states:

1. A device creates its own random credential and sends a signed enrollment
   request over HTTPS.
2. The server creates or updates the inventory entry as
   `PENDIENTE DE CLASIFICAR`, profile `PENDIENTE_SEGURO`, mode `BLINDADO` and
   commands disabled.
3. An operator compares the credential fingerprint shown by the device with
   `_SB_DEVICES.credential_fingerprint` and performs the single required
   approval.
4. The bridge binds the secret in protected Script Properties only after that
   approval and only when the request fingerprint still matches the approved
   fingerprint. Approval with an empty fingerprint is rejected. Pending
   registrations cannot preclaim a device credential.

An unknown or spoofed device can therefore create a pending inventory row but
cannot obtain configuration that releases the launcher or execute commands.

## Request envelope

Every request contains:

- `contract_version`
- `action`
- `device_id`
- `timestamp_ms`
- `nonce`
- `payload`
- `body_sha256`
- `signature`

The signature is URL-safe Base64 HMAC-SHA256 over the newline-separated
contract version, action, device ID, timestamp, nonce and canonical payload
digest. Object keys are sorted recursively for canonical JSON.

## Replay and credential handling

- Timestamps outside a five-minute window are rejected.
- A nonce is accepted once and recorded in `_SB_NONCES`.
- Credentials are per device; there is no fleet-wide secret.
- Server credentials live in Apps Script Properties, never in cells or logs.
- Android credentials are encrypted at rest using an Android Keystore key.
- A credential already bound to a device cannot be silently replaced.
- Responses are signed with the same per-device credential.

## Managed mode command

An approved enrollment response may include `mode` and `mode_revision`.
Android accepts them only when `approval_state=APPROVED`, `commands_enabled=true`
and the complete response signature is valid. Revisions are monotonic: an older
revision or a different mode reusing the same revision is rejected. Missing or
unknown mode values resolve to `BLINDADO`.

## Safe defaults

- Missing, empty or unknown mode means `BLINDADO`.
- Pending devices receive `PENDIENTE_SEGURO` and commands disabled.
- Enrollment never publishes OTA metadata and never touches Device Owner.

## Device-scoped pilot OTA

An approved terminal may receive `data.pilot_ota` only when `_SB_OTA_ASSIGNMENTS`
contains an unexpired `ACTIVE` row for its exact `device_id`. The signed object
contains `assignment_id`, `device_id`, `version_code`, `version_name`, `apk_url`,
`sha256`, `size_bytes` and `expires_at_ms`. It is delivered independently of
`commands_enabled`, but never to pending or unknown devices.

The allowed URL is restricted to release APK assets below
`https://github.com/GrupoTGT/actualizaciones-launcher/releases/download/`.
Android revalidates device binding, expiry, higher version, byte length, SHA-256,
package and signing certificate before PackageInstaller is invoked. Absence of an
exact assignment means `NO_UPDATE`; the public stable descriptor remains V64.

## Signed offline snapshot

Approved devices receive a signed `config_snapshot` even while remote commands
remain disabled. It is built only from canonical contacts, profile-contact
relations, applications and profile-application relations. The snapshot includes
the bound device ID, profile, terminal, section, contacts, call directions,
permitted apps and a monotonic content revision. Remote settings and the Panel IT
password are not included in this production core.

Android validates structure, identity, uniqueness, package names, phone numbers,
revision and SHA-256 before an atomic commit. Empty, corrupt, unsigned, stale or
same-revision/different-content responses leave the last valid snapshot intact.

## Signed telemetry heartbeat

Android registers a native, persisted `JobScheduler` job requiring network and
running at Android's minimum periodic interval (15 minutes). Boot and package
replacement reconcile the schedule, while foreground synchronization may enqueue
an additional one-shot report. The job is independent of `MainActivity` lifetime.

Telemetry uses the same per-device credential and signed envelope rules as
enrollment. The bridge accepts it only for a registered device, consumes the nonce,
stores an append-only snapshot in `_SB_TELEMETRY`, updates the current inventory,
and returns a signed acknowledgement. For approved devices it also returns the
current signed mode/config directive. Android persists it and requests reconciliation
even when `MainActivity` was not running.

Configuration and command authorization are independent: disabling commands blocks
managed-mode actions but does not stop signed agenda/application synchronization.
Internal activity commands use a bounded multi-token queue, so consecutive actions
cannot overwrite a pending reconciliation token.

When an operator changes the visible managed mode, the bridge atomically increments
the per-device revision and appends a `SET_MANAGED_MODE` entry to `_SB_COMMANDS`.
The command remains `PENDING_ACK` until a later authenticated heartbeat reports the
same applied mode and revision; only then is it marked `ACK_APPLIED` and copied to
`_SB_ACKS`. Telemetry never treats delivery as successful application.

Reported values come from Android system APIs. Unobservable values are explicitly
labelled `NO DISPONIBLE`, `NO VERIFICABLE` or `PERMISO DENEGADO`; in particular,
VoWiFi is not inferred from generic Wi-Fi or telephony connectivity.

Android and Apps Script share a golden canonical-JSON/HMAC vector, including control
characters, Unicode and `</...>` escaping, to prevent cross-runtime signature drift.
