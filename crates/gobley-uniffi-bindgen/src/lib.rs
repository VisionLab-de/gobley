/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

//! Kotlin Multiplatform binding generator for UniFFI.
//!
//! Entry point for the bindgen library half of `gobley-uniffi-bindgen`. The
//! [`KotlinBindingGenerator`] type implements `uniffi_bindgen::BindingGenerator`
//! and emits one Kotlin source set per source-set name (commonMain, jvmMain,
//! androidMain, nativeMain, wasmJsMain, …) per UniFFI component.
//!
//! Two orthogonal allowlist mechanisms gate which components actually get
//! emitted:
//!
//! 1. A manual allowlist (`allowed_crates`) populated from the CLI `--crates`
//!    flag. When set, only crates whose `crate_name` is in the set are emitted.
//! 2. An exported-symbol lookup (`export_lookup`) populated from the export
//!    table of the shipped cdylib via [`exports::read_exports`]. When set, only
//!    crates whose `ffi_<crate>_uniffi_contract_version` marker symbol is
//!    present in the library's exports are emitted.
//!
//! The two mechanisms intersect: a crate must pass both filters when both are
//! set. They exist because multi-crate cdylibs that link in shared core libs
//! (which themselves call `setup_scaffolding!()`) leak UniFFI metadata into the
//! staticlib's metadata blob, even though those core crates are not
//! consumer-facing and their FFI symbols may be stripped from the final
//! cdylib.

use std::{
    collections::{HashMap, HashSet},
    fs::File,
    io::Write,
    process::Command,
};

use heck::ToLowerCamelCase;

use anyhow::Result;
use camino::{Utf8Path, Utf8PathBuf};
use fs_err as fs;
use uniffi_bindgen::{BindingGenerator, Component, ComponentInterface, GenerationSettings};

pub mod exports;

mod gen_kotlin_multiplatform;
use gen_kotlin_multiplatform::{generate_bindings, Config};

/// Format the UniFFI contract-version marker symbol for `crate_name`.
///
/// `uniffi::setup_scaffolding!()` emits one such symbol per crate; the export
/// table of a shipped cdylib therefore contains exactly one marker per crate
/// whose FFI surface is actually reachable from the library. Used for both
/// stale-list detection (CLI side) and per-component filtering (bindgen side).
pub fn contract_version_marker(crate_name: &str) -> String {
    format!("ffi_{crate_name}_uniffi_contract_version")
}

/// Return the subset of `manual` whose contract-version markers are missing
/// from `exports`. An entry is "stale" iff the exported library does not carry
/// that crate's UniFFI marker — meaning either the CLI invocation passed a
/// crate that was never compiled in, or the cdylib was built without that
/// crate's `setup_scaffolding!()` reaching the final binary.
///
/// The caller (typically `main.rs`) decides what to do with the returned list:
/// fully stale (`stale.len() == manual.len()`) is a hard error, partially
/// stale is a warning, empty is a no-op.
pub fn check_stale(manual: &HashSet<String>, exports: &HashSet<String>) -> Vec<String> {
    let mut stale: Vec<String> = manual
        .iter()
        .filter(|c| !exports.contains(&contract_version_marker(c)))
        .cloned()
        .collect();
    stale.sort();
    stale
}

/// Two-stage allowlist for emitted UniFFI components.
///
/// `allowed_crates` holds the manual `--crates` set; `None` means "no manual
/// allowlist", `Some(set)` means "only these crates pass the manual filter".
///
/// `export_lookup` holds the export table of the shipped cdylib; `None` means
/// "no export-table filter", `Some(set)` means "only crates whose
/// `ffi_<crate>_uniffi_contract_version` marker is in the set pass the export
/// filter".
///
/// A crate must pass *both* filters (when set) to be emitted. See module-level
/// docs for rationale.
pub struct KotlinBindingGenerator {
    pub allowed_crates: Option<HashSet<String>>,
    pub export_lookup: Option<HashSet<String>>,
}

impl KotlinBindingGenerator {
    pub fn new(
        allowed_crates: Option<HashSet<String>>,
        export_lookup: Option<HashSet<String>>,
    ) -> Self {
        Self {
            allowed_crates,
            export_lookup,
        }
    }

    fn is_crate_allowed(&self, crate_name: &str) -> bool {
        if let Some(exports) = &self.export_lookup {
            if !exports.contains(&contract_version_marker(crate_name)) {
                return false;
            }
        }
        if let Some(allowed) = &self.allowed_crates {
            return allowed.contains(crate_name);
        }
        true
    }
}

impl BindingGenerator for KotlinBindingGenerator {
    type Config = Config;

