//! UniFFI bindings over the noscat client stack for the Android app.
//!
//! The rule this crate enforces: the protocol lives in Rust. Kotlin sees
//! identities, plain commands (dial, forward, expose, send, receive), and an
//! event callback - never Noise, UDP, or Nostr details. The Rust side is the
//! published `noscat-core` / `noscat-client` crates, wrapped here with
//! FFI-safe types only (records, enums, callback interfaces).

use std::sync::Arc;

uniffi::setup_scaffolding!();

/// The one FFI error: every exported fallible call surfaces its message
/// through `Failed` (uniFFI needs a real error enum, not bare strings).
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum NoscatError {
    #[error("{0}")]
    Failed(String),
}

impl NoscatError {
    fn fail<E: ToString>(e: E) -> Self {
        NoscatError::Failed(e.to_string())
    }
}

/// A noscat identity: the npub is the public mesh address, the secret hex is
/// the credential (store it in the Android keystore, never in plain files if
/// avoidable).
#[derive(uniffi::Record)]
pub struct Identity {
    pub npub: String,
    pub secret_hex: String,
}

/// Client configuration. `relays` defaults to the core's public relay set.
#[derive(uniffi::Record)]
pub struct ClientConfig {
    /// The identity secret (hex or nsec). None = generate an ephemeral one.
    pub identity_secret: Option<String>,
    /// Relay URLs; None uses the defaults.
    pub relays: Option<Vec<String>>,
    /// The tailnet device directory to scope tunnels to, if any.
    pub tailnet_dir: Option<String>,
    /// The UDP port to listen on for inbound tunnels. None = 41641.
    pub listen_port: Option<u16>,
}

/// What the client does on its own, delivered to the registered listener.
#[derive(uniffi::Enum)]
pub enum Event {
    /// The serve side is up (advert published): npub + local UDP port.
    Listening { npub: String, port: u16 },
    /// A tunnel is ready for traffic: peer npub (or noise key fallback).
    TunnelUp { peer: String },
    /// A tunnel ended; error is set when it was not a clean close.
    TunnelClosed { peer: String, error: Option<String> },
    /// A file transfer finished: the final verified path.
    TransferDone { peer: String, path: String },
    /// A file transfer failed.
    TransferFailed { peer: String, error: String },
    /// A background operation stopped (the listener, a forward, ...).
    Stopped { what: String, error: Option<String> },
}

/// The event sink the app registers; called from a background thread.
#[uniffi::export(callback_interface)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, event: Event);
}

/// Generate a fresh identity.
#[uniffi::export]
pub fn generate_identity() -> Identity {
    let id = noscat_core::Identity::generate();
    Identity {
        npub: id.npub(),
        secret_hex: id.secret_hex(),
    }
}

/// Restore an identity from its secret (hex or nsec).
#[uniffi::export]
pub fn identity_from_secret(secret: String) -> Result<Identity, NoscatError> {
    let id = noscat_core::Identity::from_secret(&secret).map_err(NoscatError::fail)?;
    Ok(Identity {
        npub: id.npub(),
        secret_hex: id.secret_hex(),
    })
}

/// A running noscat client. Dropping the last reference stops the runtime
/// and every background operation it owns.
#[derive(uniffi::Object)]
pub struct NoscatClient {
    inner: Arc<ClientInner>,
}

struct ClientInner {
    // noscat_client::Client is Send but not Sync (it carries a std mpsc
    // receiver), and uniffi objects must be Sync: pin it behind a mutex.
    // Every method on it is blocking anyway, so the lock never awaits.
    client: std::sync::Mutex<noscat_client::Client>,
}

