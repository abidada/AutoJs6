# QuickJS vendored source

- Upstream project: https://github.com/bellard/quickjs
- Pinned commit: `535a7c250ff4a577ec36c3e103daab6dadeea650` (master, fetched 2026-10-07)
- QuickJS VERSION: `2026-06-04`
- Reason: upstream Operit fetched bellard/quickjs@master at CMake configure time
  (`operit_git_source.cmake` → GitHub archive). Vendoring removes the build-time network
  dependency (port plan decision C6) and pins the exact interpreter the JNI layer is built against.
- Layout: this directory is the untouched upstream source tree (minus non-build files kept for
  reference: tests/examples/doc stay; nothing was modified).
- Upgrading: re-fetch a new ref, replace this directory, update the pin above, rebuild
  `:modules:operit-quickjs` on all 4 ABIs, run the Operit sandbox-package acceptance (P5.3).
