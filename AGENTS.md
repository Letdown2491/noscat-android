# AGENTS.md

Guidance for AI agents working in this repo. Read this file fully before making changes.

## What this project is

The Android client for [noscat](https://github.com/Letdown2491/noscat), a Nostr-native mesh
VPN (Noise XK over UDP, Nostr as the control plane). Identity is an npub. This repo is
native Kotlin (Compose) on top of a small Rust UniFFI bridge; the protocol itself lives
in the published `noscat-core` and `noscat-client` crates, never in this repo.

**Phase 1 (current scope): remote-reach client only.** The phone dials out: open
`forward` tunnels to a peer running `expose`, and send/receive files. No inbound
serving from the phone yet.

Phase 2: expose/serve (phone publishes services peers can dial).
Phase 3: full VPN via Android `VpnService` + a userspace IP stack. Shelved; do not
start it without an explicit ask. It is Kotlin/Java-only, which is one reason the app
layer stays native Kotlin instead of a cross-platform framework.

## Non-negotiable rules

1. **Zero protocol code in this repo.** If a change needs Noise, UDP framing, Nostr
   events, relay logic, or advert handling, it belongs in the noscat repo
   (`noscat-core` / `noscat-client` crates), published to crates.io, then bumped here.
2. **The FFI surface is `crates/noscat-ffi` in the noscat workspace.** It was moved
   out of this repo so it evolves in lockstep with the protocol and ships as the
   published `noscat-ffi` crate. Until the app consumes the published crate, the
   transitional bridge in `rust/` must stay surface-identical to it: every change
   to the FFI lands in `crates/noscat-ffi` first, then mirrors here. Once the app
   pins the published `noscat-ffi`, `rust/` is retired.
2. **Kotlin sees only safe surfaces:** identities (npub strings), plain commands
   (`forward`, `expose`, `sendFile`, `receiveFile`), and the `EventListener` callback.
   Never leak ports-as-socket-addrs, hex keys beyond what the UI shows, or relay URLs
   into app logic unless a feature genuinely needs them.
3. **No em dashes anywhere in the repo** (use plain hyphens). Applies to code comments,
   docs, commit messages, everything.
4. **Rust builds run only inside the podman container.** The host is a bootc system
   with no Rust toolchain. Do not try `cargo` on the host.
5. **Events arrive on a background thread.** `EventListener.on_event` is called from a
   Rust-spawned thread. Kotlin must hop to the main thread before touching UI state.
6. **GitHub pushes go over SSH** (`git@github.com:...`), not HTTPS; the stored HTTPS
   credential cannot push workflow files.

## Layout

- `rust/` - the transitional UniFFI bridge crate (`noscat-android`), kept only
  until the app consumes the published `noscat-ffi` crate from the noscat
  workspace (see rule 2). The canonical FFI surface lives there; keep this one
  surface-identical.
- `rust/bindings/` - generated Kotlin (git-ignored, regenerated at build time).
- `app/` - the Android app. `MainActivity.kt` is currently a smoke-test surface
  (identity generation + event log), not the real UI.
- Generated bindings land in `app/src/main/java/uniffi/` at build time (git-ignored).

## Commands (verified)

Rust: test the bridge crate in the container (about 1-2 min on a warm cache):

```
podman run --rm -v noscat-cargo-home:/usr/local/cargo \
  -v "$PWD/rust:/work:Z" -w /work localhost/noscat-dev \
  sh -c "cargo test"
```

Rust: regenerate the Kotlin bindings (run in the same container after `cargo build`):

```
cargo run -q --bin uniffi-bindgen -- generate \
  --library ./target/debug/libnoscat_android.so \
  --language kotlin --out-dir /work/bindings
```

ktlint is not installed in the container, so uniffi-bindgen prints a formatting
warning; the output is still valid Kotlin.

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
- Exported fallible functions must throw a real error enum (`NoscatError`), not bare
  `String`; bindgen panics on `unknown throw type` otherwise.
- `#[uniffi(flat_error)]` goes on the enum, not on the variant.
- Callback interfaces cross the FFI as `Box<dyn ...>`, not `Arc`.
- Exported objects must be `Sync`; `noscat_client::Client` is only `Send`, hence the
  mutex inside `ClientInner`. Its methods are blocking, so holding the lock is fine.
- Private helper methods cannot live inside an `#[uniffi::export] impl` block.
- Kotlin bindings need JNA: `implementation("net.java.dev.jna:jna:5.17.0@aar")` in
  `app/build.gradle.kts` (the `@aar` classifier only; the plain jar duplicates
  classes and fails the build).
- Kotlin package comes from `rust/uniffi.toml` (`[bindings.kotlin] package_name`),
  not a bindgen flag.
- UniFFI 0.29 Kotlin: Rust constructors map to real Kotlin constructors
  (`NoscatClient(...)`, not `NoscatClient.new(...)`), flat errors surface as
  `NoscatException`, `u16` maps to `UShort`, and free functions become top-level
  `generateIdentity()` / `identityFromSecret()`. Check the generated file when the
  Rust surface changes.

## Upgrading noscat

The canonical FFI crate is `crates/noscat-ffi` in the noscat workspace; it is
published to crates.io as `noscat-ffi` and version-locked to the whole workspace
(one version bump per release, all crates together). While this repo still builds
its own `rust/` bridge, any FFI change must land in `crates/noscat-ffi` first and
be mirrored here verbatim. When the next noscat release lands, flip the app to the
published `noscat-ffi` (regenerate the bindings from its `.so`, delete `rust/`,
drop the `rust` steps from `scripts/build-native.sh`). Note the crates.io token in
the noscat repo only allowlists specific crate names; a new crate name would need
an allowlist update.

## Runtime behavior the app must handle (phase 1)

- Adverts expire (TTL); a client that listens must re-advertise. On network change
  (Wi-Fi to cellular), re-publish the advert and expect tunnels to drop
  (`Event.TunnelClosed`) and re-establish.
- Background survival needs a foreground service (`FOREGROUND_SERVICE` permission is
  already in the manifest); without one, Android kills the UDP sockets when the app
  is backgrounded.
- File transfers complete asynchronously: `sendFile` / `receiveFile` return
  immediately, the outcome arrives as `TransferDone` / `TransferFailed`.
- Transfer progress events (`TransferProgress`) do not exist yet in `noscat-client`;
  if a UI needs progress bars, add a progress hook in the noscat repo first.

## Conventions

- Kotlin: package `io.github.letdown2491.noscat`, Compose + Material 3, minSdk 26.
- Rust in `rust/`: keep the FFI surface small; every exported item needs a doc
  comment explaining what it does in user terms, not protocol terms.
- Commit messages: imperative, no em dashes.
- When Gradle work happens, prefer lockfiles and pinned versions so builds are
  reproducible from a clean checkout.
