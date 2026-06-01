use crate::WalletError;

#[derive(uniffi::Record)]
pub struct AddressInfo {
    pub bech32m: String,
    pub network: String,
    pub format_version: String,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
}

#[uniffi::export]
pub fn decode_address(address: String) -> Result<AddressInfo, WalletError> {
    let (_hrp, data) = bech32::decode(&address)?;

    let network = if address.starts_with("ckb1") { "mainnet" } else { "testnet" };

    if data.is_empty() {
        return Err(WalletError::InvalidInput("Empty address payload".into()));
    }

    let format_byte = data[0];
    let (format_version, payload) = match format_byte {
        0x00 => ("ckb2021", &data[1..]),
        0x01 => ("deprecated-short", &data[1..]),
        0x02 => ("deprecated-fulldata", &data[1..]),
        0x04 => ("deprecated-fulltype", &data[1..]),
        _ => ("unknown", &data[1..]),
    };

    if payload.len() < 33 {
        return Err(WalletError::InvalidInput("Payload too short for lock script".into()));
    }

    let code_hash = hex::encode(&payload[..32]);
    let hash_type = match payload[32] {
        0x00 => "data",
        0x01 => "type",
        0x02 => "data1",
        0x04 => "data2",
        _ => "unknown",
    };
    let lock_args = hex::encode(&payload[33..]);

    Ok(AddressInfo {
        bech32m: address,
        network: network.to_string(),
        format_version: format_version.to_string(),
        lock_code_hash: code_hash,
        lock_hash_type: hash_type.to_string(),
        lock_args,
    })
}

#[uniffi::export]
pub fn encode_address(
    code_hash: String,
    hash_type: String,
    args: String,
    network: String,
) -> Result<String, WalletError> {
    let hrp = match network.as_str() {
        "mainnet" => "ckb",
        "testnet" => "ckt",
        "devnet" => "ckd",
        _ => "ckb",
    };

    let code_hash_bytes = hex::decode(&code_hash)?;
    if code_hash_bytes.len() != 32 {
        return Err(WalletError::InvalidInput("Code hash must be 32 bytes".into()));
    }

    let hash_type_byte = match hash_type.as_str() {
        "data" => 0x00u8,
        "type" => 0x01u8,
        "data1" => 0x02u8,
        "data2" => 0x04u8,
        _ => return Err(WalletError::InvalidInput(format!("Invalid hash type: {}", hash_type))),
    };

    let args_bytes = hex::decode(&args)?;

    let mut payload = Vec::with_capacity(1 + 32 + 1 + args.len());
    payload.push(0x00);
    payload.extend_from_slice(&code_hash_bytes);
    payload.push(hash_type_byte);
    payload.extend_from_slice(&args_bytes);

    let hrp_obj = bech32::Hrp::parse(hrp).map_err(|e| WalletError::EncodingError(e.to_string()))?;
    let encoded = bech32::encode_lower::<bech32::Bech32m>(hrp_obj, &payload)?;
    Ok(encoded)
}

#[uniffi::export]
pub fn validate_address(address: String) -> bool {
    decode_address(address).is_ok()
}
