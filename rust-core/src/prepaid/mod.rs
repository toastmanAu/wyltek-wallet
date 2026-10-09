//! Prepaid card protocol — demo tier (terminal-attested, version 0x02).
//! Spec: blackbox-pos docs/superpowers/specs/2026-10-09-blackbox-prepaid-demo-tier-design.md §4.
//! This module is the byte oracle for the POS's prepaid_core; `vectors` emits the golden file
//! both sides must reproduce.
pub mod cmac;
