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
   The bridge crate `rust/` only adapts types across the FFI.
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

- `rust/` - the UniFFI bridge crate (`noscat-android`). The FFI surface is
  `rust/src/lib.rs`: records (`Identity`, `ClientConfig`), the `Event` enum, the
  `EventListener` callback interface, `NoscatClient` with blocking methods that
  dispatch results as events.
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

Kotlin: building the app needs an Android SDK (compileSdk 35) and a Gradle wrapper;
no wrapper is committed yet. When one is added, document the exact command here and
keep it runnable headless (`./gradlew :app:assembleDebug`).

UniFFI gotchas already paid for; do not regress:
- Exported fallible functions must throw a real error enum (`NoscatError`), not bare
  `String`; bindgen panics on `unknown throw type` otherwise.
- `#[uniffi(flat_error)]` goes on the enum, not on the variant.
- Callback interfaces cross the FFI as `Box<dyn ...>`, not `Arc`.
- Exported objects must be `Sync`; `noscat_client::Client` is only `Send`, hence the
  mutex inside `ClientInner`. Its methods are blocking, so holding the lock is fine.
- Private helper methods cannot live inside an `#[uniffi::export] impl` block.

## Upgrading noscat

`rust/Cargo.toml` pins `noscat-core` and `noscat-client` by version. To pick up a new
release, bump both versions there, run the container test, and check whether the
`Event` enum in `noscat-client` grew variants; the pump thread in `lib.rs` matches
exhaustively and will fail to compile, which is the intended forcing function. Note
the crates.io token in the noscat repo only allowlists specific crate names; a new
crate name (not planned) would need an allowlist update.

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
