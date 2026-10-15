//! Deterministic 60/30 FPS comparison timeline used by Android through JNI.
//! Timing does not depend on a GPU, wall-clock jitter or the Android UI toolkit.
use jni::{objects::{JClass, JString}, sys::{jlong, jstring}, JNIEnv};
use serde::{Deserialize, Serialize};
use std::{panic::{catch_unwind, AssertUnwindSafe}};

#[derive(Clone, Debug, Deserialize)]
struct Card { #[serde(rename="Title", default)] title: String }
#[derive(Clone, Debug, Deserialize)]
struct Project {
    #[serde(rename="Cards", default)] cards: Vec<Card>,
    #[serde(rename="Fps", default="default_fps")] fps: u32,
    #[serde(rename="SecondsPerCard", default="default_seconds")] seconds: u32,
}
fn default_fps() -> u32 { 30 }
fn default_seconds() -> u32 { 2 }

fn normalize(json: &str) -> Result<Project, String> {
    let project: Project = serde_json::from_str(json).map_err(|e| format!("Project JSON: {e}"))?;
    if project.cards.is_empty() { return Err("Add at least one comparison card".to_string()); }
    if project.cards.len() > 500 { return Err("Too many cards (limit 500)".to_string()); }
    if !(1..=60).contains(&project.fps) { return Err("FPS must be 1–60".to_string()); }
    if !(1..=20).contains(&project.seconds) { return Err("Card duration must be 1–20 seconds".to_string()); }
    Ok(project)
}

#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct Frame {
    pub frame: u64,
    pub total_frames: u64,
    pub fps: u32,
    pub current_card: usize,
    pub next_card: usize,
    pub current_x: f64,
    pub next_x: f64,
    pub badge_scale: f64,
    pub transition: f64,
    pub current_title: String,
    pub next_title: String,
}

/// Relative 16:9 canvas X translation in units of logical pixels (960-wide).
/// Exactly deterministic at any integer frame clock. The final card never scrolls away.
fn scene(project: &Project, frame: u64) -> Frame {
    let unit = (project.fps as u64) * (project.seconds as u64);
    let count = project.cards.len();
    let total = unit * count as u64;
    let frame = frame.min(total.saturating_sub(1));
    let idx = ((frame / unit) as usize).min(count - 1);
    let local = frame % unit;
    let hold = (unit * 3 / 4).max(1);
    let moving = idx + 1 < count && local >= hold && unit > hold;
    let linear = if moving { (local - hold) as f64 / (unit - hold) as f64 } else { 0.0 };
    let eased = linear * linear * (3.0 - 2.0 * linear);
    let appear = (local as f64 / (project.fps as f64 * 0.25).max(1.0)).min(1.0);
    let pulse = 0.9 + 0.1 * (1.0 - (1.0 - appear).powi(3));
    let next = (idx + 1).min(count - 1);
    Frame {
        frame, total_frames: total, fps: project.fps,
        current_card: idx, next_card: next,
        current_x: -960.0 * eased, next_x: 960.0 * (1.0 - eased),
        badge_scale: pulse, transition: eased,
        current_title: project.cards[idx].title.clone(),
        next_title: project.cards[next].title.clone(),
    }
}
pub fn frame_json(project_json: &str, frame: u64) -> String {
    match normalize(project_json) {
        Ok(p) => serde_json::to_string(&scene(&p, frame)).unwrap_or_else(|_| "{}".to_string()),
        Err(msg) => format!(r#"{{"error":{}}}"#, serde_json::to_string(&msg).unwrap_or_default()),
    }
}
pub fn total_frames(project_json: &str) -> i64 {
    normalize(project_json).map(|p| p.cards.len() as i64 * p.fps as i64 * p.seconds as i64).unwrap_or(-1)
}

fn safely_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_retrofrost_cubicalcompare_v5_NativeEngine_scene(
    mut env: JNIEnv, _class: JClass, project_json: JString, frame: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let json: String = env.get_string(&project_json).map(|j| j.into()).unwrap_or_default();
        frame_json(&json, frame.max(0) as u64)
    })).unwrap_or_else(|_| r#"{"error":"Rust engine panic"}"#.to_string());
    safely_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_retrofrost_cubicalcompare_v5_NativeEngine_totalFrames(
    mut env: JNIEnv, _class: JClass, project_json: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let json: String = env.get_string(&project_json).map(|j| j.into()).unwrap_or_default();
        total_frames(&json) as jlong
    })).unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;
    const SAMPLE: &str = r#"{"Cards":[{"Title":"A"},{"Title":"B"},{"Title":"C"}],"Fps":30,"SecondsPerCard":2}"#;
    #[test] fn deterministic_integer_timeline() {
        let p = normalize(SAMPLE).unwrap();
        assert_eq!(total_frames(SAMPLE), 180);
        let hold = scene(&p, 4);
        assert_eq!(hold.current_card, 0);
        assert_eq!(hold.transition, 0.0);
        let moving = scene(&p, 53);
        assert_eq!(moving.current_card, 0);
        assert!(moving.transition > 0.0);
        assert!(moving.current_x < 0.0);
        assert!(moving.next_x > 0.0);
        let next = scene(&p, 60);
        assert_eq!(next.current_card, 1);
        assert_eq!(next.current_x, 0.0);
        assert_eq!(next.badge_scale, 0.9);
    }
    #[test] fn last_card_does_not_disappear() {
        let p = normalize(SAMPLE).unwrap();
        for f in 120..180 {
            assert_eq!(scene(&p, f).transition, 0.0);
        }
    }
    #[test] fn repeatable_and_bounds_checked() {
        assert_eq!(frame_json(SAMPLE, 55), frame_json(SAMPLE, 55));
        assert_eq!(scene(&normalize(SAMPLE).unwrap(), 500).frame, 179);
        assert!(frame_json("{}", 0).contains("error"));
    }
}
