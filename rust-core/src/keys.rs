use crate::WalletError;
use secp256k1::{PublicKey, SecretKey, Secp256k1};
use sha2::{Sha256, Digest};
use blake2b_ref::Blake2bBuilder;

#[derive(uniffi::Record)]
pub struct KeyPair {
    pub public_key_hex: String,
    pub private_key_hex: String,
}

fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32)
        .personal(b"ckb-default-hash")
        .build();
    hasher.update(data);
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

#[uniffi::export]
pub fn generate_secp256k1_keypair(seed_hex: String, derivation_path: String) -> Result<KeyPair, WalletError> {
    let seed = hex::decode(&seed_hex)?;

    let mut hasher = Sha256::new();
    hasher.update(&seed);
    hasher.update(derivation_path.as_bytes());
    let key_bytes: [u8; 32] = hasher.finalize().into();

    let secp = Secp256k1::new();
    let secret_key = SecretKey::from_slice(&key_bytes)?;
    let public_key = PublicKey::from_secret_key(&secp, &secret_key);

    Ok(KeyPair {
        public_key_hex: hex::encode(public_key.serialize()),
        private_key_hex: hex::encode(secret_key.secret_bytes()),
    })
}

#[uniffi::export]
pub fn public_key_to_ckb_address(
    public_key_hex: String,
    network: String,
) -> Result<String, WalletError> {
    let pk_bytes = hex::decode(&public_key_hex)?;
    let pk = PublicKey::from_slice(&pk_bytes)?;

    let pk_hash = ckb_blake2b(pk.serialize().as_ref());

    let hrp = match network.as_str() {
        "mainnet" => "ckb",
        "testnet" => "ckt",
        _ => "ckb",
    };

    let code_hash = hex::decode("9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8")?;

    let mut data = Vec::new();
    data.push(0x00);
    data.extend_from_slice(&code_hash);
    data.push(0x01);
    data.extend_from_slice(&pk_hash[..20]);

    let hrp_obj = bech32::Hrp::parse(hrp).map_err(|e| WalletError::EncodingError(e.to_string()))?;
    let encoded = bech32::encode_lower::<bech32::Bech32m>(hrp_obj, &data)?;
    Ok(encoded)
}

#[uniffi::export]
pub fn public_key_to_pq_address(
    _public_key_hex: String,
    network: String,
    _algorithm_id: u8,
) -> Result<String, WalletError> {
    let hrp = match network.as_str() {
        "mainnet" => "ckb",
        "testnet" => "ckt",
        _ => "ckb",
    };

    Ok(format!("{}1... (PQ address - implement full bech32m encoding)", hrp))
}
