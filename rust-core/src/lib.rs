uniffi::setup_scaffolding!();

pub mod mnemonic;
pub mod keys;
pub mod pq_keys;
pub mod address;
pub mod transaction;
pub mod signing;
pub mod hashing;

pub use mnemonic::{MnemonicResult, generate_mnemonic, validate_mnemonic, mnemonic_to_seed};
pub use keys::{KeyPair, generate_secp256k1_keypair, public_key_to_ckb_address, public_key_to_pq_address};
pub use pq_keys::{PQKeyPair, generate_mldsa65_keypair, mldsa65_from_seed, pq_lock_args};
pub use address::{AddressInfo, decode_address, encode_address, validate_address};
pub use transaction::{TransactionRequest, TxInput, TxOutput, BuiltTransaction, build_transaction};
pub use signing::{sign_transaction, sign_message};
pub use hashing::{blake2b_256, ckb_hash};

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum WalletError {
    #[error("Invalid input: {0}")]
    InvalidInput(String),
    #[error("Crypto error: {0}")]
    CryptoError(String),
    #[error("Encoding error: {0}")]
    EncodingError(String),
}

impl From<anyhow::Error> for WalletError {
    fn from(e: anyhow::Error) -> Self {
        WalletError::CryptoError(e.to_string())
    }
}

impl From<hex::FromHexError> for WalletError {
    fn from(e: hex::FromHexError) -> Self {
        WalletError::EncodingError(e.to_string())
    }
}

impl From<secp256k1::Error> for WalletError {
    fn from(e: secp256k1::Error) -> Self {
        WalletError::CryptoError(e.to_string())
    }
}

impl From<bech32::EncodeError> for WalletError {
    fn from(e: bech32::EncodeError) -> Self {
        WalletError::EncodingError(e.to_string())
    }
}

impl From<bech32::DecodeError> for WalletError {
    fn from(e: bech32::DecodeError) -> Self {
        WalletError::EncodingError(e.to_string())
    }
}

#[uniffi::export]
pub fn wallet_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
