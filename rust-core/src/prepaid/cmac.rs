//! AES-128 CMAC (RFC 4493) with the DESFire 8-byte truncation (odd-index bytes). Must match
//! blackbox-pos ckb_pos_v1.0.0/ev2_core.cpp byte for byte; the RFC 4493 and AN12196 Table 7
//! vectors below are the same ones that suite pins.
use aes::Aes128;
use cmac::{Cmac, Mac};

pub fn aes_cmac(key: &[u8; 16], msg: &[u8]) -> [u8; 16] {
    let mut mac = <Cmac<Aes128> as Mac>::new_from_slice(key).expect("16-byte key");
    mac.update(msg);
    mac.finalize().into_bytes().into()
}

/// DESFire MACt: bytes 1,3,5,...,15 of the full CMAC.
pub fn mac_truncate(full: &[u8; 16]) -> [u8; 8] {
    let mut out = [0u8; 8];
    for i in 0..8 { out[i] = full[2 * i + 1]; }
    out
}

pub fn cmac8(key: &[u8; 16], msg: &[u8]) -> [u8; 8] { mac_truncate(&aes_cmac(key, msg)) }

#[cfg(test)]
mod tests {
    use super::*;
    fn hx(s: &str) -> Vec<u8> { hex::decode(s).unwrap() }
    fn key() -> [u8; 16] { hx("2b7e151628aed2a6abf7158809cf4f3c").try_into().unwrap() }

    #[test]
    fn rfc4493_vectors() {
        assert_eq!(aes_cmac(&key(), &[]).to_vec(), hx("bb1d6929e95937287fa37d129b756746"));
        assert_eq!(aes_cmac(&key(), &hx("6bc1bee22e409f96e93d7e117393172a")).to_vec(), hx("070a16b46b4d4144f79bdd9dd04a287c"));
        assert_eq!(aes_cmac(&key(), &hx("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411")).to_vec(), hx("dfa66747de9ae63030ca32611497c827"));
        assert_eq!(aes_cmac(&key(), &hx("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")).to_vec(), hx("51f0bebf7e3b9d92fc49741779363cfe"));
    }

    #[test]
    fn desfire_truncation_an12196_table7() {
        let full: [u8; 16] = hx("B565AC978FA46D5784C845CD1444102C").try_into().unwrap();
        assert_eq!(mac_truncate(&full).to_vec(), hx("6597A457C8CD442C"));
    }

    #[test]
    fn cmac8_is_truncated_cmac() {
        let m = hx("6bc1bee22e409f96e93d7e117393172a");
        assert_eq!(cmac8(&key(), &m), mac_truncate(&aes_cmac(&key(), &m)));
    }
}
