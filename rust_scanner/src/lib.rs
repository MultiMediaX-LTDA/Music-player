use std::path::Path;

use lofty::file::{AudioFile, TaggedFileExt};
use lofty::probe::Probe;
use lofty::tag::ItemKey;
use serde::{Deserialize, Serialize};
use walkdir::WalkDir;

#[derive(Serialize, Deserialize, Debug)]
struct TrackInfo {
    path: String,
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    album_artist: Option<String>,
    genre: Option<String>,
    year: Option<u32>,
    track_number: Option<u32>,
    duration_ms: Option<u64>,
    bitrate: Option<u32>,
    sample_rate: Option<u32>,
    channels: Option<u32>,
}

fn scan_directory(dir: &str) -> Vec<TrackInfo> {
    let mut tracks = Vec::new();
    let path = Path::new(dir);

    if !path.exists() || !path.is_dir() {
        return tracks;
    }

    for entry in WalkDir::new(path)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        let path = entry.path();
        if path.is_file() {
            if let Ok(mut probe) = Probe::open(path) {
                if let Ok(tagged_file) = probe.read() {
                    let properties = tagged_file.properties();
                    let tag = tagged_file.primary_tag();

                    let track = TrackInfo {
                        path: path.to_string_lossy().to_string(),
                        title: tag.and_then(|t| t.title().map(|s| s.to_string())),
                        artist: tag.and_then(|t| t.artist().map(|s| s.to_string())),
                        album: tag.and_then(|t| t.album().map(|s| s.to_string())),
                        album_artist: tag.and_then(|t| {
                            t.get_string(&ItemKey::AlbumArtist)
                                .map(|s| s.to_string())
                        }),
                        genre: tag.and_then(|t| t.genre().map(|s| s.to_string())),
                        year: tag.and_then(|t| t.year()),
                        track_number: tag.and_then(|t| t.track()),
                        duration_ms: Some(properties.duration().as_millis() as u64),
                        bitrate: properties.audio_bitrate(),
                        sample_rate: properties.sample_rate(),
                        channels: properties.channels().map(|c| c as u32),
                    };
                    tracks.push(track);
                }
            }
        }
    }

    tracks
}

#[no_mangle]
pub extern "system" fn Java_android_kimyona_jammer_core_media_RustBridge_nativeScanDirectory(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    dir: jni::objects::JString,
) -> jni::sys::jstring {
    // Convert JString → Rust String safely
    let dir_str: String = match env.get_string(&dir) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let tracks = scan_directory(&dir_str);

    let json = match serde_json::to_string(&tracks) {
        Ok(s) => s,
        Err(_) => "[]".to_string(),
    };

    // Convert Rust String → JString (Java)
    match env.new_string(&json) {
        Ok(jstring) => jstring.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_android_kimyona_jammer_core_media_RustBridge_nativeGetVersion(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let version = env!("CARGO_PKG_VERSION");
    match _env.new_string(version) {
        Ok(jstring) => jstring.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
