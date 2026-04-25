/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

use std::{collections::HashMap, fs::File, io::Write, process::Command};

use heck::ToLowerCamelCase;

use anyhow::Result;
use camino::{Utf8Path, Utf8PathBuf};
use fs_err as fs;
use uniffi_bindgen::{BindingGenerator, Component, ComponentInterface, GenerationSettings};

mod gen_kotlin_multiplatform;
use gen_kotlin_multiplatform::{generate_bindings, Config};

pub struct KotlinBindingGenerator;
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
        for c in &mut *components {
            c.config
                .package_name
                .get_or_insert_with(|| format!("uniffi.{}", c.ci.namespace()));
            c.config.cdylib_name.get_or_insert_with(|| {
                settings
                    .cdylib
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
        for Component { ci, config, .. } in components {
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

        write_wasm_callback_imports(&settings.out_dir, components);

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

fn write_wasm_callback_imports(out_dir: &Utf8Path, components: &[Component<Config>]) {
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

fn generate_wasm_callback_imports_content(components: &[Component<Config>]) -> String {
    let mut lines = Vec::new();

    for Component { ci, .. } in components {
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
