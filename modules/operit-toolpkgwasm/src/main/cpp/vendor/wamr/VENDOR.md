# WAMR (WebAssembly Micro Runtime) — vendored subset

Vendored for the Operit `toolpkgwasm` native target (offline-reproducible build).

- Upstream: https://github.com/bytecodealliance/wasm-micro-runtime
- Pin: `38b044a676893e678ceb19babaa431ca5e460114` (main, 2026-10-08)
- Vendored: `core/` + `build-scripts/` + `LICENSE` (only what `runtime_lib.cmake` needs;
  no product-mini/samples/tests/docs).
- Size: ~10 MB.

Upstream Operit fetches this at configure time via `cmake/operit_git_source.cmake`
(`operit_prepare_git_source(... wamr ... "main")`). Vendoring removes the network dependency.

## Refresh

```
git clone --depth 1 https://github.com/bytecodealliance/wasm-micro-runtime.git /tmp/wamr
rm -rf core build-scripts && cp -r /tmp/wamr/core /tmp/wamr/build-scripts . && cp /tmp/wamr/LICENSE .
```
Then update the pin above.
