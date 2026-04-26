/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

use std::fs::{self, File};
use std::io::{BufRead, BufReader};
use std::str::FromStr as _;

use anyhow::Context;
use camino::Utf8PathBuf;
use clap::Parser;
use gobley_wasm_transformer::import::WasmFunctionImport;
use gobley_wasm_transformer::Transformer;

#[derive(Parser)]
#[clap(name = clap::crate_name!())]
#[clap(version = clap::crate_version!())]
#[clap(propagate_version = true)]
struct Cli {
    /// The path to the .wasm file to transform.
    #[clap(long, short)]
    input: Utf8PathBuf,

    /// The path where the output .kt file will be generated.
    #[clap(long, short)]
    output: Utf8PathBuf,

    /// The package name to be used in the resulting .kt file.
    #[clap(long, short)]
    package_name: Option<String>,

    /// The path to the file containing the list of line-separated additional function imports to be
    /// inserted into the transformed WASM module.
    #[clap(long, short)]
    function_imports_file: Option<Utf8PathBuf>,

    /// Optional path where the Kotlin/Wasm JavaScript shim
    /// (`<crate>_wasmjs_helpers.mjs`) will be written. When supplied,
    /// the transformer additionally generates the per-crate JS bridge
    /// described in `KotlinWasmJsHelpersRenderer` (T0.C.6). When absent,
    /// only the Kotlin/JS file is generated (back-compat with the
    /// existing `kotlin("js")` flow).
    #[clap(long)]
    mjs_output: Option<Utf8PathBuf>,

    /// Optional path where the Kotlin/Wasm-flavored Kotlin file will be
    /// written, in addition to the Kotlin/JS file at `--output`. When
    /// supplied, the transformer runs `Transformer::render_into_wasmjs_kt`
    /// against the same input WASM module and writes a Kotlin/Wasm-
    /// compatible source file alongside the Kotlin/JS one. The two
    /// files share the same package name (passed via `--package-name`)
    /// but live at different filesystem paths so the wasmJs source set
    /// can be wired separately from the js source set in Gradle.
    ///
    /// When absent, only the Kotlin/JS file at `--output` is generated
    /// (back-compat with the pre-T0.C.6.b pipeline).
    ///
    /// The wasmJs Kotlin file is intentionally minimal: package
    /// declaration, chunked base64 wasm bytes, embedded helper-module
    /// source, and a small `gobleyWasmBytes()` decoder. The actual
    /// `WebAssembly.instantiate` call still lives in the per-crate
    /// helper shim, but the wasmJs Kotlin file also embeds that shim as
    /// a data URL for downstream consumers. See `KotlinWasmJsRenderer`
    /// doc comment in `lib.rs` for the full rationale.
    #[clap(long)]
    wasmjs_output: Option<Utf8PathBuf>,

    /// Crate name used for documentation comments in the generated
    /// `.mjs` shim. Required when `--mjs-output` is set; ignored
    /// otherwise. Defaults to the file stem of `--input` if not given.
    #[clap(long)]
    crate_name: Option<String>,
}

fn main() -> anyhow::Result<()> {
    let Cli {
        input,
        output,
        package_name,
        function_imports_file: function_imports_file_path,
        mjs_output,
        wasmjs_output,
        crate_name,
    } = Cli::parse();
    let input_bytes = fs::read(&input).with_context(|| format!("failed to read `{input}`"))?;

    let mut function_imports = vec![];
    if let Some(function_imports_file_path) = function_imports_file_path {
        let function_imports_file = File::open(&function_imports_file_path)
            .with_context(|| format!("failed to open `{function_imports_file_path}`"))?;
        for line in BufReader::new(function_imports_file).lines() {
            let line = line.with_context(|| {
                format!("failed to read lines from `{function_imports_file_path}`")
            })?;
            let line = line.trim();
            if !line.is_empty() {
                let function_import = WasmFunctionImport::from_str(line).with_context(|| {
                    format!(
                "failed to read function import definitions from `{function_imports_file_path}`"
            )
                })?;
                function_imports.push(function_import);
            }
        }
    }

    let resolved_crate_name = if mjs_output.is_some() || wasmjs_output.is_some() {
        Some(
            crate_name
                .clone()
                .or_else(|| input.file_stem().map(|stem| stem.replace('-', "_")))
                .ok_or_else(|| {
                    anyhow::anyhow!(
                        "--crate-name is required when --mjs-output or --wasmjs-output is set, and could not be inferred from input file name"
                    )
                })?,
        )
    } else {
        None
    };

    // The Kotlin/JS render path consumes the transformer; if we also
    // need the .mjs shim we have to build a second `Transformer`. Both
    // start from the same source bytes; the .mjs render path does NOT
    // run `transform()` (it inspects the source module's import section
    // directly), so the cost is just one extra walrus parse — cheap
    // relative to the bindgen + cargo work upstream.
    if let Some(mjs_output_path) = mjs_output.as_ref() {
        let crate_name = resolved_crate_name
            .as_deref()
            .expect("crate name must be resolved when emitting the helper module");
        let mut mjs_transformer = Transformer::new(&input_bytes, function_imports.clone())?;
        mjs_transformer.transform_all()?;
        let mjs_content = mjs_transformer.render_into_mjs(crate_name)?;
        if let Some(parent) = mjs_output_path.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("failed to create directory `{parent}`"))?;
        }
        fs::write(mjs_output_path, mjs_content)
            .with_context(|| format!("failed to write `{mjs_output_path}`"))?;
    }

    // T0.C.6.b: optional Kotlin/Wasm-flavored Kotlin file. Each
    // `render_into_*_kt` call consumes its `Transformer`, so we build a
    // dedicated one here. Same input bytes, same `function_imports`
    // injection (so the WASM payload baked into the wasmJs file matches
    // the JS file's payload byte-for-byte). Cost: one extra walrus parse
    // + one extra `transform()` pass — still cheap next to the cargo
    // build that produced the input.
    if let Some(wasmjs_output_path) = wasmjs_output.as_ref() {
        let crate_name = resolved_crate_name
            .as_deref()
            .expect("crate name must be resolved when emitting wasmJs Kotlin");
        let wasmjs_transformer = Transformer::new(&input_bytes, function_imports.clone())?;
        let wasmjs_content =
            wasmjs_transformer.render_into_wasmjs_kt(package_name.as_deref(), crate_name)?;
        if let Some(parent) = wasmjs_output_path.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("failed to create directory `{parent}`"))?;
        }
        fs::write(wasmjs_output_path, wasmjs_content)
            .with_context(|| format!("failed to write `{wasmjs_output_path}`"))?;
    }

    let transformer = Transformer::new(&input_bytes, function_imports)?;
    let output_kt = transformer.render_into_kt(package_name.as_deref())?;
    fs::write(&output, output_kt).with_context(|| format!("failed to write `{output}`"))?;
    Ok(())
}
