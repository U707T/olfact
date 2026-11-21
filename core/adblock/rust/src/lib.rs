//! adblock-rust 的 FFI 壳。
//!
//! 两层导出：
//! 1. **C ABI**（`olfact_adblock_*`）：不依赖 JVM，任何宿主都能 `dlopen` 调用；
//! 2. **JNI**（`Java_cn_olfact_core_adblock_*`）：薄薄一层，把 C ABI 包给 Kotlin。
//!
//! 句柄用 `Box<Engine>` 的裸指针，`destroy` 负责回收；所有函数对空指针返回安全默认值，
//! 保证 Kotlin 侧永远不需要因为 native 崩溃而 try/catch（虽然它还是包了）。

use std::ffi::{c_char, CStr, CString};
use std::ptr;

use adblock::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

const VERSION: &str = concat!("olfact-adblock-ffi/", env!("CARGO_PKG_VERSION"));

// ---------------------------------------------------------------- 类型转换

fn cstr_to_str<'a>(s: *const c_char) -> Option<&'a str> {
    if s.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(s) }.to_str().ok()
}

fn to_c_string(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Kotlin `ResourceType.code` → adblock 的请求类型字符串。
fn request_type_name(code: i32) -> &'static str {
    match code {
        0 => "document",
        1 => "subdocument",
        2 => "stylesheet",
        3 => "script",
        4 => "image",
        5 => "font",
        6 => "media",
        7 => "object",
        8 => "xmlhttprequest",
        9 => "ping",
        10 => "websocket",
        _ => "other",
    }
}

fn build_engine(rules: &str) -> Engine {
    let mut filter_set = FilterSet::new(true);
    filter_set.add_filter_list(rules, ParseOptions::default());
    Engine::from_filter_set(filter_set, true)
}

fn cosmetic_selectors(engine: &Engine, page_url: &str) -> String {
    let mut selectors: Vec<_> = engine.url_cosmetic_resources(page_url).hide_selectors.into_iter().collect();
    selectors.sort_unstable();
    selectors.join("\n")
}

// ------------------------------------------------------------------- C ABI

/// `rules_path` 指向规则文件（txt）。返回句柄，失败返回 null。
#[no_mangle]
pub extern "C" fn olfact_adblock_engine_create(rules_path: *const c_char) -> *mut Engine {
    let Some(path) = cstr_to_str(rules_path) else {
        return ptr::null_mut();
    };
    let Ok(text) = std::fs::read_to_string(path) else {
        return ptr::null_mut();
    };
    Box::into_raw(Box::new(build_engine(&text)))
}

/// 直接从规则文本构建；保留已弃用的缓存路径参数以维持 ABI。
#[no_mangle]
pub extern "C" fn olfact_adblock_engine_create_from_text(
    rules_text: *const c_char,
    _engine_path: *const c_char,
) -> *mut Engine {
    let Some(rules) = cstr_to_str(rules_text) else {
        return ptr::null_mut();
    };
    Box::into_raw(Box::new(build_engine(rules)))
}

/// 是否拦截。异常规则命中时同样返回 false（等价于放行）。
#[no_mangle]
pub extern "C" fn olfact_adblock_engine_check(
    engine: *const Engine,
    url: *const c_char,
    source_url: *const c_char,
    request_type: i32,
) -> bool {
    if engine.is_null() {
        return false;
    }
    let (Some(url), Some(source)) = (cstr_to_str(url), cstr_to_str(source_url)) else {
        return false;
    };
    let engine = unsafe { &*engine };
    let source = if source.is_empty() { url } else { source };
    match Request::new(url, source, request_type_name(request_type)) {
        Ok(request) => {
            let result = engine.check_network_request(&request);
            result.matched && result.exception.is_none() || result.important
        }
        Err(_) => false,
    }
}

/// 返回该页面命中的隐藏选择器，`\n` 分隔。调用方用 `olfact_adblock_string_free` 释放。
#[no_mangle]
pub extern "C" fn olfact_adblock_engine_cosmetic(
    engine: *const Engine,
    page_url: *const c_char,
) -> *mut c_char {
    if engine.is_null() {
        return ptr::null_mut();
    }
    let Some(page_url) = cstr_to_str(page_url) else {
        return ptr::null_mut();
    };
    let engine = unsafe { &*engine };
    to_c_string(cosmetic_selectors(engine, page_url))
}

#[no_mangle]
pub extern "C" fn olfact_adblock_engine_destroy(engine: *mut Engine) {
    if engine.is_null() {
        return;
    }
    unsafe { drop(Box::from_raw(engine)) };
}

#[no_mangle]
pub extern "C" fn olfact_adblock_version() -> *mut c_char {
    to_c_string(VERSION.to_string())
}

#[no_mangle]
pub extern "C" fn olfact_adblock_string_free(s: *mut c_char) {
    if s.is_null() {
        return;
    }
    unsafe { drop(CString::from_raw(s)) };
}

// --------------------------------------------------------------------- JNI

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn engine_ref<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        return None;
    }
    unsafe { (handle as *const Engine).as_ref() }
}

#[no_mangle]
pub extern "system" fn Java_cn_olfact_core_adblock_NativeAdBlockEngine_nativeCreate(
    mut env: JNIEnv,
    _this: JObject,
    rules: JString,
    _engine_path: JString,
) -> jlong {
    let rules_text = jstring_to_string(&mut env, &rules);
    if rules_text.is_empty() {
        return 0;
    }
    Box::into_raw(Box::new(build_engine(&rules_text))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_cn_olfact_core_adblock_NativeAdBlockEngine_nativeCheck(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    url: JString,
    source_url: JString,
    type_code: jint,
) -> jboolean {
    let Some(engine) = engine_ref(handle) else {
        return JNI_FALSE;
    };
    let url = jstring_to_string(&mut env, &url);
    let source = jstring_to_string(&mut env, &source_url);
    let source_ref = if source.is_empty() { url.as_str() } else { source.as_str() };
    let blocked = match Request::new(&url, source_ref, request_type_name(type_code as i32)) {
        Ok(request) => {
            let result = engine.check_network_request(&request);
            (result.matched && result.exception.is_none()) || result.important
        }
        Err(_) => false,
    };
    if blocked {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_olfact_core_adblock_NativeAdBlockEngine_nativeCosmetic(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    page_url: JString,
) -> jstring {
    let Some(engine) = engine_ref(handle) else {
        return ptr::null_mut();
    };
    let page = jstring_to_string(&mut env, &page_url);
    match env.new_string(cosmetic_selectors(engine, &page)) {
        Ok(s) => s.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_olfact_core_adblock_NativeAdBlockEngine_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe { drop(Box::from_raw(handle as *mut Engine)) };
}

#[no_mangle]
pub extern "system" fn Java_cn_olfact_core_adblock_AdBlockNativeLib_nativeVersion(
    env: JNIEnv,
    _this: JClass,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
