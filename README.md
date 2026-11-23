# noscat android

Android client for [noscat](https://github.com/Letdown2491/noscat), the Nostr-native
mesh VPN. Native Kotlin (Compose) over a Rust UniFFI bridge; the protocol lives in the
published `noscat-client` crates, so this repo contains no protocol code.

Status: phase 1 in progress - a remote-reach client (dial `forward` tunnels to peers,
send and receive files). Roadmap and agent guidance in [AGENTS.md](AGENTS.md).

## Building

Two steps: the native bridge, then the app.

1. Native bridge (Rust builds in the podman container; the script mounts the
   host Android NDK and cross-compiles for arm64, then strips the .so into
   `app/src/main/jniLibs/`):

   ```
   ./scripts/build-native.sh
   ```

2. The app itself (needs the JDK 21 tarball in `~/opt`):

   ```
   JAVA_HOME=$(echo ~/opt/jdk-21*) ./gradlew :app:assembleDebug --no-daemon
   ```

The APK lands in `app/build/outputs/apk/debug/`.
