# MDM automated test matrix

| Required scenario | Automated evidence |
| --- | --- |
| Initial and repeated registration | `SafeBridge.test.js` exercises the real bridge functions with in-memory Sheets and proves one terminal/device row. |
| Unknown pending / known linked device | Bridge test covers pending auto-enrolment and later approved binding; JVM response tests verify safe mode/command projection. |
| Invalid signature / replay / expired timestamp | Bridge test invokes the production HMAC, nonce and timestamp checks. |
| Empty / corrupt response | `MdmEnrollmentResponseTest` rejects both. |
| Timeout / 4xx / 5xx | `MdmPolicyTest` verifies terminal versus retryable transport classification. |
| Offline cache / reconnection | Parser rejection plus `MdmCachePolicy` prove stale/conflicting/invalid input keeps the last valid value and a higher revision applies. |
| Profile change | `MdmConfigParserTest` requires a complete explicit snapshot and validates the changed profile. |
| BLINDADO / LIBRE GESTIONADO | `ManagedModeRevisionPolicy` tests monotonic transitions and rejects replayed or contradictory revisions. |
| Agenda and apps without duplicates | Parser tests reject duplicate phone, contact ID, application ID or package; inventory tests deduplicate configured apps. |
| Permitted versus installed apps | `MdmAppInventory` test keeps configured, installed-configured and missing sets separate. |
| Command without ACK | **Partial:** bridge registration preserves `SIN ACK` and telemetry rejects a response without its ACK. A real command queue/ACK path is not implemented yet and remains a pilot blocker. |
| Device Owner preservation | Automated source audit finds no owner-removal API; pre-install ADB separately verifies the actual owner. No destructive owner test is run. |
| Airplane / Wi-Fi without Internet / recovered Internet | `MdmNetworkStateFactory` tests all three states using the same classifier as production. |
| VoWiFi not verifiable | Network test requires the honest `NO VERIFICABLE` value. |

The JVM suite does not claim physical validation. JobScheduler execution, Android
permissions, Device Owner, LockTask, HOME transitions, calls and offline behavior
must still be exercised on Sala 3 before `PASS PILOTO`.
