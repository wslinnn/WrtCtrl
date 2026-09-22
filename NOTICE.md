# Third-party notices

This project includes or depends on the following third-party components:

## Bundled assets

- **OUI vendor database** (`app/src/main/assets/oui/vendors.json`) — derived from the macdb project, MIT License. License text at `app/src/main/assets/oui/LICENSE.macdb`, supplemental notices at `app/src/main/assets/oui/NOTICE.supplemental`.
- **Brand icons** (`app/src/main/res/drawable/vendor_*.xml`) — vector conversions of simple-icons glyphs, CC0 1.0. Trademarks and logos remain property of their respective owners.
- **Firewall zone color hashing** (`app/src/main/kotlin/dev/wrtctrl/util/FirewallColors.kt`) — algorithm compatible with the LuCI (Apache-2.0) zone color implementation.

## Major dependencies

| Component | License |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Vico (charting) | Apache-2.0 |
| zxing-core (QR) | Apache-2.0 |
| material-kolor | Apache-2.0 |
| kotlinx-coroutines | Apache-2.0 |
| DataStore | Apache-2.0 |
| reqwest / tokio / rustls (Rust) | Apache-2.0 / MIT |
| serde / serde_json | Apache-2.0 / MIT |
| sha2 (RustCrypto) | Apache-2.0 / MIT |

Each dependency's full license text is available in its published source distribution.
