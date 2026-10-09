//! cargo run --example emit_prepaid_vectors -- <output path>
fn main() {
    let path = std::env::args().nth(1).expect("usage: emit_prepaid_vectors <out.txt>");
    std::fs::write(&path, wyltekwalletcore::prepaid::vectors::emit()).expect("write");
    eprintln!("wrote {path}");
}
