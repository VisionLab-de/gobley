/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

pub mod import;
mod stack;
mod wasm_bindgen;

use std::collections::BTreeSet;

use askama::Template;
use base64::Engine;
use walrus::{
    ir::Value, ConstExpr, ElementItems, ElementKind, Export, ExportItem, Function, Global,
    GlobalKind, Import, ImportKind, Module, ModuleGlobals, ValType,
};

use self::import::WasmFunctionImport;

#[derive(Debug)]
pub struct Transformer {
    module: Module,
    function_imports: Vec<WasmFunctionImport>,
    global_entities: Vec<GlobalEntity>,
    wasm_bindgen_js_modules: Vec<WasmBindgenJsModules>,
}

#[derive(Debug, Clone)]
struct GlobalEntity {
    pub modifier: String,
    pub name: String,
    pub expr: String,
    pub ty: String,
    pub lang: GlobalEntityLang,
}

#[derive(Debug, Clone)]
struct WasmBindgenJsModules {
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
enum GlobalEntityLang {
    JavaScript,
    Kotlin,
}

// TODO: Make this private in the next version
#[derive(Template)]
#[template(syntax = "kt", escape = "none", path = "js.kt")]
pub struct KotlinJsRenderer<'a> {
    package_name: Option<&'a str>,
    base64: &'a str,
    module: &'a Module,
    global_entities: &'a [GlobalEntity],
    wasm_bindgen_js_modules: &'a [WasmBindgenJsModules],
}

impl<'a> KotlinJsRenderer<'a> {
    fn import_modules(&self) -> Vec<&str> {
        let import_modules = self
            .module
            .imports
            .iter()
            .map(|i| &i.module)
            .filter(|wasm_module_name| {
                self.wasm_bindgen_js_modules
                    .iter()
                    .all(|js_module| **wasm_module_name != js_module.name)
            })
            .collect::<BTreeSet<_>>();

        import_modules.iter().map(|i| i.as_str()).collect()
    }

    fn imports_from_module<'b>(
        &'b self,
        module: impl AsRef<str> + 'b,
    ) -> impl Iterator<Item = &'a Import> + 'b {
        self.module
            .imports
            .iter()
            .filter(move |i| i.module == module.as_ref())
    }

    fn import_to_kt_signature(&self, import: &Import) -> String {
        match import.kind {
            ImportKind::Function(id) => self.function_to_kt_signature(self.module.funcs.get(id)),
            ImportKind::Table(_) => "WebAssembly.Table".to_string(),
            ImportKind::Memory(_) => "WebAssembly.Memory".to_string(),
            ImportKind::Global(id) => Self::global_to_kt_signature(self.module.globals.get(id)),
        }
    }

    fn import_to_function_table_entry_idx(&self, import: &Import) -> Option<usize> {
        let ImportKind::Function(function_id) = import.kind else {
            return None;
        };

        let Ok(Some(main_function_table)) = self.module.tables.main_function_table() else {
            return None;
        };

        for element in self.module.elements.iter() {
            let ElementItems::Functions(function_ids) = &element.items else {
                continue;
            };
            let Some(offset) = function_ids.iter().position(|id| *id == function_id) else {
                continue;
            };
            let ElementKind::Active {
                table,
                offset: element_offset,
            } = &element.kind
            else {
                continue;
            };
            if main_function_table != *table {
                continue;
            }

            fn get_usize_from_constexpr(
                globals: &ModuleGlobals,
                expr: &ConstExpr,
            ) -> Option<usize> {
                Some(match expr {
                    ConstExpr::Value(value) => match value {
                        Value::I32(i32) => *i32 as usize,
                        Value::I64(i64) => *i64 as usize,
                        Value::F32(f32) => *f32 as usize,
                        Value::F64(f64) => *f64 as usize,
                        Value::V128(v128) => *v128 as usize,
                    },
                    ConstExpr::Global(id) => {
                        return match &globals.get(*id).kind {
                            GlobalKind::Local(expr) => get_usize_from_constexpr(globals, expr),
                            _ => None,
                        }
                    }
                    _ => return None,
                })
            }

            let Some(element_offset) =
                get_usize_from_constexpr(&self.module.globals, element_offset)
            else {
                continue;
            };

            return Some(offset + element_offset);
        }

        None
    }

    fn exports(&self) -> impl Iterator<Item = &Export> {
        self.module.exports.iter()
    }

    fn export_to_kt_signature(&self, export: &Export) -> String {
        match export.item {
            ExportItem::Function(id) => self.function_to_kt_signature(self.module.funcs.get(id)),
            ExportItem::Table(_) => "WebAssembly.Table".to_string(),
            ExportItem::Memory(_) => "WebAssembly.Memory".to_string(),
            ExportItem::Global(id) => Self::global_to_kt_signature(self.module.globals.get(id)),
        }
    }

    fn function_to_kt_signature(&self, function: &Function) -> String {
        let ty = self.module.types.get(function.ty());
        let mut output = String::new();
        let mut first = true;
        output.push('(');

        for param_str in ty.params().iter().map(Self::map_val_type_to_kt) {
            if !first {
                output.push_str(", ");
            }
            first = false;
            output.push_str(param_str);
        }

        output.push_str(") -> ");

        if let Some(result) = ty.results().first() {
            output.push_str(Self::map_val_type_to_kt(result));
        } else {
            output.push_str("Unit");
        }

        output
    }

    fn global_to_kt_signature(global: &Global) -> String {
        let inner_ty = Self::map_val_type_to_kt(&global.ty);
        format!("WebAssembly.Global<{inner_ty}>")
    }

    fn map_val_type_to_kt(ty: &ValType) -> &'static str {
        match ty {
            ValType::I32 => "Int",
            ValType::F32 => "Float",
            ValType::F64 => "Double",
            _ => "Any",
        }
    }

    fn global_entities(&self) -> &[GlobalEntity] {
        self.global_entities
    }

    fn wasm_bindgen_js_modules(&self) -> &[WasmBindgenJsModules] {
        self.wasm_bindgen_js_modules
    }
}

