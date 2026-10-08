#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Find ALL call sites of listener/observer registration APIs in an APK.

Disassembles every dex with build-tools dexdump and tracks the enclosing
class/method. Finds hidden registration sites that source grep cannot see
(generated code, cross-module calls, registration buried inside a helper API).

Usage:
  python find_register_sites.py --apk app-debug.apk \
      [--patterns "eventbus/EventBus;.register,Other;.addListener"] [--dexdump PATH]

NOTE: dexdump prints method refs in DOT style — `Lorg/greenrobot/eventbus/EventBus;.register:(...)V`
so the EventBus pattern is `eventbus/EventBus;.register`, NOT `->register`.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_PATTERNS = "eventbus/EventBus;.register"

def locate_dexdump(explicit=None):
    if explicit and os.path.isfile(explicit):
        return explicit
    which = os.name == "nt" and "dexdump.exe" or "dexdump"
    w = __import__("shutil").which(which)
    if w: return w
    sdk_dirs = []
    for prop in ("local.properties", os.path.join(os.getcwd(), "local.properties")):
        if os.path.isfile(prop):
            for line in open(prop, encoding="utf-8", errors="replace"):
                if line.startswith("sdk.dir"):
                    sdk_dirs.append(line.split("=", 1)[1].strip().replace("\\:", ":"))
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env): sdk_dirs.append(os.environ[env])
    for sdk in sdk_dirs:
        bts = sorted(glob.glob(os.path.join(sdk, "build-tools", "*")), reverse=True)
        for bt in bts:
            d = os.path.join(bt, which)
            if os.path.isfile(d): return d
    raise RuntimeError("dexdump not found — pass --dexdump or set sdk.dir/ANDROID_SDK_ROOT")

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--patterns", default=DEFAULT_PATTERNS,
                    help="comma-separated regex substrings, dexdump DOT style")
    ap.add_argument("--dexdump", default=None)
    args = ap.parse_args()

    dexdump = locate_dexdump(args.dexdump)
    patterns = [p.strip() for p in args.patterns.split(",") if p.strip()]
    call_re = re.compile("|".join("(?:%s)" % re.escape(p) for p in patterns))
    cls_re = re.compile(r"Class descriptor\s*:\s*'([^']+)'")
    name_re = re.compile(r"name\s*:\s*'([^']+)'")

    def dex_order(n):
        m = re.search(r"\d+", n)
        return int(m.group()) if m else 0

    zf = zipfile.ZipFile(args.apk)
    dexes = sorted((n for n in zf.namelist() if re.match(r"classes\d*\.dex$", n)),
                   key=dex_order)
    print("dex files: %d | patterns: %s" % (len(dexes), patterns), flush=True)

    results = []
    tmp = tempfile.mkdtemp(prefix="mlh-dex-")
    for dex in dexes:
        p = os.path.join(tmp, dex)
        with open(p, "wb") as fh:
            fh.write(zf.read(dex))
        proc = subprocess.Popen([dexdump, "-d", p], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True,
                                encoding="utf-8", errors="replace")
        cur_cls = cur_meth = "?"
        for line in proc.stdout:
            m = cls_re.search(line)
            if m:
                cur_cls = m.group(1); continue
            m = name_re.search(line)
            if m:
                cur_meth = m.group(1); continue
            if call_re.search(line):
                results.append((cur_cls, cur_meth, dex))
        proc.wait()
        os.remove(p)

    print()
    print("=== registration call sites (%d) ===" % len(results))
    seen = set()
    for cls, meth, dex in results:
        key = (cls, meth)
        mark = "" if key not in seen else "  (dup)"
        seen.add(key)
        print("  %-72s %-28s [%s]%s" % (cls[:72], meth[:28], dex, mark))
    if not results:
        print("  (none — check pattern style: dexdump uses DOT style, e.g. 'EventBus;.register')")
    return 0

if __name__ == "__main__":
    sys.exit(main())