    fn new_config(&self, root_toml: &toml::value::Value) -> Result<Self::Config> {
        Ok(root_toml.clone().try_into()?)
    }

    fn update_component_configs(
        &self,
        settings: &GenerationSettings,
        components: &mut Vec<Component<Self::Config>>,
    ) -> Result<()> {
        // For multi-crate library_mode, only the wrapper crate's config gets
        // loaded via `--config`; transitive crate components have empty Configs.
        // Propagate any pre-set cdylib_name (from the wrapper config or from
        // calc_cdylib_name) to all components so they all reference the same
        // wasm package emitted by gobley-wasm-rust. Without this, bindgen falls
        // back to `uniffi_<namespace>` per crate, breaking wasmJs codegen.
        let shared_cdylib_name: Option<String> = settings
            .cdylib
            .clone()
            .or_else(|| components.iter().find_map(|c| c.config.cdylib_name.clone()));
        for c in &mut *components {
            c.config
                .package_name
                .get_or_insert_with(|| format!("uniffi.{}", c.ci.namespace()));
            c.config.cdylib_name.get_or_insert_with(|| {
                shared_cdylib_name
                    .clone()
                    .unwrap_or_else(|| format!("uniffi_{}", c.ci.namespace()))
            });
        }
        // We need to update package names
        let packages = HashMap::<String, String>::from_iter(
            components
                .iter()
                .map(|c| (c.ci.crate_name().to_string(), c.config.package_name())),
        );
        for c in components {
            for (ext_crate, ext_package) in &packages {
                if ext_crate != c.ci.crate_name()
                    && !c.config.external_packages.contains_key(ext_crate)
                {
                    c.config
                        .external_packages
                        .insert(ext_crate.to_string(), ext_package.clone());
                }
            }
        }
        Ok(())
    }

    fn write_bindings(
        &self,
        settings: &GenerationSettings,
        components: &[Component<Self::Config>],
    ) -> Result<()> {
        let allowed_components: Vec<&Component<Self::Config>> = components
            .iter()
            .filter(|c| self.is_crate_allowed(c.ci.crate_name()))
            .collect();

        if allowed_components.is_empty() {
            anyhow::bail!(
                "no crates allowed by filter — empty intersection of metadata + --crates + --exported-lib (input had {} component(s))",
                components.len()
            );
        }

        for Component { ci, config, .. } in allowed_components.iter().copied() {
            let bindings = generate_bindings(config, ci)?;

            write_bindings_target(ci, settings, config, "common", bindings.common);

            if let Some(jvm) = bindings.jvm {
                write_bindings_target(ci, settings, config, "jvm", jvm);
            }
            if let Some(android) = bindings.android {
                write_bindings_target(ci, settings, config, "android", android);
            }
            if let Some(native) = bindings.native {
                write_bindings_target(ci, settings, config, "native", native);
            }
            if let Some(stub) = bindings.stub {
                write_bindings_target(ci, settings, config, "stub", stub);
            }
            if let Some(wasm_js) = bindings.wasm_js {
                write_bindings_target(ci, settings, config, "wasmJs", wasm_js);
            }

            if let Some(header) = bindings.header {
                write_cinterop(ci, &settings.out_dir, header);
            }
        }

        write_wasm_callback_imports(&settings.out_dir, &allowed_components);

        Ok(())
    }
}

fn write_bindings_target(
    ci: &ComponentInterface,
    settings: &GenerationSettings,
    config: &Config,
    target: &str,
    content: String,
) {
    let source_set_name = if config.kotlin_multiplatform {
        format!("{}Main", target)
    } else {
        String::from("main")
    };
    let package_path: Utf8PathBuf = config.package_name().split('.').collect();
    let file_name = format!("{}.{}.kt", ci.namespace(), target);

    let dest_dir = Utf8PathBuf::from(&settings.out_dir)
        .join(source_set_name)
        .join("kotlin")
        .join(package_path);
    let file_path = Utf8PathBuf::from(&dest_dir).join(file_name);

    fs::create_dir_all(dest_dir).unwrap();
    fs::write(&file_path, content).unwrap();

    if settings.try_format_code {
        println!("Code generation complete, formatting with ktlint (use --no-format to disable)");
        if let Err(e) = Command::new("ktlint").arg("-F").arg(&file_path).output() {
            println!(
                "Warning: Unable to auto-format {} using ktlint: {e:?}",
                file_path.file_name().unwrap(),
            );
        }
    }
}

fn write_wasm_callback_imports(out_dir: &Utf8Path, components: &[&Component<Config>]) {
    let content = generate_wasm_callback_imports_content(components);
    if content.is_empty() {
        return;
    }
    let file_path = Utf8PathBuf::from(out_dir).join("wasm_callback_imports.txt");
    fs::create_dir_all(out_dir).unwrap();
    fs::write(&file_path, content).unwrap();
}

