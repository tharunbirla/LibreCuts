// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! The Java VM and the app's context, for the parts of the engine that
//! reach Android's own services through them: the sound device (cpal opens
//! it through `ndk-context`).
//!
//! The Kotlin bindings load this library through JNA, which never calls
//! `JNI_OnLoad`; so the app also loads it once with `System.loadLibrary`
//! (which does) and then hands its context over through
//! `NativeContext.init`. The only unsafe code in the crate is here: two
//! pointers kept for the life of the process.
#![allow(unsafe_code)]

use std::ffi::c_void;
use std::sync::atomic::{AtomicPtr, Ordering};

use jni_sys::{JNI_VERSION_1_6, JNIEnv, JavaVM, jclass, jint, jobject};

static VM: AtomicPtr<JavaVM> = AtomicPtr::new(std::ptr::null_mut());

/// Called by the system when `System.loadLibrary` loads this library: the
/// Java VM, kept for [`Java_io_github_artcraftandroid_capcraft_NativeContext_init`].
///
/// # Safety
///
/// The system passes a valid JavaVM pointer that lives as long as the
/// process.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    VM.store(vm, Ordering::Release);
    JNI_VERSION_1_6
}

/// `NativeContext.init(context)`: keeps a global reference to the app's
/// context and gives it, with the VM, to `ndk-context`. Later calls do
/// nothing.
///
/// # Safety
///
/// Called by the Java VM with a valid environment and a live context object.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_artcraftandroid_capcraft_NativeContext_init(
    env: *mut JNIEnv,
    _class: jclass,
    context: jobject,
) {
    static DONE: std::sync::Once = std::sync::Once::new();
    let vm = VM.load(Ordering::Acquire);
    if vm.is_null() || env.is_null() || context.is_null() {
        log::warn!("android: no Java VM or context to hand to the engine");
        return;
    }
    DONE.call_once(|| {
        // SAFETY: `env` is the calling thread's environment, valid for this
        // call; the function table it points to is the VM's.
        let global = unsafe {
            match (**env).NewGlobalRef {
                Some(new_global_ref) => new_global_ref(env, context),
                None => std::ptr::null_mut(),
            }
        };
        if global.is_null() {
            log::warn!("android: could not keep the app's context");
            return;
        }
        // SAFETY: both pointers stay valid for the life of the process: the
        // VM is the process's, and the global reference is never deleted.
        unsafe { ndk_context::initialize_android_context(vm.cast(), global.cast()) };
        log::info!("android: context handed to the engine");
    });
}
