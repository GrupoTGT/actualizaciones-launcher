# SAFE BRIDGE contract v1

## Deployment

- Apps Script project: `1qv79yC0SqqdzguF0IOgym_wK8kCBM_-tdUFCn2ecXrNLDpZ6MlRifEc0`
- Web App deployment: `AKfycby2-olpj2Y9wryLca77Jd5a01nROHf8C2XvyfU_wlk94DlAjR9mGE81uTwCPLj-x0E5`
- Active deployment version: `4`.
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