fn ffi_type_to_wasm_params(ty: &uniffi_bindgen::interface::FfiType) -> Vec<&'static str> {
    use uniffi_bindgen::interface::FfiType;
    match ty {
        FfiType::Int8 | FfiType::UInt8 => vec!["i32"],
        FfiType::Int16 | FfiType::UInt16 => vec!["i32"],
        FfiType::Int32 | FfiType::UInt32 => vec!["i32"],
        FfiType::Int64 | FfiType::UInt64 | FfiType::Handle => vec!["i64"],
        FfiType::Float32 => vec!["f32"],
        FfiType::Float64 => vec!["f64"],
        FfiType::RustArcPtr(_)
        | FfiType::Callback(_)
        | FfiType::VoidPointer
        | FfiType::Reference(_)
        | FfiType::MutReference(_)
        | FfiType::Struct(_) => vec!["i32"],
        // wasm32 C ABI passes structs >8 bytes by pointer, not flattened.
        FfiType::RustBuffer(_) => vec!["i32"],
        FfiType::ForeignBytes => vec!["i32", "i32"],
        FfiType::RustCallStatus => vec!["i32"],
    }
}

fn generate_wasm_callback_imports_content(components: &[&Component<Config>]) -> String {
    let mut lines = Vec::new();

    for Component { ci, .. } in components.iter().copied() {
        let ns = ci.namespace();

        // Async continuation callback
        if ci.has_async_fns() {
            lines.push(format!(
                "gobley_callbacks:gobley_{ns}_async_continuation_callback:i64i32:"
            ));
        }

        // Foreign-future-free callback
        if ci.has_async_callback_interface_definition() {
            lines.push(format!(
                "gobley_callbacks:gobley_{ns}_foreign_future_free:i64:"
            ));
        }

        // Callback interface methods + uniffi_free
        let callback_objects = ci
            .object_definitions()
            .iter()
            .filter(|o| o.has_callback_interface())
            .collect::<Vec<_>>();

        for obj in &callback_objects {
            let name = obj.name();

            // uniffi_free
            lines.push(format!(
                "gobley_callbacks:gobley_callback_{name}_uniffi_free:i64:"
            ));

            // Per-method callbacks
            for (ffi_cb, meth) in obj.vtable_methods() {
                let meth_name = meth.name().to_lower_camel_case();
                let mut params: Vec<&str> = ffi_cb
                    .arguments()
                    .iter()
                    .flat_map(|arg| ffi_type_to_wasm_params(&arg.type_()))
                    .collect();
                if ffi_cb.has_rust_call_status_arg() {
                    params.push("i32");
                }
                let params_str = params.join("");
                let result_str = ffi_cb
                    .return_type()
                    .map(|rt| ffi_type_to_wasm_params(rt).join(""))
                    .unwrap_or_default();
                lines.push(format!(
                    "gobley_callbacks:gobley_callback_{name}_{meth_name}:{params_str}:{result_str}"
                ));
            }
        }

        // Legacy callback_interface_definitions (pre-trait callback interfaces)
        for cbi in ci.callback_interface_definitions() {
            let name = cbi.name();

            lines.push(format!(
                "gobley_callbacks:gobley_callback_{name}_uniffi_free:i64:"
            ));

            let methods = cbi.methods();
            let ffi_cbs = cbi.ffi_callbacks();
            for (meth, ffi_cb) in methods.iter().zip(ffi_cbs.iter()) {
                let meth_name = meth.name().to_lower_camel_case();
                let mut params: Vec<&str> = ffi_cb
                    .arguments()
                    .iter()
                    .flat_map(|arg| ffi_type_to_wasm_params(&arg.type_()))
                    .collect();
                if ffi_cb.has_rust_call_status_arg() {
                    params.push("i32");
                }
                let params_str = params.join("");
                let result_str = ffi_cb
                    .return_type()
                    .map(|rt| ffi_type_to_wasm_params(rt).join(""))
                    .unwrap_or_default();
                lines.push(format!(
                    "gobley_callbacks:gobley_callback_{name}_{meth_name}:{params_str}:{result_str}"
                ));
            }
        }
    }

    lines.sort();
    lines.dedup();
    if lines.is_empty() {
        String::new()
    } else {
        lines.join("\n") + "\n"
    }
}

