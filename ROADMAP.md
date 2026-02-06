# Kings — Fork Roadmap

> Fork of [OpenBible2](https://github.com/SchweGELBin/OpenBible2) adding NIP-84 highlights, Nostr relay integration, and Negentropy sync.

## Overview

Kings extends OpenBible2 with the ability to highlight Bible text, attach notes to verses, and store/sync those annotations as Nostr events (NIP-84 `kind:9802`). Data flows through a three-tier relay architecture: an embedded in-process relay (always available), an optional external local relay (e.g., Citrine), and public relays for sharing.

---

## Security Audit Findings (Pre-Fork)

The following issues were identified in the upstream codebase and must be resolved before feature work begins.

| Severity | Issue | Location |
|----------|-------|----------|
| **Critical** | Zip Slip vulnerability in `restoreBackup()` — crafted ZIP can write arbitrary files | `logic/Main.kt:125-151` |
| **High** | All file I/O runs on the main thread — ANR risk | `logic/Main.kt`, `logic/Getters.kt`, `logic/Serialization.kt`, `ui/screens/Main.kt` |
| Medium | No `network_security_config.xml` — cleartext HTTP allowed on API 27 | Missing XML, `AndroidManifest.xml` |
| Medium | Path traversal via unsanitized `abbrev` in file path construction | `logic/Getters.kt:156-157`, `logic/Main.kt:38-44` |
| Medium | `FileInputStream` not closed on exception path | `logic/Getters.kt:128-142` |
| Medium | Custom translation import uses unsanitized abbreviation from user-provided JSON | `ui/screens/Selection.kt:113-126` |
| Medium | Backup ZIPs written to public Downloads without SAF | `logic/Main.kt:101-123` |
| Medium | `allowBackup=true` with unconfigured backup rules | `AndroidManifest.xml` |
| Medium | No file size limits on imported custom translations | `ui/screens/Selection.kt` |
| Medium | Large JSON files read entirely into memory (`readText()`) | `logic/Serialization.kt` |
| Medium | Unchecked array index access in `getChapter()` | `logic/Getters.kt:69` |

---

## Architecture

### Three-Tier Relay Strategy

```
+----------------------------------------------------------+
|                       Kings App                           |
|                                                          |
|  +----------------------------------------------------+  |
|  |           HighlightRepository (facade)              |  |
|  |   saveHighlight()  getHighlights()  publish()       |  |
|  +--------+--------------------------+----------------+  |
|           |                          |                   |
|   +-------v----------+     +--------v--------------+    |
|   | Embedded Relay    |     | Relay Client (OkHttp) |    |
|   | Ktor CIO + Room   |     |                       |    |
|   | ws://127.0.0.1    |     | Connects to:          |    |
|   |   :4870           |     |  - External local     |    |
|   |                   |     |    ws://localhost:4869 |    |
|   | Always available  |     |  - relay.damus.io     |    |
|   +-------+-----------+     |  - relay.primal.net   |    |
|           |                 +--------+--------------+    |
|           |    +---------------------+                   |
|           |    | Negentropy Sync     |                   |
|           +----+ Engine             |                   |
|                |                     |                   |
|                | embedded <-> ext    |                   |
|                | embedded <-> pub    |                   |
|                +---------------------+                   |
+----------------------------------------------------------+
```

- **Embedded relay**: In-process Ktor CIO WebSocket server backed by Room/SQLite. Source of truth. Always available, zero config.
- **External local relay**: User-run relay (e.g., Citrine on `ws://localhost:4869`). Preferred when reachable. Enables other Nostr apps on the device to access highlights.
- **Public relays**: `wss://relay.damus.io`, `wss://relay.primal.net` (configurable). Publish targets for sharing highlights.
- **Negentropy sync** (`kmp-negentropy`): Efficient set reconciliation between tiers. Only transfers event IDs that differ.

### NIP-84 Event Format

```json
{
  "kind": 9802,
  "content": "For God so loved the world...",
  "tags": [
    ["r", "bible:kjv/john/3/16", "source"],
    ["context", "For God so loved the world, that he gave his only begotten Son..."],
    ["comment", "User's note about this passage"],
    ["alt", "Highlight: For God so loved the world..."]
  ]
}
```

The `r` tag uses the scheme `bible:{translation}/{book}/{chapter}/{verse}` for addressable, filterable references.

### Key Management

- **Default**: Generate secp256k1 keypair in-app. Private key (nsec) stored in `EncryptedSharedPreferences`.
- **Advanced**: Delegate signing to an external NIP-55 signer app (e.g., Amber) via Intent.
- Toggle between modes in Settings.

---

## Phases

### Phase 0 — Security Fixes

Resolve all Critical and High findings from the audit before adding new surface area.

| Task | File(s) |
|------|---------|
| Fix Zip Slip: validate ZIP entry paths before extraction | `logic/Main.kt:125-151` |
| Move all file I/O to `Dispatchers.IO` via coroutines | `logic/Main.kt`, `Getters.kt`, `Serialization.kt`, `ui/screens/Main.kt` |
| Add `network_security_config.xml` disallowing cleartext (except localhost for relay) | New: `res/xml/network_security_config.xml`, `AndroidManifest.xml` |
| Sanitize `abbrev` parameter: reject path separators | `logic/Getters.kt`, `logic/Main.kt` |
| Fix `FileInputStream` leak: use `.use {}` | `logic/Getters.kt:128-142` |

### Phase 1 — Nostr Foundation

Core Nostr protocol primitives. No UI changes yet.

| File | Purpose |
|------|---------|
| `logic/nostr/Keys.kt` | Keypair generation (secp256k1), nsec/npub encoding, `EncryptedSharedPreferences` storage |
| `logic/nostr/Event.kt` | `NostrEvent` model, event ID computation (SHA-256), schnorr signing |
| `logic/nostr/Signer.kt` | `Signer` interface with `LocalSigner` (uses stored nsec) and `AmberSigner` (NIP-55 Intent delegation) |
| `logic/nostr/Relay.kt` | OkHttp WebSocket client: connect, send `EVENT`/`REQ`/`CLOSE`, receive events as `Flow` |
| `logic/nostr/RelayPool.kt` | Multi-relay connection manager: routes events, manages reconnection |

**New dependencies**: OkHttp, secp256k1-kmp-jni-android, security-crypto

### Phase 2 — Embedded Relay

In-process Nostr relay for offline-first operation.

| File | Purpose |
|------|---------|
| `logic/nostr/embedded/EmbeddedRelay.kt` | Ktor CIO WebSocket server on `127.0.0.1:4870`. Handles `EVENT`, `REQ`, `CLOSE`. ~200 lines. |
| `logic/nostr/embedded/EventStore.kt` | Room DAO wrapper: insert events, query by NIP-01 filters, delete (NIP-09). ~100 lines. |
| `logic/nostr/embedded/SubscriptionManager.kt` | Track active REQ subscriptions per connection. ~60 lines. |
| `logic/nostr/db/AppDatabase.kt` | Room database definition with `NostrEventEntity` table |
| `logic/nostr/db/NostrEventEntity.kt` | Room entity mapping Nostr event fields + sync metadata |
| `logic/nostr/db/NostrEventDao.kt` | Room DAO: insert, query by kind/tags/author, delete by event ID |

**NIP support**: NIP-01 (core), NIP-09 (deletion), NIP-77 (Negentropy).

**New dependencies**: Ktor server CIO, Ktor server WebSockets, Room (runtime, ktx, compiler via KSP)

### Phase 3 — Negentropy Sync

Efficient set reconciliation between relay tiers.

| File | Purpose |
|------|---------|
| `logic/nostr/sync/NegentropySync.kt` | Negentropy reconciliation engine: builds `StorageVector` from local events, exchanges `NEG-OPEN`/`NEG-MSG`/`NEG-CLOSE` with remote relay, returns have/need ID sets. ~150 lines. |
| `logic/nostr/sync/SyncManager.kt` | Orchestrates sync across relay tiers. Triggers: app launch, relay reconnect, manual publish, periodic (15 min). ~100 lines. |

**New dependency**: `com.vitorpamplona.negentropy:kmp-negentropy:1.0.1` (pure Kotlin, zero transitive deps, MIT)

**Sync triggers**:
- App launch: embedded <-> external local (if reachable)
- User taps "Publish": embedded <-> target public relay
- Periodic: every 15 minutes (if external relay reachable)
- On reconnection after disconnect

### Phase 4 — NIP-84 Highlight Model

Data layer for creating, reading, and managing highlights.

| File | Purpose |
|------|---------|
| `logic/nostr/Highlight.kt` | NIP-84 `kind:9802` event construction and parsing. Reference URI scheme: `bible:{translation}/{book}/{chapter}/{verse}`. |
| `logic/nostr/HighlightRepository.kt` | Facade: `saveHighlight()`, `getHighlights(translation, book, chapter)`, `deleteHighlight(id)`, `publishHighlight(id, relays)`. Reads/writes via embedded relay. |

### Phase 5 — UI: Per-Verse Rendering & Corner Annotations

The core UX change. Current `Read.kt` renders the entire chapter as a single `String`. This phase switches to per-verse rendering with highlight overlays.

**Highlight creation flow**:
1. User long-presses to select text (existing `SelectionContainer` behavior)
2. Custom `TextToolbar` adds a "Highlight" action to the system selection menu
3. Tapping "Highlight" opens a `ModalBottomSheet` with:
   - Selected text preview
   - `TextField` for optional note/comment
   - Save / Cancel buttons
4. Save signs a `kind:9802` event and publishes to embedded relay

**Highlight viewing**:
1. Highlighted verses display a small colored triangle in the upper-right corner
2. Verse text gets a subtle background tint
3. Tapping the triangle opens a `ModalBottomSheet` showing:
   - The highlighted text
   - The user's note/comment
   - Publish to Relays / Edit Note / Delete actions

| File | Purpose |
|------|---------|
| `logic/Getters.kt` (modify) | Add `getVerses()` returning `List<VerseDisplay>` for per-verse rendering |
| `ui/screens/Read.kt` (modify) | Per-verse `Box` composables inside `SelectionContainer`, annotation overlays |
| `ui/components/VerseText.kt` (new) | Per-verse composable with highlight background overlay and selection support |
| `ui/components/HighlightAnnotation.kt` (new) | Upper-right corner triangle indicator drawn via `Canvas`, clickable |
| `ui/components/HighlightBottomSheet.kt` (new) | `ModalBottomSheet` for create/view/edit modes |
| `ui/components/HighlightTextToolbar.kt` (new) | Custom `TextToolbar` injecting "Highlight" action into system selection menu |

### Phase 6 — UI: Settings, Highlights List, Publish Flow

| File | Purpose |
|------|---------|
| `ui/screens/Settings.kt` (modify) | Add "Nostr" section: identity (npub), signer toggle, local relay URL, public relay list, auto-publish toggle |
| `ui/screens/Bookmarks.kt` -> `Highlights.kt` (rename+rewrite) | Highlights list: grouped by book/chapter, shows snippet + note preview + publish status badge. Tap navigates to verse. Swipe/button to publish. Batch "Publish All". |
| `ui/screens/Main.kt` (modify) | Update navigation routes: `Highlights`, `HighlightDetail`, `NostrSettings` |
| `logic/SharedPrefs.kt` (modify) | Add Nostr settings: relay URLs, signer preference, auto-publish flag |
| `res/values/strings.xml` (modify) | Add all highlight/Nostr UI strings |

---

## New Dependencies Summary

| Dependency | Purpose | Est. Size |
|------------|---------|-----------|
| `com.squareup.okhttp3:okhttp` | WebSocket client for external relays | ~800KB |
| `io.ktor:ktor-server-cio` | Embedded WebSocket server | ~2MB |
| `io.ktor:ktor-server-websockets` | Ktor WebSocket plugin | ~200KB |
| `fr.acinq.secp256k1:secp256k1-kmp-jni-android` | Schnorr signing (NIP-01) | ~500KB |
| `androidx.security:security-crypto` | `EncryptedSharedPreferences` for nsec | ~100KB |
| `androidx.room:room-runtime` + `room-ktx` | SQLite for embedded relay storage | ~500KB |
| `com.vitorpamplona.negentropy:kmp-negentropy` | Negentropy set reconciliation | ~50KB |

Total APK size increase: ~4-5MB before R8 (~2-3MB after minification).

## New File Map

```
logic/nostr/
  Keys.kt
  Event.kt
  Signer.kt
  Relay.kt
  RelayPool.kt
  Highlight.kt
  HighlightRepository.kt
  embedded/
    EmbeddedRelay.kt
    EventStore.kt
    SubscriptionManager.kt
  sync/
    NegentropySync.kt
    SyncManager.kt
  db/
    AppDatabase.kt
    NostrEventEntity.kt
    NostrEventDao.kt

ui/components/
  HighlightAnnotation.kt
  HighlightBottomSheet.kt
  HighlightTextToolbar.kt
  VerseText.kt
```

## Effort Estimates

| Phase | Work | Days |
|-------|------|------|
| 0 | Security fixes | 1 |
| 1 | Nostr foundation (keys, events, signing, relay client) | 2 |
| 2 | Embedded relay (Ktor + Room) | 2 |
| 3 | Negentropy sync engine | 1-2 |
| 4 | NIP-84 highlight data model | 1 |
| 5 | UI: per-verse rendering, corner annotations, bottom sheet, text toolbar | 3-4 |
| 6 | UI: settings, highlights list, publish flow | 1-2 |
| **Total** | | **~13-16** |

## References

- [NIP-84: Highlights](https://nips.nostr.com/84) — `kind:9802` event specification
- [NIP-01: Basic protocol](https://nips.nostr.com/1) — Nostr event model and relay protocol
- [NIP-09: Event Deletion](https://nips.nostr.com/9) — `kind:5` deletion events
- [NIP-55: Android Signer](https://nips.nostr.com/55) — External signer app integration
- [NIP-77: Negentropy](https://nips.nostr.com/77) — Set reconciliation for efficient sync
- [Citrine](https://github.com/greenart7c3/Citrine) — Reference Android Nostr relay (MIT, Ktor + Room architecture)
- [negentropy-kmp](https://github.com/vitorpamplona/negentropy-kmp) — Kotlin Multiplatform Negentropy implementation
- [OpenBible2](https://github.com/SchweGELBin/OpenBible2) — Upstream project (Apache 2.0)
