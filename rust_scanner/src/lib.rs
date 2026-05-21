use std::ffi::{CStr, CString, c_char};
use std::path::Path;
use walkdir::WalkDir;
use lofty::read_from_path;
use lofty::file::{TaggedFileExt, AudioFile};
use lofty::tag::Accessor;
use serde::Serialize;
use rayon::prelude::*;

#[derive(Serialize)]
pub struct Track {
    pub path: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u32,
    pub format: String,
}

#[no_mangle]
pub extern "C" fn Java_android_kimyona_jammer_core_media_RustBridge_nativeScanDirectory(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    dir_ptr: *const c_char,
) -> *mut c_char {
    let dir = unsafe { CStr::from_ptr(dir_ptr).to_string_lossy() };
    let tracks = scan_directory(&dir);

    let json = match serde_json::to_string(&tracks) {
        Ok(s) => s,
        Err(_) => return CString::new("[]").unwrap().into_raw(),
    };

    CString::new(json).unwrap().into_raw()
}

fn scan_directory(dir: &str) -> Vec<Track> {
    // Coleta todos os caminhos válidos primeiro (sequencial, pois WalkDir não é Send)
    let paths: Vec<_> = WalkDir::new(dir)
        .follow_links(false)        // NÃO segue symlinks → evita loops e pastas estranhas
        .max_depth(3)              // Reduz profundidade → não entra em subpastas infinitas
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| {
            let p = e.path();
            is_audio_file(p) && is_reasonable_size(p)
        })
        .map(|e| e.path().to_path_buf())
        .collect();

    // Processa em paralelo usando todos os núcleos do processador
    let tracks: Vec<Track> = paths
        .par_iter()
        .filter_map(|path| parse_track(path).ok())
        .collect();

    tracks
}

fn is_audio_file(path: &Path) -> bool {
    let ext = path.extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    matches!(ext.as_str(), 
        "mp3" | "flac" | "ogg" | "opus" | "m4a" | "aac" | "wma" | "wav" | "midi" | "mid"
    )
}

/// Ignora arquivos vazios ou muito grandes (provavelmente vídeos com extensão errada)
fn is_reasonable_size(path: &Path) -> bool {
    if let Ok(meta) = std::fs::metadata(path) {
        let size = meta.len();
        size > 1024 && size < 200_000_000  // Entre 1KB e 200MB
    } else {
        false
    }
}

fn parse_track(path: &Path) -> Result<Track, Box<dyn std::error::Error>> {
    let tagged_file = read_from_path(path)?;

    let tag = tagged_file.primary_tag()
        .or_else(|| tagged_file.first_tag())
        .ok_or("No tags found")?;

    let props = tagged_file.properties();

    Ok(Track {
        path: path.to_string_lossy().to_string(),
        title: tag.title().as_deref().unwrap_or("Unknown Title").to_string(),
        artist: tag.artist().as_deref().unwrap_or("Unknown Artist").to_string(),
        album: tag.album().as_deref().unwrap_or("Unknown Album").to_string(),
        duration_ms: props.duration().as_millis() as u32,
        format: path.extension().and_then(|e| e.to_str()).unwrap_or("unknown").to_uppercase(),
    })
}
