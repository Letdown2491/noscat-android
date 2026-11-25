# AGENTS.md

Guidance for AI agents working in this repo. Read this file fully before making changes.

## What this project is

The Android client for [noscat](https://github.com/Letdown2491/noscat), a Nostr-native device
mesh (0.5: Marmot groups on Nostr relays for membership, FIPS with QUIC streams for
data). Identity is an npub. This repo is
native Kotlin (Compose) on top of the upstream `noscat-ffi` UniFFI bridge crate; the
protocol and the FFI layer live in the noscat workspace, never in this repo.

**Phase 1 (current scope): remote-reach client only.** The phone dials out: chat
with peers, send/receive files, forward a peer's services to local ports, and join
a mesh by scanning a member's QR (send it a join request, get invited, join).

Phase 2: expose/serve (phone publishes services peers can dial). The FFI already
has `up()` for node mode when this starts.
Phase 3: full VPN via Android `VpnService` + a userspace IP stack. Shelved; do not
start it without an explicit ask. It is Kotlin/Java-only, which is one reason the app
layer stays native Kotlin instead of a cross-platform framework.

## Non-negotiable rules

1. **Zero protocol code in this repo.** If a change needs FIPS, QUIC streams, Marmot, Nostr
   events, relay logic, advert handling, or FFI surface changes, it belongs in the
   noscat repo's `crates/noscat-ffi`, published to crates.io, then bumped here.
   To try unreleased noscat changes, build from the local checkout
   (`scripts/build-native.sh local`); releases use the published crate.
   The upstream API contract is `API.md` in the
   [noscat repo](https://github.com/Letdown2491/noscat/blob/main/API.md): consult
   it before touching event handling, mesh glue, or the FFI surface. Its
   "Current as of" stamp must be >= the noscat-ffi version in this repo's build.
2. **Kotlin sees only safe surfaces:** identities (npub strings), plain commands
   (`forward`, `sendFile`, `sendChat`, `up`), the mesh helpers
   (`meshPrepare`/`meshJoin`/`meshSync`/`meshMembers`), and the
   `EventListener` callback. Never leak ports-as-socket-addrs, hex keys beyond what
   the UI shows, or relay URLs into app logic unless a feature genuinely needs them.
3. **No em or en dashes anywhere in the repo, and no hyphen standing in for one**
   (" - " between words): use a colon, semicolon, comma or parentheses. Applies to
   code comments, UI text, docs, commit messages, everything.
4. **Rust builds run only inside the podman container.** The host is a bootc system
   with no Rust toolchain. Do not try `cargo` on the host.
5. **Events arrive on a background thread.** `EventListener.on_event` is called from a
   Rust-spawned thread. Kotlin must hop to the main thread before touching UI state.
6. **No FFI call on the main thread.** Even a trivial getter (`npub()`) can stall
   behind the node's relay work inside the Rust locks and ANR the app. Cache values
   at client start (`ClientHolder.myNpub`) and run sends on `Dispatchers.IO`.
7. **GitHub pushes go over SSH** (`git@github.com:...`), not HTTPS; the stored HTTPS
   credential cannot push workflow files.

## Layout

- `app/`: the Android app. `MainActivity.kt` holds the screens: the join flow
  (mesh invite by QR), and a five-tab layout with Devices in the center and
  slightly larger: Chat (conversation list and bubbles), Files (Received:
  the inbox with each file's sender; Sent: the last 100 sends with Send
  again; progress bars both ways), Devices (roster with per-row actions in the
  desktop app's order: chat, send-file, forward, clipboard), Services (the
  port forwards this phone is running, each stoppable; expose joins this
  tab in phase 2), Settings (status, device name, this phone's QR, and
  diagnostics: the event log, collapsed until tapped, with Copy log). Disconnect
  lives on the connection chip itself (click it, on any tab's header) plus
  a button in Settings, both behind a confirm dialog. Disconnect keeps the
  phone a member: `ClientHolder.disconnect` closes the client and sets the
  `disconnected` pref, so it stays offline across restarts until Reconnect
  (chip or Settings). Forget mesh (Settings) is `ClientHolder.forget`:
  `meshLeave` (30 s; with no member online it forgets locally and the next
  member online completes the removal), then it deletes `filesDir/device`
  and `members.json` and drops to the join flow. If leaving fails, nothing
  is deleted. Clients are stopped with `close()`, never left to the
  garbage collector: a collected-later client still holds the directory.
  `ClientHolder.kt` owns the running client and exposes client lifetime,
  roster cache, chat history, unread counts, forwards, and transfer state
  as Compose snapshot state.
  The roster persists to `filesDir/members.json` so startup renders the
  device list instantly. The running client follows the mesh live, so
  `meshSync`/`meshMembers` read its members (names and `lastSeenSecs`
  presence) without a relay round trip; `ClientHolder` syncs on a 2
  minute timer, when listening starts, and on the `MeshJoined`,
  `Roster`, `MemberSeen` and `MeshLeft` events. The dot is green when
  `MeshMember.connected` (a direct link or live connection; the client
  re-dials online members every 30 s and emits `Roster` on every
  connection change; never saved), amber when only the advert is fresh
  (under ten minutes, aged forward from `rosterAgeBase` on a 30 s tick),
  red otherwise.
  The device lives in `filesDir/device` (pref `device_dir`). Creating a mesh from the phone needs UI that does not
  exist yet; a CLI device creates it. Inviting is approving a join request
  (see Runtime behavior). The join screen's `meshJoin` runs
  a temporary device for `filesDir/device`; it is released when the call
  returns, and only then does `ClientHolder.restart` start the app's
  client there (one device per directory).
- `app/.../NoscatService.kt`: the foreground service that keeps the client
  alive while the app is in the background.
- `scripts/build-native.sh`: cross-compiles the `noscat-ffi` crate
  for arm64, strips the .so into `app/src/main/jniLibs/`, and regenerates the
  Kotlin bindings. `local` builds the `~/Documents/noscat` checkout.
- `app/src/main/java/uniffi/`: generated Kotlin bindings (git-ignored,
  regenerated by the script at build time).

## Commands (verified)

Native bridge: cross-compile `noscat-ffi` for arm64 (a published version, or the
local noscat checkout for unreleased changes), strip, and regenerate the Kotlin bindings (a few minutes
on a warm cache). The Rust build is the debug profile unless `PROFILE=release`
(LTO; built with symbols because uniffi reads the bindings from them, stripped
afterwards). `scripts/build-release.sh` does that plus a signed `assembleRelease`
(signing from the git-ignored `keystore.properties`; the keystore lives outside the
repo, never commit either). Version: `versionName` follows noscat (0.5.0),
`versionCode` goes up every release.

```
./scripts/build-native.sh local    # or a version number for a published crate
```

Install on a device (wireless debugging works; `adb` is not on PATH):

```
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin: build the app with the Gradle wrapper (committed). The host has no system
JDK; use the tarball JDK in `~/opt`:

```
JAVA_HOME=$(echo ~/opt/jdk-21*) ./gradlew :app:assembleDebug --no-daemon
```

Toolchain on this machine (installed by hand, nothing on the immutable host):
- JDK 21 and Gradle 8.10.2 unpacked in `~/opt` (Gradle was only needed to generate
  the wrapper; use `./gradlew` for everything else).
- Android SDK at `~/Android/Sdk` (installed by Studio's first-run wizard), with
  `local.properties` pointing at it. `cmdline-tools/latest` lives inside the SDK;
  platforms 35 and 37 and build-tools 35/36 are installed. Licenses already accepted;
  to add packages:
  `JAVA_HOME=$(echo ~/opt/jdk-21*) ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager 'platforms;android-XX'`

UniFFI gotchas already paid for; do not regress:
- Kotlin bindings need JNA: `implementation("net.java.dev.jna:jna:5.17.0@aar")` in
  `app/build.gradle.kts` (the `@aar` classifier only; the plain jar duplicates
  classes and fails the build).
- Kotlin package comes from `crates/noscat-ffi/uniffi.toml` in the noscat repo
  (`[bindings.kotlin] package_name` = `uniffi.noscat`), not a bindgen flag.
- UniFFI 0.29 Kotlin: Rust constructors map to real Kotlin constructors
  (`NoscatClient(...)`, not `NoscatClient.new(...)`), flat errors surface as
  `NoscatException`, `u16` maps to `UShort`, `u64` to `ULong`, record fields are
  camelCased (`this_device` -> `thisDevice`), and free functions become top-level
  `generateIdentity()` / `identityFromSecret()` / `meshJoin(...)` etc. Check
  the generated file when the Rust surface changes.

## Upgrading noscat

The FFI crate is `crates/noscat-ffi` in the noscat workspace, version-locked to
the whole workspace (one version bump per release, all crates together). To pick
up changes: publish/bump there, update the version (or checkout path) in
`scripts/build-native.sh`, rerun it, and check whether the FFI surface grew; the
Kotlin bindings regenerate with it.

## Runtime behavior the app must handle (phase 1)

- Members advertise inside the group every two minutes (the client does this). On
  network change (Wi-Fi to cellular) `ClientHolder.watchNetwork` (a
  `ConnectivityManager` default-network callback registered in `init`) calls
  `networkChanged()` when the default network changes or its addresses do; expect
  connections to drop (`Event.TunnelClosed`) and come back on next use. noscat
  retries a failed chat send once on a fresh connection.
- Background survival needs the foreground service (`NoscatService`, started from
  `MainActivity`); without it, Android kills the UDP sockets when the app is
  backgrounded.
- File transfers complete asynchronously: `sendFile` returns the transfer id;
  `TransferStarted`, `TransferProgress` (both directions) and the outcome
  arrive as events carrying it. `ClientHolder.transfers` keeps them, with the
  history in `noBackupFilesDir/transfers.json` (cleared by Forget mesh).
  Inbound files land in the app inbox automatically.
- A rename in Settings calls `ClientHolder.rename`: saved to prefs and sent
  to the running client (`setDeviceName`), no restart.
- Join requests (`Event.JoinRequest`) wait in `ClientHolder.joinRequests`
  and show as cards on Devices with the asker's short ID; Approve calls
  `invite` off the main thread.
- `Notifier` posts notifications on three channels (Messages, Files, Join
  requests): chat unless that conversation is on screen in the foreground
  (`visibleChat`, `inForeground`), files received in the background, and
  join requests. Tapping one opens the chat or tab (`NavRequest`; the
  activity is singleTop). POST_NOTIFICATIONS is asked at first start.
  Foreground pop-ups name devices, never npubs (`nameOf`, `scrub`).
- The device files (`identity.nsec`, `mesh-state.json`) are sealed at rest:
  `ClientHolder.init` gets a 32-byte storage key from
  `IdentityStore.storageKey` (random, wrapped by the Keystore AES key, kept
  in prefs) and calls `setStorageKey` before anything touches the device
  dir. Plain files migrate on start. The app never reads `identity.nsec`:
  the client config passes no secret with a mesh dir. An unreadable key is
  never replaced (a new one would not open the files); only Forget mesh
  recovers.
- Backup and device-to-device transfer are off (`allowBackup="false"`,
  `res/xml/data_extraction_rules.xml` excludes everything): a restored
  device dir would make two phones the same device with diverging mesh
  state. A new phone joins as a new device.
- Chats persist in `noBackupFilesDir/chats.json` (500 per conversation,
  saved debounced); Forget clears them.
- Chat is asynchronous too: sends (`sendChat`, `sendClipboard`) are
  fire-and-forget; the optimistic bubble is marked delivered by the
  `ChatDelivered` event (the member acknowledged it), never by the call returning.
  Failure surfaces as `ChatFailed`; either event settles only the newest
  pending bubble. Tapping a failed bubble is `ClientHolder.retryChat` (the
  failed bubble goes, the text is sent as a new message); long-press opens
  Delete (`deleteChat`, this phone only) and Retry. Nothing is resent
  automatically: the member may have it. Incoming messages arrive as
  `ChatMessage`.
- Order: `displayOrder()` sorts the roster as noscat's `members()` does (by
  name, then unnamed; presence never reorders), because the roster keeps a
  cached name when a fetch has none. This phone's card is first on Devices.
  The chat list shows every member, latest conversation first, then device
  order.
- An outgoing send can be cancelled: `cancelTransfer(path, peer)` stops it
  between chunks (`TransferCancelled` event); the receiver keeps a
  resumable partial, so the next send of the same file continues.
- Port forwards are managed, not write-only: `forward()` returns an id,
  `listForwards()` snapshots them, `cancelForward(id)` stops one, and every
  end arrives as `ForwardStopped{id, error}` so the Services list
  reconciles.

## Conventions

- Kotlin: package `io.github.letdown2491.noscat`, Compose + Material 3, minSdk 26.
- Commit messages: imperative, no em dashes.
- When Gradle work happens, prefer lockfiles and pinned versions so builds are
  reproducible from a clean checkout.
- Work on `master`; commit and push there. No tags until the 0.5.0 release.
