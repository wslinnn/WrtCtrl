# Third-party notices

This project includes or depends on the following third-party components:

## Bundled assets

- **OUI vendor database** (`app/src/main/assets/oui/vendors.json`) — derived from the macdb project, MIT License. License text at `app/src/main/assets/oui/LICENSE.macdb`.
- **Brand icons** (`app/src/main/res/drawable/vendor_*.xml`) — vector conversions of simple-icons glyphs, CC0 1.0. Three icons (Midea, TCL, GIGABYTE) are supplemental SVGs sourced from Wikimedia rather than simple-icons; sources and attribution at `app/src/main/assets/oui/NOTICE.supplemental`. Trademarks and logos remain property of their respective owners.
- **Firewall zone color hashing** (`app/src/main/kotlin/dev/wrtctrl/util/FirewallColors.kt`) — ported from the LuCI (Apache-2.0) zone color implementation, which is based on SuperFastHash.

## Major dependencies

| Component | License |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Vico (charting) | Apache-2.0 |
| zxing-core (QR) | Apache-2.0 |
| material-kolor | Apache-2.0 |
| reorderable | MIT |
| kotlinx-coroutines | Apache-2.0 |
| DataStore | Apache-2.0 |
| reqwest / tokio / rustls (Rust) | Apache-2.0 / MIT |
| jni (Rust JNI) | MIT / Apache-2.0 |
| serde / serde_json / thiserror | Apache-2.0 / MIT |
| sha2 (RustCrypto) | Apache-2.0 / MIT |

Each dependency's full license text is available in its published source distribution.
