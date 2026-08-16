# Canonical offline cache

`MdmCanonicalConfigCache` stores one complete canonical JSON snapshot, its
device ID, revision and SHA-256 in a synchronous SharedPreferences transaction.
Every load revalidates the hash and schema before exposing the data.

The accepted snapshot is projected into the existing V64 runtime state so the
launcher can continue using its established UI:

- outgoing call buttons use `terminal_can_call`;
- incoming whitelist uses `can_call_terminal`;
- permitted packages remain distinct from installed package telemetry;
- terminal, section, profile, settings and revision survive restart;
- losing Wi-Fi or Internet never clears the snapshot.

The previous CSV cache remains only as a compatibility fallback until a signed
snapshot exists. Its agenda and app data may still be read, but its remote
commands, PIN changes, banners, volume, brightness, timeout and TTS execution are
disabled.
