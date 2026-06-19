uniffi::setup_scaffolding!();

pub mod mnemonic;
pub mod keys;
pub mod pq_keys;
pub mod address;
pub mod transaction;
pub mod signing;
pub mod hashing;
pub mod molecule;
pub mod agent;

pub use mnemonic::{MnemonicResult, generate_mnemonic, validate_mnemonic, mnemonic_to_seed};
pub use keys::{KeyPair, generate_secp256k1_keypair, public_key_to_ckb_address, public_key_to_pq_address};
pub use pq_keys::{PQKeyPair, generate_mldsa65_keypair, mldsa65_from_seed, mldsa65_sign, mldsa65_verify, pq_lock_args};
pub use address::{AddressInfo, decode_address, encode_address, validate_address};
pub use transaction::{TransactionRequest, TxInput, TxOutput, BuiltTransaction, build_transaction};
pub use signing::{sign_transaction, sign_ckb_secp256k1, sign_message, verify_signature};
pub use hashing::{blake2b_256, ckb_hash};
pub use agent::{AgentError, CapInfo, Decision, Intent, LedgerView, RequestCtx, Scope, TokenSpec};

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

impl From<String> for WalletError {
    fn from(e: String) -> Self {
        WalletError::InvalidInput(e)
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ckb_address_generation() {
        let seed_hex = "abcd1234".repeat(8);
        let kp = keys::generate_secp256k1_keypair(seed_hex, "m/44'/302'/0'/0/0".to_string()).unwrap();
        let addr = public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string()).unwrap();
        let info = address::decode_address(addr.clone()).unwrap();
        
        let expected_code_hash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8";
        assert_eq!(info.lock_code_hash, expected_code_hash, "code_hash mismatch!");
        assert!(info.lock_args.starts_with("0x"), "args must have 0x prefix");
        assert_eq!(info.lock_hash_type, "type", "hash_type mismatch!");
        assert!(!info.lock_args.is_empty(), "args should not be empty");
        println!("address: {}", addr);
        println!("OK: address decodes correctly with standard secp256k1 code hash");
    }
}
