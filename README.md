# noscat android

Android client for [noscat](https://github.com/Letdown2491/noscat), the Nostr-native
mesh VPN. Native Kotlin (Compose) over a Rust UniFFI bridge; the protocol lives in
the noscat workspace (`noscat-core`/`noscat-client`/`noscat-ffi`), so this repo
contains no protocol code.

Status: phase 1 in progress - a remote-reach client: chat, file send/receive,
port forwards to a peer's services, and joining the owner's mesh by invite.
Roadmap and agent guidance in [AGENTS.md](AGENTS.md).

## Building

Two steps: the native bridge, then the app.

1. Native bridge (cross-compiles the `noscat-ffi` crate in the podman
   container, mounts the host Android NDK, strips the .so into
   `app/src/main/jniLibs/`, and regenerates the Kotlin bindings):

   ```
   ./scripts/build-native.sh local    # build from the ~/Documents/noscat checkout
   ./scripts/build-native.sh 0.3.0    # or cross-compile a published version
   ```

2. The app itself (needs the JDK 21 tarball in `~/opt`):

   ```
   JAVA_HOME=$(echo ~/opt/jdk-21*) ./gradlew :app:assembleDebug --no-daemon
   ```

The APK lands in `app/build/outputs/apk/debug/`.
