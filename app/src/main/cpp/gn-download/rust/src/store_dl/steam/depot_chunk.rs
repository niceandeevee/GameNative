use crate::store_dl::steam::cdn_client;
use crate::store_dl::steam::crypto::{
    aes256_cbc_decrypt, aes256_ecb_decrypt_block, AesBlock, SessionKey, AES_BLOCK_BYTES,
    SESSION_KEY_LENGTH,
};
use std::io::Read;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotChunkResult {
    pub data: Vec<u8>,
    pub error: String,
}

impl DepotChunkResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty()
    }
}

pub fn process_depot_chunk(
    raw: &[u8],
    depot_key: &[u8],
    expected_crc: u32,
    expected_size: u32,
) -> DepotChunkResult {
    if depot_key.len() != SESSION_KEY_LENGTH {
        return fail("chunk: bad depot key length");
    }
    if raw.is_empty() {
        return fail("chunk: empty");
    }
    let mut key = [0u8; SESSION_KEY_LENGTH];
    key.copy_from_slice(depot_key);

    let Some(dec) = steam_symmetric_decrypt(&key, raw) else {
        return fail("chunk: AES decrypt failed");
    };
    if dec.is_empty() {
        return fail("chunk: AES decrypt failed");
    }

    let result = if dec.starts_with(b"VSZa") {
        decompress_vzstd(&dec, expected_size)
    } else if dec.starts_with(b"VZa") {
        decompress_vzip(&dec, expected_size)
    } else if dec.starts_with(b"PK\x03\x04") {
        match cdn_client::unzip_first_entry(&dec) {
            Some(data) => DepotChunkResult {
                data,
                error: String::new(),
            },
            None => fail("chunk: PKZip decompress failed"),
        }
    } else {
        fail("chunk: unrecognised compression header")
    };
    if !result.ok() {
        return result;
    }
    if result.data.len() != expected_size as usize {
        return fail(format!(
            "chunk: size mismatch ({} != {expected_size})",
            result.data.len()
        ));
    }
    if steam_adler_hash(&result.data) != expected_crc {
        return fail("chunk: Adler32 mismatch");
    }
    result
}

pub fn steam_symmetric_decrypt(key: &SessionKey, enc: &[u8]) -> Option<Vec<u8>> {
    if enc.len() < AES_BLOCK_BYTES * 2 {
        return None;
    }
    let mut wrapped = [0u8; AES_BLOCK_BYTES];
    wrapped.copy_from_slice(&enc[..AES_BLOCK_BYTES]);
    let iv: AesBlock = aes256_ecb_decrypt_block(key, &wrapped)?;
    aes256_cbc_decrypt(key, &iv, &enc[AES_BLOCK_BYTES..])
}

fn decompress_vzstd(dec: &[u8], expected_size: u32) -> DepotChunkResult {
    if dec.len() <= 8 {
        return fail("vzstd: chunk too small");
    }
    let payload = &dec[8..];
    // C libzstd. single_frame(): Steam's footer follows the frame and must not be read as another one.
    let reader = match zstd::stream::read::Decoder::with_buffer(payload) {
        Ok(reader) => reader.single_frame(),
        Err(_) => return fail("vzstd: bad zstd frame"),
    };
    let mut data = Vec::with_capacity(expected_size as usize);
    // Decode at most expected_size + 1 bytes: a hostile/malformed frame must be
    // rejected by size, not allowed to expand unboundedly before the check.
    if reader.take(expected_size as u64 + 1).read_to_end(&mut data).is_err() {
        return fail("vzstd: decode failed");
    }
    if data.len() > expected_size as usize {
        return fail("vzstd: decoded size exceeds expected chunk size");
    }
    DepotChunkResult {
        data,
        error: String::new(),
    }
}

fn decompress_vzip(dec: &[u8], expected_size: u32) -> DepotChunkResult {
    const HEADER: usize = 3 + 4;
    const PROPS: usize = 5;
    const FOOTER: usize = 10;
    if dec.len() < HEADER + PROPS + FOOTER {
        return fail("vzip: chunk too small");
    }
    if dec[dec.len() - 2] != b'z' || dec[dec.len() - 1] != b'v' {
        return fail("vzip: bad footer");
    }
    let props = &dec[HEADER..HEADER + PROPS];
    let comp = &dec[HEADER + PROPS..dec.len() - FOOTER];
    if !valid_lzma_props(props) {
        return fail("vzip: bad LZMA properties");
    }
    // VZip is raw LZMA1 with the properties split off. Put back the .lzma ("alone") header —
    // 5 property bytes + the 64-bit uncompressed size — so C liblzma's alone decoder knows where
    // the stream ends (VZip has no end marker).
    let mut alone = Vec::with_capacity(13 + comp.len());
    alone.extend_from_slice(props);
    alone.extend_from_slice(&(expected_size as u64).to_le_bytes());
    alone.extend_from_slice(comp);
    let stream = match liblzma::stream::Stream::new_lzma_decoder(u64::MAX) {
        Ok(stream) => stream,
        Err(_) => return fail("vzip: decoder init failed"),
    };
    let reader = liblzma::read::XzDecoder::new_stream(&alone[..], stream);
    let mut output = Vec::with_capacity(expected_size as usize);
    match reader.take(expected_size as u64 + 1).read_to_end(&mut output) {
        Ok(_) => DepotChunkResult {
            data: output,
            error: String::new(),
        },
        Err(_) => fail("vzip: LZMA decode failed"),
    }
}