/// Well-known import module that gobley-bindgen-emitted Kotlin/Wasm
/// expects to bind for callback-interface dispatch and async-future
/// continuations. Documented in `crates/gobley-uniffi-bindgen/src/templates/wasm-js/CallbackInterfaceImpl.kt`
/// and `wasm-js/Async.kt`.
const GOBLEY_CALLBACKS_MODULE: &str = "gobley_callbacks";

/// Description of a single Rust-side import the JS shim must bind to a
/// Kotlin `@JsExport` function. Currently always sourced from imports
/// in the `gobley_callbacks` module — see `Transformer::rust_callback_imports`.
#[derive(Debug, Clone)]
struct WasmJsCallbackBinding {
    module: String,
    name: String,
}

/// Renderer for the per-crate JavaScript shim that bridges the Rust
/// `wasm32-unknown-unknown` cdylib and the Kotlin/Wasm bindings.
///
/// Distinct from `KotlinJsRenderer` (which targets Kotlin/JS plain-`js()`
/// loaders): the wasmJs renderer emits ES module JavaScript (`.mjs`) and
/// is consumed at runtime by the Kotlin/Wasm app via `import("...")`.
///
/// The shim's responsibilities are documented in the template
/// (`templates/wasmjs_helpers.mjs`):
///   * `WebAssembly.instantiate` the Rust module with an import
///     dictionary mapping `gobley_callbacks.<name>` → Kotlin `@JsExport`
///     `fun <name>(...)`.
///   * Install a memory-growth-safe `globalThis.__gobleyWasmMemory`
///     `DataView` that the inline `@JsFun` accessors in
///     `wasm-js/PointerHelper.kt` read on every primitive call.
///   * Expose `globalThis.__gobleyRustExports` for inline `@JsFun`
///     bodies that reach into Rust exports directly (vtable index
///     lookups in `wasm-js/CallbackInterfaceImpl.kt`).
#[derive(Template)]
#[template(syntax = "kt", escape = "none", path = "wasmjs_helpers.mjs")]
pub struct KotlinWasmJsHelpersRenderer<'a> {
    crate_name: &'a str,
    callback_bindings: &'a [WasmJsCallbackBinding],
}

