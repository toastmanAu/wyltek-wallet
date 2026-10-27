//! Staff-key PIN derivation (prepaid spec §5.4 / §5.5).
//!
//! ```text
//! K_staff   = AES-CMAC(K_debit, "BBSTAFF1" || 8 x 0x00)          (full 16 bytes)
//! pin_check = AES-CMAC(K_staff, key_id || pin_ver.to_le_bytes() || pin)   (full 16 bytes)
//! ```
//!
//! The first 8 bytes of `pin_check` are the MACt the card stores for PIN verification; the
//! full 16 bytes are kept as the staff-side verification value. The Vault and the POS derive
//! `K_staff` from `K_debit` and never store it.
use super::cmac::aes_cmac;

pub const PIN_MIN: usize = 4;
pub const PIN_MAX: usize = 8;
pub const K_STAFF_MSG: [u8; 16] = *b"BBSTAFF1\0\0\0\0\0\0\0\0";

/// A PIN is 4 to 8 ASCII decimal digits. Leading zeros are significant.
pub fn pin_ok(pin: &[u8]) -> bool {
    (PIN_MIN..=PIN_MAX).contains(&pin.len()) && pin.iter().all(|b| b.is_ascii_digit())
}

pub fn k_staff(k_debit: &[u8; 16]) -> [u8; 16] {
    aes_cmac(k_debit, &K_STAFF_MSG)
}

/// Full 16-byte PIN check value, or `None` if the PIN is malformed.
pub fn pin_check(k_staff: &[u8; 16], key_id: &[u8; 8], pin_ver: u16, pin: &[u8]) -> Option<[u8; 16]> {
    if !pin_ok(pin) {
        return None;
    }
    let mut msg = Vec::with_capacity(8 + 2 + pin.len());
    msg.extend_from_slice(key_id);
    msg.extend_from_slice(&pin_ver.to_le_bytes());
    msg.extend_from_slice(pin);
    Some(aes_cmac(k_staff, &msg))
}

#[cfg(test)]
mod tests {
    use super::super::cmac::{aes_cmac, cmac8, mac_truncate};
    use super::*;

    fn kd() -> [u8; 16] { core::array::from_fn(|i| 0x10 + i as u8) }
    fn kid() -> [u8; 8] { [0xC0, 0xFF, 0xEE, 0x01, 0x02, 0x03, 0x04, 0x05] }

    #[test] fn k_staff_is_the_full_cmac_of_the_label_under_k_debit() {
        assert_eq!(k_staff(&kd()), aes_cmac(&kd(), &K_STAFF_MSG));
        assert_eq!(&K_STAFF_MSG[..8], b"BBSTAFF1"); assert_eq!(K_STAFF_MSG[8..], [0u8; 8]);
        let mut other = kd(); other[15] ^= 1; assert_ne!(k_staff(&other), k_staff(&kd()));
    }
    #[test] fn pin_check_message_is_key_id_then_pin_ver_le_then_the_digits() {
        let ks = k_staff(&kd());
        let mut msg = Vec::new(); msg.extend_from_slice(&kid()); msg.extend_from_slice(&0x0102u16.to_le_bytes()); msg.extend_from_slice(b"1234");
        assert_eq!(pin_check(&ks, &kid(), 0x0102, b"1234"), Some(aes_cmac(&ks, &msg)));
        assert_eq!(msg.len(), 14);
    }
    #[test] fn pin_check_is_the_full_16_bytes_whose_truncation_is_cmac8() {
        let ks = k_staff(&kd()); let pc = pin_check(&ks, &kid(), 1, b"1234").unwrap();
        let mut msg = Vec::new(); msg.extend_from_slice(&kid()); msg.extend_from_slice(&1u16.to_le_bytes()); msg.extend_from_slice(b"1234");
        assert_eq!(cmac8(&ks, &msg), mac_truncate(&pc)); assert_ne!(&pc[8..], &[0u8; 8][..]);
    }
    #[test] fn the_same_pin_at_pin_ver_1_and_2_differs() {
        let ks = k_staff(&kd());
        assert_ne!(pin_check(&ks, &kid(), 1, b"1234"), pin_check(&ks, &kid(), 2, b"1234"));
    }
    #[test] fn leading_zeros_are_part_of_the_pin() {
        let ks = k_staff(&kd());
        assert_ne!(pin_check(&ks, &kid(), 1, b"0042"), pin_check(&ks, &kid(), 1, b"1042"));
        assert!(pin_check(&ks, &kid(), 1, b"00000000").is_some());
    }
    #[test] fn pin_length_and_charset_are_enforced() {
        let ks = k_staff(&kd());
        for bad in [&b"123"[..], b"123456789", b"12a4", b"12 4", "１２３４".as_bytes(), b""] { assert!(pin_check(&ks, &kid(), 1, bad).is_none(), "{bad:?}"); assert!(!pin_ok(bad)); }
        for good in [&b"1234"[..], b"12345678", b"0000"] { assert!(pin_ok(good)); assert!(pin_check(&ks, &kid(), 1, good).is_some()); }
    }
    #[test] fn k_staff_is_not_a_record_or_journal_mac_key() {
        // domain separation: a pin_check never equals a CMAC under K_debit over the same message
        let ks = k_staff(&kd()); let mut msg = Vec::new(); msg.extend_from_slice(&kid()); msg.extend_from_slice(&1u16.to_le_bytes()); msg.extend_from_slice(b"1234");
        assert_ne!(pin_check(&ks, &kid(), 1, b"1234").unwrap(), aes_cmac(&kd(), &msg));
    }
}
