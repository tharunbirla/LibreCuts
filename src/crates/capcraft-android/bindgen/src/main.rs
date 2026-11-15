// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer and Concat contributors
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! `uniffi-bindgen` for `capcraft-android`: generates its Kotlin bindings
//! from the built library, e.g.
//! `cargo run -p capcraft-android-bindgen -- generate libcapcraft_android.so --language kotlin --out-dir out`
//! (`android/app/build.gradle.kts` runs it).
#![forbid(unsafe_code)]

fn main() {
    uniffi::uniffi_bindgen_main()
}
