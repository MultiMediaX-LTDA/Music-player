use std::ffi::{CStr, CString, c_char};
use std::path::Path;
use walkdir::WalkDir;
use lofty::read_from_path;
use serde::Serialize;

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
pub extern "C" fn Java_android_kimyona_jammer_RustBridge_scanDirectory(
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

fn scan_directory(dir: &str) -> Vec<<Track> {
    let mut tracks = Vec::new();
    
    for entry in WalkDir::new(dir)
        .follow_links(true)
        .max_depth(5)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        let path = entry.path();
        if is_audio_file(path) {
            if let Ok(track) = parse_track(path) {
                tracks.push(track);
            }
        }
    }
    
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

fn parse_track(path: &Path) -> Result<<Track, Box<dyn std::error::Error>> {
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
