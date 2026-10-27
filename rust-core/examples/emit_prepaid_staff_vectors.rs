//! cargo run --example emit_prepaid_staff_vectors -- <output path>
fn main() {
    let path = std::env::args().nth(1).expect("usage: emit_prepaid_staff_vectors <out.txt>");
    std::fs::write(&path, wyltekwalletcore::prepaid::vectors::emit_staff()).expect("write");
    eprintln!("wrote {path}");
}