/// lc/lp/pb packed as (pb * 5 + lp) * 9 + lc; anything at or above 225 is invalid.
fn valid_lzma_props(props: &[u8]) -> bool {
    props.len() == 5 && props[0] < 225
}

/// Adler-32 with a zero seed (Steam's chunk checksum). The modulo is taken once per 5552 bytes,
/// the most that can be summed without overflowing u32, instead of twice per byte.
pub fn steam_adler_hash(data: &[u8]) -> u32 {
    const MOD: u32 = 65_521;
    const NMAX: usize = 5552;
    let mut a = 0u32;
    let mut b = 0u32;
    for block in data.chunks(NMAX) {
        for byte in block {
            a += *byte as u32;
            b += a;
        }
        a %= MOD;
        b %= MOD;
    }
    a | (b << 16)
}

fn fail(msg: impl Into<String>) -> DepotChunkResult {
    DepotChunkResult {
        data: Vec::new(),
        error: msg.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store_dl::steam::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block};

    #[test]
    fn steam_adler_hash_uses_zero_seed() {
        assert_eq!(steam_adler_hash(b""), 0);
        assert_eq!(steam_adler_hash(b"abc"), 0x024a_0126);
    }

    #[test]
    fn decrypts_and_processes_pkzip_chunk() {
        let key = [4u8; SESSION_KEY_LENGTH];
        let payload = b"chunk bytes";
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"x");
        zip.extend_from_slice(payload);

        let raw = encrypt_steam_symmetric(&key, &zip);
        let result =
            process_depot_chunk(&raw, &key, steam_adler_hash(payload), payload.len() as u32);
        assert_eq!(result.data, payload);
        assert!(result.ok());
    }

    #[test]
    fn decrypts_and_processes_vzip_raw_lzma_chunk() {
        let key = [8u8; SESSION_KEY_LENGTH];
        let payload: Vec<u8> = (0..300_000u32).map(|i| (i * 7 % 251) as u8 ^ (i >> 9) as u8).collect();
        let mut lzma_alone = Vec::new();
        lzma_rs::lzma_compress(&mut std::io::Cursor::new(&payload[..]), &mut lzma_alone).unwrap();
        assert!(lzma_alone.len() > 13);

        let mut vzip = Vec::new();
        vzip.extend_from_slice(b"VZa");
        vzip.extend_from_slice(&0u32.to_le_bytes());
        vzip.extend_from_slice(&lzma_alone[..5]);
        vzip.extend_from_slice(&lzma_alone[13..]);
        vzip.extend_from_slice(&0u32.to_le_bytes());
        vzip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        vzip.extend_from_slice(b"zv");

        let raw = encrypt_steam_symmetric(&key, &vzip);
        let result =
            process_depot_chunk(&raw, &key, steam_adler_hash(&payload), payload.len() as u32);
        assert_eq!(result.data, payload);
        assert!(result.ok());
    }

    #[test]
    fn decrypts_and_processes_vzstd_chunk() {
        let key = [9u8; SESSION_KEY_LENGTH];
        let payload: Vec<u8> = (0..300_000u32).map(|i| (i % 97) as u8).collect();
        let frame = zstd::bulk::compress(&payload, 3).unwrap();
        let mut vz = Vec::new();
        vz.extend_from_slice(b"VSZa");
        vz.extend_from_slice(&0u32.to_le_bytes());
        vz.extend_from_slice(&frame);
        // Footer: crc, size, "zsv" — must be ignored by the decoder.
        vz.extend_from_slice(&0u32.to_le_bytes());
        vz.extend_from_slice(&(payload.len() as u64).to_le_bytes());
        vz.extend_from_slice(b"zsv");
        let raw = encrypt_steam_symmetric(&key, &vz);
        let result =
            process_depot_chunk(&raw, &key, steam_adler_hash(&payload), payload.len() as u32);
        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.data, payload);
        // A frame that expands past the expected size is rejected.
        assert!(!decompress_vzstd(&vz, payload.len() as u32 - 1).ok());
    }

    #[test]
    fn adler_matches_reference_on_long_input() {
        fn reference(data: &[u8]) -> u32 {
            let (mut a, mut b) = (0u32, 0u32);
            for byte in data {
                a = (a + *byte as u32) % 65_521;
                b = (b + a) % 65_521;
            }
            a | (b << 16)
        }
        let all_ff = vec![0xffu8; 1 << 20];
        assert_eq!(steam_adler_hash(&all_ff), reference(&all_ff));
        let mixed: Vec<u8> = (0..1_000_003u32).map(|i| (i.wrapping_mul(2654435761) >> 24) as u8).collect();
        assert_eq!(steam_adler_hash(&mixed), reference(&mixed));
    }

    #[test]
    fn vzip_rejects_bad_properties_before_raw_decode() {
        let mut dec = Vec::new();
        dec.extend_from_slice(b"VZa");
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(&[225, 0, 0, 0, 0]);
        dec.extend_from_slice(b"payload");
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(&0u32.to_le_bytes());
        dec.extend_from_slice(b"zv");
        assert_eq!(decompress_vzip(&dec, 1).error, "vzip: bad LZMA properties");
    }

    fn encrypt_steam_symmetric(key: &SessionKey, plaintext: &[u8]) -> Vec<u8> {
        let iv = [6u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, plaintext).unwrap();
        let mut out = wrapped.to_vec();
        out.extend_from_slice(&body);
        out
    }
}