fn write_cinterop(ci: &ComponentInterface, out_dir: &Utf8Path, content: String) {
    let dst_dir = Utf8PathBuf::from(out_dir)
        .join("nativeInterop")
        .join("cinterop")
        .join("headers")
        .join(ci.namespace());
    fs::create_dir_all(&dst_dir).unwrap();
    let file_path = dst_dir.join(format!("{}.h", ci.namespace()));
    let mut f = File::create(file_path).unwrap();
    write!(f, "{}", content).unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(items: &[&str]) -> HashSet<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    fn markers(crates: &[&str]) -> HashSet<String> {
        crates.iter().map(|c| contract_version_marker(c)).collect()
    }

    // ---------- Constructor / is_crate_allowed precedence matrix (12 cases) -----

    #[test]
    fn matrix_1_no_filters_admits_anything() {
        let g = KotlinBindingGenerator::new(None, None);
        assert!(g.is_crate_allowed("any"));
    }

    #[test]
    fn matrix_2_export_only_admits_crate_with_marker() {
        let g = KotlinBindingGenerator::new(None, Some(markers(&["a"])));
        assert!(g.is_crate_allowed("a"));
    }

    #[test]
    fn matrix_3_export_only_rejects_crate_without_marker() {
        let g = KotlinBindingGenerator::new(None, Some(markers(&["a"])));
        assert!(!g.is_crate_allowed("b"));
    }

    #[test]
    fn matrix_4_export_only_empty_admits_nothing() {
        let g = KotlinBindingGenerator::new(None, Some(HashSet::new()));
        assert!(!g.is_crate_allowed("anything"));
    }

    #[test]
    fn matrix_5_manual_only_admits_listed_crate() {
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), None);
        assert!(g.is_crate_allowed("a"));
    }

    #[test]
    fn matrix_6_manual_only_rejects_unlisted_crate() {
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), None);
        assert!(!g.is_crate_allowed("b"));
    }

    #[test]
    fn matrix_7_both_filters_agree_admit() {
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), Some(markers(&["a"])));
        assert!(g.is_crate_allowed("a"));
    }

    #[test]
    fn matrix_8_both_filters_agree_reject() {
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), Some(markers(&["a"])));
        assert!(!g.is_crate_allowed("b"));
    }

    #[test]
    fn matrix_9_export_lookup_can_veto_manual_listed_crate() {
        // Manual allows "a" but exports only show marker for "b" — "a" rejected.
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), Some(markers(&["b"])));
        assert!(!g.is_crate_allowed("a"));
    }

    #[test]
    fn matrix_10_manual_can_veto_export_listed_crate() {
        // Exports show marker for "b" but manual only allows "a" — "b" rejected.
        let g = KotlinBindingGenerator::new(Some(s(&["a"])), Some(markers(&["b"])));
        assert!(!g.is_crate_allowed("b"));
    }

    #[test]
    fn matrix_11_manual_empty_admits_nothing() {
        let g = KotlinBindingGenerator::new(Some(HashSet::new()), None);
        assert!(!g.is_crate_allowed("any"));
    }

    #[test]
    fn matrix_12_manual_empty_overrides_export_match() {
        let g = KotlinBindingGenerator::new(Some(HashSet::new()), Some(markers(&["a"])));
        assert!(!g.is_crate_allowed("a"));
    }

    // ---------- check_stale helper --------------------------------------------

    #[test]
    fn check_stale_all_missing_returns_full_set() {
        let manual = s(&["a", "b"]);
        let exports = markers(&["c"]);
        let stale = check_stale(&manual, &exports);
        assert_eq!(stale, vec!["a".to_string(), "b".to_string()]);
    }

    #[test]
    fn check_stale_some_missing_returns_only_missing() {
        let manual = s(&["a", "b", "c"]);
        let exports = markers(&["a", "b"]);
        let stale = check_stale(&manual, &exports);
        assert_eq!(stale, vec!["c".to_string()]);
    }

    #[test]
    fn check_stale_none_missing_returns_empty() {
        let manual = s(&["a", "b"]);
        let exports = markers(&["a", "b"]);
        let stale = check_stale(&manual, &exports);
        assert!(stale.is_empty());
    }

    #[test]
    fn check_stale_both_empty_returns_empty() {
        let manual = HashSet::new();
        let exports = HashSet::new();
        let stale = check_stale(&manual, &exports);
        assert!(stale.is_empty());
    }

    #[test]
    fn check_stale_empty_manual_returns_empty() {
        let manual = HashSet::new();
        let exports = markers(&["a"]);
        let stale = check_stale(&manual, &exports);
        assert!(stale.is_empty());
    }

    // ---------- contract_version_marker formatter -----------------------------

    #[test]
    fn marker_format_matches_uniffi_setup_scaffolding() {
        // uniffi_macros::setup_scaffolding emits this exact spelling.
        assert_eq!(
            contract_version_marker("rs_social_uniffi"),
            "ffi_rs_social_uniffi_uniffi_contract_version"
        );
    }
}
