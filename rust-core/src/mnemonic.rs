use crate::WalletError;

#[derive(uniffi::Record)]
pub struct MnemonicResult {
    pub mnemonic: String,
    pub seed_hex: String,
}

#[uniffi::export]
pub fn generate_mnemonic(word_count: u32) -> Result<MnemonicResult, WalletError> {
    let entropy_len = match word_count {
        12 => 16,
        18 => 24,
        _ => 32,
    };

    let mut entropy = vec![0u8; entropy_len];
    rand::Rng::fill(&mut rand::thread_rng(), &mut entropy[..]);

    let mn = bip39::Mnemonic::from_entropy(&entropy)
        .map_err(|e| WalletError::CryptoError(format!("Mnemonic generation failed: {}", e)))?;

    let phrase: String = mn.words().collect::<Vec<&str>>().join(" ");
    let seed = mn.to_seed("");
    let seed_hex = hex::encode(seed);

    Ok(MnemonicResult {
        mnemonic: phrase,
        seed_hex,
    })
}

#[uniffi::export]
pub fn validate_mnemonic(mnemonic: String) -> bool {
    bip39::Mnemonic::parse_normalized(&mnemonic).is_ok()
}

#[uniffi::export]
pub fn mnemonic_to_seed(mnemonic: String, passphrase: String) -> Result<String, WalletError> {
    let mn = bip39::Mnemonic::parse_normalized(&mnemonic)
        .map_err(|e| WalletError::InvalidInput(e.to_string()))?;
    let seed = mn.to_seed(passphrase);
    Ok(hex::encode(seed))
}

#[uniffi::export]
pub fn get_bip39_wordlist() -> Vec<String> {
    bip39::Language::English
        .word_list()
        .iter()
        .map(|s| s.to_string())
        .collect()
}

#[uniffi::export]
pub fn suggest_bip39_words(prefix: String, limit: u32) -> Vec<String> {
    let prefix_lower = prefix.to_lowercase();
    bip39::Language::English
        .word_list()
        .iter()
        .filter(|w| w.starts_with(&prefix_lower))
        .take(limit as usize)
        .map(|s| s.to_string())
        .collect()
}
