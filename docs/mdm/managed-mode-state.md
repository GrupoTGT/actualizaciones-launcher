# Managed mode state

The APK supports two persisted states without removing Device Owner:

- `BLINDADO`: TGT HOME is enabled and verified, the Device Owner allowlist is
  applied, and MainActivity enters LockTask.
- `LIBRE GESTIONADO`: LockTask is stopped, its allowlist is emptied, One UI Home
  is fixed and verified, and only then is the TGT HOME alias disabled.

The desired mode and revision come only from an authenticated SAFE BRIDGE
response. Android persists desired mode, desired revision, applied mode, applied
revision, transition phase and last error. An interrupted `PENDING`, `APPLYING`
or `ERROR` transition is reconciled idempotently from the persisted desired
state on activity start, resume, boot and package replacement.

Temporary IT maintenance remains separate. When it ends, the APK restores the
authenticated managed mode rather than assuming that every terminal must return
to LockTask.

## Local privileged commands

MainActivity must remain exported because it is the launcher activity. Privileged
extras for IT maintenance, maintenance closure and manual OTA therefore require a
random one-use token issued by the non-exported IT activity. Only a SHA-256 hash
is persisted, tokens expire after 30 seconds, and successful consumption is
atomic. An external explicit intent without the token is logged and rejected.