#[uniffi::export]
impl NoscatClient {
    /// Start the client: resolve the identity and config, spin the runtime.
    /// `listener` receives every subsequent event from a background thread.
    #[uniffi::constructor]
    pub fn new(config: ClientConfig, listener: Box<dyn EventListener>) -> Result<Self, NoscatError> {
        let mut client = noscat_client::Client::start(noscat_client::ClientConfig {
            identity: match &config.identity_secret {
                Some(s) => noscat_client::IdentitySource::Secret(s.trim().to_string()),
                None => noscat_client::IdentitySource::Ephemeral,
            },
            relays: config.relays.clone(),
            listen: Some(
                std::net::SocketAddr::from(([0, 0, 0, 0], config.listen_port.unwrap_or(41641))),
            ),
            tailnet_dir: config.tailnet_dir.as_ref().map(std::path::PathBuf::from),
            advert_ttl_secs: 3600,
        })
        .map_err(NoscatError::fail)?;

        // Pump client events into the app's callback. The channel dies with
        // the client; the thread ends with it.
        let rx = client
            .events()
            .ok_or_else(|| NoscatError::Failed("event receiver already taken".into()))?;
        let pump = std::thread::spawn(move || {
            for ev in rx {
                let event = match ev {
                    noscat_client::Event::Listening { npub, addr } => Event::Listening {
                        npub,
                        port: addr.port(),
                    },
                    noscat_client::Event::TunnelUp { peer, .. } => Event::TunnelUp { peer },
                    noscat_client::Event::TunnelClosed { peer, error } => {
                        Event::TunnelClosed { peer, error }
                    }
                    noscat_client::Event::TransferDone { peer, path } => {
                        Event::TransferDone { peer, path: path.display().to_string() }
                    }
                    noscat_client::Event::TransferFailed { peer, error, .. } => {
                        Event::TransferFailed { peer, error }
                    }
                    noscat_client::Event::Stopped { what, error } => {
                        Event::Stopped { what: what.to_string(), error }
                    }
                    // Phase 1 does not surface raw streams: listeners are
                    // consumed internally by expose/listen operations.
                    noscat_client::Event::Incoming { .. } => continue,
                };
                listener.on_event(event);
            }
        });
        std::mem::forget(pump); // detach: runs until the channel closes

        Ok(Self {
            inner: Arc::new(ClientInner {
                client: std::sync::Mutex::new(client),
            }),
        })
    }

    /// This device's npub (the mesh address to share with peers).
    pub fn npub(&self) -> String {
        self.lock().npub().to_string()
    }

    /// Forward a local port over a tunnel to a peer running `expose`:
    /// localhost:port on the phone becomes the peer's service. Returns
    /// immediately; progress arrives as events.
    pub fn forward(&self, target_npub: String, local_port: u16) -> Result<(), NoscatError> {
        self.lock()
            .forward(
                &target_npub,
                std::net::SocketAddr::from(([127, 0, 0, 1], local_port)),
            )
            .map_err(NoscatError::fail)
    }

    /// Expose a peer-reachable TCP service of this device (host:port) over
    /// tunnels. Returns immediately; progress arrives as events.
    pub fn expose(&self, to_host: String, to_port: u16) -> Result<u16, NoscatError> {
        let to: std::net::SocketAddr = format!("{to_host}:{to_port}")
            .parse()
            .map_err(NoscatError::fail)?;
        self.lock()
            .expose(to)
            .map(|addr| addr.port())
            .map_err(NoscatError::fail)
    }

    /// Send a file to a peer. Returns immediately; the outcome arrives as
    /// TransferDone / TransferFailed. Resume-aware, sha256-verified at the peer.
    pub fn send_file(&self, path: String, target_npub: String) {
        self.lock().send_file(std::path::PathBuf::from(path), &target_npub);
    }

    /// Receive one file into out_dir. Returns immediately; the outcome
    /// arrives as TransferDone (with the verified final path) / TransferFailed.
    pub fn receive_file(&self, out_dir: String) {
        self.lock().receive_file(std::path::PathBuf::from(out_dir));
    }
}

// Not exported: a plain private helper (MutexGuard cannot cross the FFI).
impl NoscatClient {
    fn lock(&self) -> std::sync::MutexGuard<'_, noscat_client::Client> {
        self.inner.client.lock().expect("client lock")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_round_trip() {
        let fresh = generate_identity();
        let restored = identity_from_secret(fresh.secret_hex.clone()).unwrap();
        assert_eq!(fresh.npub, restored.npub);
        assert!(restored.npub.starts_with("npub1"));
    }

    #[test]
    fn bad_secret_is_an_error() {
        assert!(identity_from_secret("not-a-key".into()).is_err());
    }
}
