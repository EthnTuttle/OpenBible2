# CLAUDE.md — Project Context for AI Assistants

## What This Project Is

Kings is a fork of [OpenBible2](https://github.com/SchweGELBin/OpenBible2), a Kotlin/Jetpack Compose Android app for reading Bible translations. The fork adds NIP-84 highlight/annotation functionality backed by the Nostr protocol, with an embedded relay for offline-first operation and Negentropy for efficient sync.

See `ROADMAP.md` for the full implementation plan and `AGENTS.md` for build commands and upstream code style guidelines.

## Build & Run

```bash
./gradlew assembleDebug            # Build debug APK
./gradlew assembleRelease          # Build release APK (R8 minified)
./gradlew test                     # Run unit tests
./gradlew connectedAndroidTest     # Run instrumentation tests (needs emulator/device)
./gradlew installDebug             # Install debug APK to connected device
./gradlew clean                    # Clean build artifacts
```

Run a single test class:
```bash
./gradlew test --tests com.schwegelbin.openbible.logic.SomeTest
```

## Project Structure

```
app/src/main/kotlin/com/schwegelbin/openbible/
  MainActivity.kt              # Single-activity entry point, sets up Compose + theme
  logic/
    Main.kt                    # Downloads, backup/restore, search, deep links
    Getters.kt                 # Data retrieval: translations, chapters, checksums
    Serialization.kt           # JSON data models (Bible, Verse, Translation) + deserialization
    SharedPrefs.kt             # SharedPreferences read/write, settings enums
    nostr/                     # [NEW] Nostr protocol layer
      Keys.kt                  # Keypair gen, nsec/npub, EncryptedSharedPreferences
      Event.kt                 # NostrEvent model, ID computation, signing
      Signer.kt                # Signer interface: LocalSigner, AmberSigner (NIP-55)
      Relay.kt                 # OkHttp WebSocket client for external relays
      RelayPool.kt             # Multi-relay connection manager
      Highlight.kt             # NIP-84 kind:9802 event construction/parsing
      HighlightRepository.kt   # CRUD facade over embedded relay
      embedded/
        EmbeddedRelay.kt       # Ktor CIO WebSocket server on 127.0.0.1:4870
        EventStore.kt          # Room DAO wrapper for relay storage
        SubscriptionManager.kt # Active subscription tracking
      sync/
        NegentropySync.kt      # Negentropy set reconciliation engine
        SyncManager.kt         # Orchestrates sync across relay tiers
      db/
        AppDatabase.kt         # Room database definition
        NostrEventEntity.kt    # Room entity for Nostr events
        NostrEventDao.kt       # Room DAO
  ui/
    screens/
      Main.kt                 # NavHost, route definitions, deep links
      Read.kt                 # Bible reading screen (pinch-zoom, split screen)
      Selection.kt            # Translation/book/chapter picker
      Search.kt               # Full-text search
      Settings.kt             # App settings
      Highlights.kt           # [NEW] Highlights list (replaces Bookmarks.kt)
      Start.kt                # Initial setup / onboarding
    components/               # [NEW] Reusable UI components
      VerseText.kt            # Per-verse composable with highlight overlay
      HighlightAnnotation.kt  # Corner triangle indicator
      HighlightBottomSheet.kt # Create/view/edit highlight bottom sheet
      HighlightTextToolbar.kt # Custom TextToolbar with "Highlight" action
    theme/
      Color.kt                # Material3 color palette
      Theme.kt                # OpenBibleTheme composable
      Type.kt                 # Typography
```

## Key Technical Details

### Android Configuration
- **Package**: `com.schwegelbin.openbible`
- **Target SDK**: 36, **Min SDK**: 27
- **Java/JVM**: 21
- **Compose BOM**: 2026.01.01
- **Kotlin**: 2.3.0

### Data Sources
- Bible translations: `https://api.getbible.life/v2/{abbrev}.json`
- Translation index: `https://api.getbible.life/v2/translations.json`
- Files stored in `context.getExternalFilesDir()`

### Nostr Integration
- **NIP-84**: Highlights are `kind:9802` events. Content is the highlighted text. Tags include `r` (Bible reference URI), `context`, `comment`, and `alt`.
- **Reference URI scheme**: `bible:{translation}/{book}/{chapter}/{verse}` (e.g., `bible:kjv/john/3/16`)
- **Embedded relay**: Ktor CIO on `ws://127.0.0.1:4870`. Room/SQLite backend. Supports NIP-01, NIP-09, NIP-77.
- **External local relay**: User-configurable, default `ws://localhost:4869` (Citrine).
- **Public relays**: `wss://relay.damus.io`, `wss://relay.primal.net`. User-configurable list.
- **Signing**: secp256k1 schnorr. Private key in `EncryptedSharedPreferences`. Optional NIP-55 Amber delegation.
- **Sync**: Negentropy (`kmp-negentropy`) for efficient set reconciliation between embedded, external, and public relays.

### Dependencies (New for Fork)
- `com.squareup.okhttp3:okhttp` — WebSocket client
- `io.ktor:ktor-server-cio` + `ktor-server-websockets` — Embedded relay server
- `fr.acinq.secp256k1:secp256k1-kmp-jni-android` — Schnorr signing
- `androidx.security:security-crypto` — Encrypted key storage
- `androidx.room:room-runtime` + `room-ktx` — SQLite ORM for embedded relay
- `com.vitorpamplona.negentropy:kmp-negentropy` — Negentropy sync

## Code Conventions

These conventions extend the upstream guidelines in `AGENTS.md`.

### General Rules
- 4-space indentation, no tabs. Max 120 character lines.
- Prefer `val` over `var`. Use type inference where obvious.
- `@Serializable` on all data classes that cross serialization boundaries.
- All user-facing strings in `res/values/strings.xml` via `stringResource()`.
- All dependencies declared in `gradle/libs.versions.toml` version catalog.

### Nostr-Specific Conventions
- All Nostr event construction goes through `Event.kt` — never build raw JSON manually.
- All relay communication goes through `Relay.kt` / `RelayPool.kt` — no raw WebSocket calls elsewhere.
- All highlight CRUD goes through `HighlightRepository.kt` — UI code never talks to relays directly.
- Private keys (nsec) must only exist in `EncryptedSharedPreferences` — never logged, never in regular SharedPrefs, never in event content.
- Relay URLs must be validated before use: require `ws://` or `wss://` scheme.
- All relay I/O must run on `Dispatchers.IO` — never on the main thread.
- Negentropy sync is fire-and-forget from the UI's perspective — no blocking the user.

### Compose Conventions
- Per-verse rendering: each verse is a `Box` with text + optional annotation overlay.
- Highlight state: fetched from embedded relay on chapter load, cached in `remember`.
- Bottom sheets use `ModalBottomSheet` from Material3.
- Custom `TextToolbar` injects "Highlight" action — no custom gesture detectors for selection.
- Corner annotations use `Canvas` drawing, not image assets.

### Error Handling for Relay Operations
- Relay connection failures are non-fatal — the embedded relay is always available.
- Public relay `["OK", id, false, "reason"]` responses are surfaced as snackbar messages.
- Negentropy `NEG-ERR` responses are logged and the sync is silently abandoned (will retry later).
- Signing failures (Amber not installed, user rejected) cancel the operation with a user-visible message.

### Testing Strategy
- Unit tests for: event ID computation, signing/verification, NIP-84 tag construction, reference URI parsing, Negentropy storage vector building.
- Integration tests for: embedded relay EVENT/REQ/CLOSE cycle, highlight CRUD through repository.
- UI tests for: highlight creation flow, annotation tap → bottom sheet, publish action.
- Tests go in `app/src/test/` (unit) or `app/src/androidTest/` (instrumentation).

## Known Issues from Upstream

These are documented in `ROADMAP.md` Phase 0 and must be fixed before feature work:

1. **Zip Slip vulnerability** in `restoreBackup()` (`logic/Main.kt:125-151`) — validate ZIP entry paths
2. **Main-thread I/O** — all file/network ops need `withContext(Dispatchers.IO)`
3. **No network security config** — add `network_security_config.xml`
4. **Unsanitized `abbrev`** in file paths — reject path separators
5. **FileInputStream resource leak** — use `.use {}`

## References

- [NIP-84](https://nips.nostr.com/84) — Highlights (`kind:9802`)
- [NIP-01](https://nips.nostr.com/1) — Basic Nostr protocol
- [NIP-09](https://nips.nostr.com/9) — Event deletion
- [NIP-55](https://nips.nostr.com/55) — Android signer application
- [NIP-77](https://nips.nostr.com/77) — Negentropy syncing
- [Citrine](https://github.com/greenart7c3/Citrine) — Reference Android embedded relay
- [negentropy-kmp](https://github.com/vitorpamplona/negentropy-kmp) — Kotlin Negentropy library