impl KotlinWasmJsHelpersRenderer<'_> {
    /// Names of `@JsExport` Kotlin functions the host must surface to
    /// the shim. We derive this from the Rust import set rather than a
    /// separate registry: every `gobley_callbacks.<name>` import in the
    /// Rust module corresponds to a Kotlin `@JsExport public fun <name>`.
    fn kotlin_callback_exports(&self) -> Vec<&str> {
        let mut names: Vec<&str> = self
            .callback_bindings
            .iter()
            .map(|b| b.name.as_str())
            .collect();
        names.sort_unstable();
        names.dedup();
        names
    }

    /// Rust-side imports the shim must bind to Kotlin `@JsExport`
    /// functions. Currently sourced from the `gobley_callbacks` module
    /// only; see `GOBLEY_CALLBACKS_MODULE`.
    fn rust_callback_imports(&self) -> &[WasmJsCallbackBinding] {
        self.callback_bindings
    }
}

impl Transformer {
    pub fn new(input: &[u8], function_imports: Vec<WasmFunctionImport>) -> anyhow::Result<Self> {
        Ok(Self {
            module: Module::from_buffer(input)?,
            function_imports,
            global_entities: vec![],
            wasm_bindgen_js_modules: vec![],
        })
    }

    fn transform(&mut self) -> anyhow::Result<()> {
        self.inject_stack_pointer_shim()?;
        self.inject_function_imports();
        if self.needs_wasm_bindgen() {
            self.transform_using_wasm_bindgen()?;
        }
        Ok(())
    }

    pub fn render_into_kt(mut self, package_name: Option<&str>) -> anyhow::Result<String> {
        use base64::prelude::BASE64_STANDARD;

        self.transform()?;

        let wasm = self.module.emit_wasm();
        let wasm_base64 = BASE64_STANDARD.encode(&wasm);
        let module = Module::from_buffer(&wasm)?;
        let renderer = KotlinJsRenderer {
            package_name,
            base64: &wasm_base64,
            module: &module,
            global_entities: &self.global_entities,
            wasm_bindgen_js_modules: &self.wasm_bindgen_js_modules,
        };
        Ok(renderer.render()?)
    }

    /// Render the per-crate Kotlin/Wasm JS shim (`.mjs`) for the
    /// already-loaded module. Idempotent: does NOT re-run the
    /// transformation pipeline. Call after `render_into_kt` on a fresh
    /// `Transformer` instance, or call standalone — both work.
    ///
    /// `crate_name` is the Cargo crate name (snake_cased version goes
    /// into the doc comment of the emitted module). The shim itself
    /// does not bake the name into runtime behavior.
    ///
    /// The shim binds Rust imports under module `gobley_callbacks` to
    /// Kotlin `@JsExport` functions of the same name. If the Rust
    /// module declares no such imports the shim still emits — the host
    /// can use it purely for `init` + memory bridging without callback
    /// support.
    pub fn render_into_mjs(&self, crate_name: &str) -> anyhow::Result<String> {
        let bindings = Self::collect_callback_bindings(&self.module);
        let renderer = KotlinWasmJsHelpersRenderer {
            crate_name,
            callback_bindings: &bindings,
        };
        Ok(renderer.render()?)
    }

    /// Walk the WASM imports section and collect every entry under
    /// `GOBLEY_CALLBACKS_MODULE`. Order is preserved as walrus iterates
    /// the section, which mirrors the original module's import order —
    /// keeps generated shim diffs reviewable across rebuilds.
    fn collect_callback_bindings(module: &Module) -> Vec<WasmJsCallbackBinding> {
        let mut bindings = vec![];
        for import in module.imports.iter() {
            if import.module != GOBLEY_CALLBACKS_MODULE {
                continue;
            }
            if !matches!(import.kind, ImportKind::Function(_)) {
                continue;
            }
            bindings.push(WasmJsCallbackBinding {
                module: import.module.clone(),
                name: import.name.clone(),
            });
        }
        bindings
    }
}
