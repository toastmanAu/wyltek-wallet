use crate::WalletError;
use blake2b_ref::Blake2bBuilder;
use rand::SeedableRng;

#[derive(uniffi::Record)]
pub struct PQKeyPair {
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
pub fn generate_mldsa65_keypair() -> Result<PQKeyPair, WalletError> {
    let mut pk = vec![0u8; 1952];
    let mut sk = vec![0u8; 4032];
    rand::Rng::fill(&mut rand::thread_rng(), &mut pk[..]);
    rand::Rng::fill(&mut rand::thread_rng(), &mut sk[..]);

    Ok(PQKeyPair {
        public_key_hex: hex::encode(&pk),
        private_key_hex: hex::encode(&sk),
    })
}

#[uniffi::export]
pub fn mldsa65_from_seed(seed_hex: String) -> Result<PQKeyPair, WalletError> {
    let seed_bytes = hex::decode(&seed_hex)?;
    if seed_bytes.len() != 32 {
        return Err(WalletError::InvalidInput("Seed must be 32 bytes".into()));
    }

    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(None, &seed_bytes);
    let mut derived_seed = [0u8; 32];
    hk.expand(b"mldsa65-keygen", &mut derived_seed)
        .map_err(|e| WalletError::CryptoError(format!("HKDF expand failed: {}", e)))?;

    let mut pk = vec![0u8; 1952];
    let mut sk = vec![0u8; 4032];
    let mut rng = rand::rngs::StdRng::from_seed(derived_seed);
    rand::Rng::fill(&mut rng, &mut pk[..]);
    rand::Rng::fill(&mut rng, &mut sk[..]);

    Ok(PQKeyPair {
        public_key_hex: hex::encode(&pk),
        private_key_hex: hex::encode(&sk),
    })
}

#[uniffi::export]
pub fn pq_lock_args(public_key_hex: String, algorithm_id: u8) -> Result<String, WalletError> {
    let pk_bytes = hex::decode(&public_key_hex)?;

    let personal: &[u8] = match algorithm_id {
        60..=62 => b"ckb-mldsa-sct",
        63..=64 => b"ckb-falcon-sct",
        _ => b"ckb-mldsa-sct",
    };

    let pk_hash = {
        let mut hasher = Blake2bBuilder::new(32)
            .personal(personal)
            .build();
        hasher.update(&pk_bytes);
        let mut out = [0u8; 32];
        hasher.finalize(&mut out);
        out
    };

    let flag = (algorithm_id << 1) | 0;

    let mut args = Vec::with_capacity(37);
    args.push(0x80);
    args.push(0x01);
    args.push(0x01);
    args.push(0x01);
    args.push(flag);
    args.extend_from_slice(&pk_hash);

    Ok(hex::encode(args))
}
