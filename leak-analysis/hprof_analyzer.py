#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline Android/JVM HPROF analyzer (zero dependency).

Usage:
  python hprof_analyzer.py <dump.hprof> [report.txt] [--project-prefix a.b.,c.d.] [--quiet]

- Full structural parse of the .hprof (integrity check).
- Instance census per class (counts + shallow bytes).
- Project-package census (--project-prefix, else auto-detected candidate packages).
- LeakCanary KeyedWeakReference extraction + shortest GC-root path (leak trace).
- Destroyed Activity/Service/Fragment instances reachable from GC roots.

Handles every ART quirk met in practice: big-endian, 0xFE HEAP_DUMP_INFO,
JNI_MONITOR root = tag + 4B id, instance layout = own fields first then
ancestors, WeakReference.referent is NOT a strong edge, shadow$_klass_ fields,
object id != file offset, dot-form class names.
"""
import argparse
import mmap
import os
import struct
import sys
import time
from array import array
from collections import defaultdict, deque

_ap = argparse.ArgumentParser(add_help=False)
_ap.add_argument("hprof")
_ap.add_argument("out", nargs="?")
_ap.add_argument("--project-prefix", default="")
_ap.add_argument("--quiet", action="store_true")
ARGS, _ = _ap.parse_known_args()

T0 = time.time()
HPROF = ARGS.hprof
OUT = ARGS.out or (os.path.splitext(HPROF)[0] + ".report.txt")
QUIET = ARGS.quiet

def log(m):
    if not QUIET:
        print("[%6.1fs] %s" % (time.time() - T0, m), flush=True)

f = open(HPROF, "rb")
mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
N = len(mm)
z = mm.find(b"\x00")
VERSION = mm[:z].decode("ascii", "replace")
IS = struct.unpack_from(">I", mm, z + 1)[0]          # id size
IF = ">I" if IS == 4 else ">Q"
log("hprof: version=%s idSize=%d size=%d" % (VERSION, IS, N))

VSIZE = {2: IS, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
ETYPE_NAME = {2: "ref", 4: "boolean", 5: "char", 6: "float", 7: "double",
              8: "byte", 9: "short", 10: "int", 11: "long"}

# heap sub-record root sizes (bytes after tag byte); id fields first
ROOT_INFO = {0xFF: IS, 0x01: IS + 4, 0x02: IS + 8, 0x03: IS + 8, 0x04: IS + 4,
             0x05: IS, 0x06: IS + 4, 0x07: IS, 0x08: IS + 8,
             0x89: IS, 0x8a: IS, 0x8b: IS, 0x8c: IS, 0x8d: IS}
ROOT_NAME = {0xFF: "UNKNOWN", 0x01: "JNI_GLOBAL", 0x02: "JNI_LOCAL", 0x03: "JAVA_FRAME",
             0x04: "NATIVE_STACK", 0x05: "STICKY_CLASS", 0x06: "THREAD_BLOCK",
             0x07: "MONITOR_USED", 0x08: "THREAD_OBJECT", 0x89: "INTERNED_STRING",
             0x8a: "FINALIZING", 0x8b: "DEBUGGER", 0x8c: "REFERENCE_CLEANUP",
             0x8d: "JNI_MONITOR"}
VALID_SUBTAGS = set(ROOT_INFO) | {0x20, 0x21, 0x22, 0x23, 0xFE}

strings = {}
classes = {}                      # classObjId -> dict
roots = []                        # (kindName, objId)
prim_arrays = {}                  # arrId -> (off, etype, count)
obj_arrays = {}                   # arrId -> (off, elemClsId, count)
string_meta = []                  # (oid, off, nb) of java.lang.String instances
kwr_cids = set()
pending_meta = []                 # (oid, cid, off, nb) before LOAD_CLASS seen
inst_arrays = {}                  # classId -> (objIds, offs, nbytes) arrays
n_inst = n_objarr = n_primarr = n_classdump = 0
unknown_subtags = {}
skipped_segments = 0
skip_reasons = {}

def get_meta(cid):
    a = inst_arrays.get(cid)
    if a is None:
        a = inst_arrays[cid] = (array("I"), array("I"), array("I"))
    return a

def parse_root(p, t):
    oid = struct.unpack_from(IF, mm, p)[0]
    return ROOT_NAME[t], oid

def parse_class_dump(p):
    """Parse CLASS_DUMP starting after tag byte; returns end offset."""
    cid = struct.unpack_from(IF, mm, p)[0]
    c = classes.get(cid)
    if c is None:
        c = classes[cid] = {"cid": cid, "nameId": 0, "name": "<?>"}
    o = p + IS + 4                       # skip cid + stack trace serial
    sup = struct.unpack_from(IF, mm, o)[0]
    clo = struct.unpack_from(IF, mm, o + IS)[0]
    o += IS * 6                          # super, classloader, signers, protDomain, r1, r2
    c["super"] = sup
    c["classloader"] = clo
    o += 4                               # instance size
    nConst = struct.unpack_from(">H", mm, o)[0]; o += 2
    for _ in range(nConst):
        t = mm[o + 2]; o += 3 + VSIZE[t]
    nStat = struct.unpack_from(">H", mm, o)[0]; o += 2
    stats = []
    for _ in range(nStat):
        nid = struct.unpack_from(IF, mm, o)[0]; o += IS
        t = mm[o]; o += 1
        v = read_prim(t, o); o += VSIZE[t]
        stats.append((strings.get(nid, "?"), t, v))
    c["statics"] = stats
    nF = struct.unpack_from(">H", mm, o)[0]; o += 2
    flds = []
    for _ in range(nF):
        nid = struct.unpack_from(IF, mm, o)[0]; o += IS
        t = mm[o]; o += 1
        flds.append((strings.get(nid, "?"), t))
    c["fields"] = flds
    return o

def read_prim(t, o):
    if t == 2: return struct.unpack_from(IF, mm, o)[0]
    if t == 4: return mm[o]
    if t == 5: return struct.unpack_from(">H", mm, o)[0]
    if t == 6: return struct.unpack_from(">f", mm, o)[0]
    if t == 7: return struct.unpack_from(">d", mm, o)[0]
    if t == 8: return mm[o] - 256 if mm[o] > 127 else mm[o]
    if t == 9: return struct.unpack_from(">h", mm, o)[0]
    if t == 10: return struct.unpack_from(">i", mm, o)[0]
    if t == 11: return struct.unpack_from(">q", mm, o)[0]
    return 0

# ---------------- phase 1: parse ----------------
pos = z + 1 + 4 + 8
rec_count = 0
while pos + 9 <= N:
    tag = mm[pos]
    ts, ln = struct.unpack_from(">II", mm, pos + 1)
    body = pos + 9
    bend = body + ln
    if bend > N:
        log("!! truncated top-level record tag=0x%02x at %d (len %d > remaining %d)"
            % (tag, pos, ln, N - body))
        break
    if tag == 0x01:  # STRING
        sid = struct.unpack_from(IF, mm, body)[0]
        strings[sid] = mm[body + IS:bend].decode("utf-8", "replace")
    elif tag == 0x02:  # LOAD_CLASS: u4 serial, IS cid, u4 stack, IS nameId
        cid = struct.unpack_from(IF, mm, body + 4)[0]
        nameId = struct.unpack_from(IF, mm, body + 4 + IS + 4)[0]
        c = classes.get(cid)
        if c is None:
            c = classes[cid] = {"cid": cid, "nameId": nameId, "name": strings.get(nameId, "<?>)")}
        else:
            c.setdefault("cid", cid)
            c["nameId"] = nameId
            c["name"] = strings.get(nameId, "<?>)")
    elif tag in (0x0C, 0x1C):  # HEAP_DUMP / SEGMENT
        p = body
        seg_ok = True
        while p < bend:
            t = mm[p]
            try:
                if t == 0x21:  # INSTANCE_DUMP
                    oid = struct.unpack_from(IF, mm, p + 1)[0]
                    cid = struct.unpack_from(IF, mm, p + 1 + IS + 4)[0]
                    nb = struct.unpack_from(">I", mm, p + 1 + IS + 4 + IS)[0]
                    doff = p + 1 + IS + 4 + IS + 4
                    n_inst += 1
                    if cid in classes:
                        a = get_meta(cid)
                        a[0].append(oid); a[1].append(doff); a[2].append(nb)
                        nm = classes[cid]["name"]
                        if nm.endswith("KeyedWeakReference"):
                            kwr_cids.add(cid)
                        elif nm == "java.lang.String":
                            string_meta.append((oid, doff, nb))
                    else:
                        pending_meta.append((oid, cid, doff, nb))
                    p = doff + nb
                elif t == 0x22:  # OBJECT ARRAY
                    aid = struct.unpack_from(IF, mm, p + 1)[0]
                    cnt = struct.unpack_from(">I", mm, p + 1 + IS + 4)[0]
                    ecls = struct.unpack_from(IF, mm, p + 1 + IS + 8)[0]
                    h = IS + 4 + 4 + IS
                    obj_arrays[aid] = (p + 1 + h, ecls, cnt)
                    n_objarr += 1
                    p = p + 1 + h + cnt * IS
                elif t == 0x23:  # PRIM ARRAY
                    aid = struct.unpack_from(IF, mm, p + 1)[0]
                    cnt = struct.unpack_from(">I", mm, p + 1 + IS + 4)[0]
                    et = mm[p + 1 + IS + 8]
                    h = IS + 4 + 4 + 1
                    prim_arrays[aid] = (p + 1 + h, et, cnt)
                    n_primarr += 1
                    p = p + 1 + h + cnt * VSIZE[et]
                elif t == 0x20:  # CLASS_DUMP
                    n_classdump += 1
                    p = parse_class_dump(p + 1)
                elif t == 0xFE:  # HEAP_DUMP_INFO: u4 heap + id heapNameStringId
                    p = p + 1 + 4 + IS
                elif t in ROOT_INFO:
                    kind, oid = parse_root(p + 1, t)
                    roots.append((kind, oid))
                    np_ = p + 1 + ROOT_INFO[t]
                    if np_ < bend and mm[np_] not in VALID_SUBTAGS:
                        raise ValueError("root 0x%02x size assumption broken (next tag 0x%02x)" % (t, mm[np_]))
                    p = np_
                else:
                    unknown_subtags[t] = unknown_subtags.get(t, 0) + 1
                    raise KeyError("unknown heap sub-tag 0x%02x at %d" % (t, p))
                if p > bend:
                    raise ValueError("overrun")
            except Exception as e:
                skipped_segments += 1
                key = "%s" % e
                skip_reasons[key] = skip_reasons.get(key, 0) + 1
                if len(skip_reasons) <= 4:
                    log("!! heap segment skipped at %d (tag 0x%02x): %s" % (p, t, e))
                p = bend
                seg_ok = False
                break
    rec_count += 1
    pos = bend

for cid, c in classes.items():
    if "name" not in c or c["name"].startswith("<?>"):
        c["name"] = strings.get(c.get("nameId", 0), c.get("name", "<?>"))

log("parse done: records=%d classes=%d instances=%d objArr=%d primArr=%d roots=%d unknownTags=%s skipped=%d"
    % (rec_count, len(classes), n_inst, n_objarr, n_primarr, len(roots), unknown_subtags or "-", skipped_segments))
if skip_reasons:
    for k, v in sorted(skip_reasons.items(), key=lambda kv: -kv[1])[:6]:
        log("   skip x%d: %s" % (v, k[:110]))

for oid, cid, doff, nb in pending_meta:
    if cid in classes:
        a = get_meta(cid)
        a[0].append(oid); a[1].append(doff); a[2].append(nb)

# ---------------- layout resolution ----------------
def merged_fields(c):
    chain, x, seen = [], c, set()
    while x is not None and x["cid"] not in seen:
        seen.add(x["cid"]); chain.append(x)
        x = classes.get(x.get("super", 0))
    allf = []
    for k in chain:
        allf.extend(k.get("fields", []))
    out, o = [], 0
    for (n, t) in allf:
        out.append((n, t, o)); o += VSIZE[t]
    return out, out

layout_cache = {}
def layout(cid):
    """Instance data layout: own fields first (class-dump order), then each
    ancestor's fields appended (child -> parent). Empirically validated."""
    r = layout_cache.get(cid)
    if r is not None: return r
    c = classes.get(cid)
    if c is None: return ("chain", [])
    out, o = [], 0
    x, seen = c, set()
    while x is not None and x["cid"] not in seen:
        seen.add(x["cid"])
        for (n, t) in x.get("fields", []):
            out.append((n, t, o)); o += VSIZE[t]
        x = classes.get(x.get("super", 0))
    layout_cache[cid] = ("chain", out)
    return layout_cache[cid]

def field_off(lf, fname):
    for (n, t, o) in reversed(lf):
        if n == fname: return (t, o)
    return None

# java.lang.String values
string_vals = {}
string_cid = next((cid for cid, c in classes.items() if c["name"] == "java.lang.String"), 0)
if string_cid:
    _, slf = layout(string_cid)
    fv = field_off(slf, "value")
    fc = field_off(slf, "coder")
    for oid, off, nb in string_meta:
        if not fv or fv[0] != 2: break
        val = struct.unpack_from(IF, mm, off + fv[1])[0]
        coder = None
        if fc and fc[0] in (8, 10):
            coder = mm[off + fc[1]] if fc[0] == 8 else struct.unpack_from(">i", mm, off + fc[1])[0]
        if val: string_vals[oid] = (val, coder)
string_meta = None  # free

def decode_string(oid, depth=0):
    if not oid or depth > 2: return None
    sv = string_vals.get(oid)
    if sv is None: return None
    val, coder = sv
    pa = prim_arrays.get(val)
    if pa is None: return None
    off, et, cnt = pa
    if et == 8:
        data = mm[off:off + cnt]
        try:
            return data.decode("utf-16-le" if coder == 1 else "latin-1", "replace")
        except Exception:
            return data.decode("latin-1", "replace")
    if et == 5:
        data = mm[off:off + cnt * 2]
        for enc in ("utf-16-le", "utf-16-be"):
            try:
                s = data.decode(enc)
                if s.isprintable(): return s
            except Exception: pass
        return data.decode("utf-16-le", "replace")
    return None

# ---------------- census ----------------
pkg_stats = defaultdict(lambda: [0, 0])
cls_stats = []
for cid, a in inst_arrays.items():
    nm = classes[cid]["name"]
    cnt = len(a[0]); byt = sum(a[2])
    cls_stats.append((nm, cnt, byt))
    pkg = nm.rsplit(".", 1)[0] if "." in nm else "(default)"
    pkg_stats[pkg][0] += cnt; pkg_stats[pkg][1] += byt

total_shallow = sum(b for _, _, b in cls_stats) + \
    sum(cnt * VSIZE[et] for _, et, cnt in prim_arrays.values()) + \
    sum(cnt * IS for _, _, cnt in obj_arrays.values())

obj_class = {}
for cid, a in inst_arrays.items():
    for oid in a[0]: obj_class[oid] = cid

# project package prefixes: explicit arg wins, else auto-detect
KNOWN_NONPROJECT = (
    "android", "androidx", "kotlin", "kotlinx", "java", "javax", "dalvik",
    "libcore", "sun", "com.android", "com.google", "com.sun", "com.squareup",
    "com.facebook", "com.bumptech", "com.baidu", "com.alibaba", "com.huawei",
    "com.hihonor", "me.zhanghai", "org.apache", "org.greenrobot", "org.chromium",
    "org.json", "org.xml", "org.w3c", "org.jetbrains", "org.intellij",
    "io.reactivex", "android.support", "dev.rikka", "rikka", "top.yukonga",
    "com.belerweb", "com.k2fsa", "org.opencv", "org.pngquant", "com.benjaminwan",
    "net.dongliu", "com.mcal", "com.huaban", "com.wdullaer", "com.jaredrummler",
    "com.afollestad", "jackpal", "org.mozilla", "pxb", "ezy", "zhao", "WV",
    "okhttp3", "okio", "jdk.internal", "com.stericson", "hihonor", "com.hihonor",
    "org.ccil", "com.github", "org.slf4j", "com.hankcs",
)
if ARGS.project_prefix:
    PROJ = tuple(p.strip().rstrip(".") + "." for p in ARGS.project_prefix.split(",") if p.strip())
    proj_source = "user-specified"
else:
    cand = set()
    for pkg, (cnt, byt) in pkg_stats.items():
        if cnt < 3: continue
        root = ".".join(pkg.split(".")[:3])
        if any(root == k or root.startswith(k + ".") or k.startswith(root) for k in KNOWN_NONPROJECT):
            continue
        cand.add(root)
    PROJ = tuple(sorted(c + "." for c in cand))
    proj_source = "auto-detected"

# ---------------- adjacency + BFS ----------------
fnidx = {}
fnames = []
def fname_idx(n):
    i = fnidx.get(n)
    if i is None:
        if len(fnames) >= 65000: n = "?"
        i = fnidx.get(n)
        if i is None:
            i = fnidx[n] = len(fnames); fnames.append(n)
    return i

adj_dst = defaultdict(lambda: array("I"))
adj_fn = defaultdict(lambda: array("H"))

log("building reference graph...")
SHADOW_FIELDS = {"shadow$_klass_", "shadow$_monitor_"}

def _chain_has_ref(cid):
    x, seen = classes.get(cid), set()
    while x is not None and x["cid"] not in seen:
        seen.add(x["cid"])
        if x["name"] == "java.lang.ref.Reference": return True
        x = classes.get(x.get("super", 0))
    return False

ref_subclasses = {cid for cid in classes if _chain_has_ref(cid)}

for cid, a in inst_arrays.items():
    _, lf = layout(cid)
    objf = [(o, fname_idx(n)) for (n, t, o) in lf
            if t == 2 and n not in SHADOW_FIELDS
            and not (n == "referent" and cid in ref_subclasses)]
    if not objf: continue
    objs, offs_a, _ = a
    L = len(objs)
    for i in range(L):
        doff = offs_a[i]; oid = objs[i]
        dst = adj_dst[oid]; fn = adj_fn[oid]
        for (o, fi) in objf:
            r = struct.unpack_from(IF, mm, doff + o)[0]
            if r:
                dst.append(r); fn.append(fi)

for aid, (off, ecls, cnt) in obj_arrays.items():
    take = min(cnt, 32768)
    dst = adj_dst[aid]; fn = adj_fn[aid]
    fi = fname_idx("[i]")
    for i in range(take):
        r = struct.unpack_from(IF, mm, off + i * IS)[0]
        if r: dst.append(r); fn.append(fi)

for cid, c in classes.items():
    for (n, t, v) in c.get("statics", []):
        if t == 2 and v:
            adj_dst[cid].append(v); adj_fn[cid].append(fname_idx("static:" + n))
    cl = c.get("classloader", 0)
    if cl and cl in obj_class:
        adj_dst[cl].append(cid); adj_fn[cl].append(fname_idx("<classloader>"))

root_kind = {}
for kind, oid in roots:
    root_kind.setdefault(oid, kind)

log("BFS from %d gc roots..." % len(root_kind))
visited = set()
parent = {}
dq = deque()
for oid in root_kind:
    if oid and oid not in visited:
        visited.add(oid); parent[oid] = None; dq.append(oid)
while dq:
    src = dq.popleft()
    dsts = adj_dst.get(src)
    if not dsts: continue
    fns = adj_fn[src]
    for i in range(len(dsts)):
        d = dsts[i]
        if d not in visited:
            visited.add(d)
            parent[d] = (src << 16) | fns[i]
            dq.append(d)
n_nodes = len(obj_class) + len(classes) + len(obj_arrays) + len(prim_arrays)
log("reachable: %d / ~%d nodes" % (len(visited), n_nodes))

# ---------------- helpers ----------------
def class_of(oid):
    if oid in classes: return "class " + classes[oid]["name"]
    pa = prim_arrays.get(oid)
    if pa: return "%s[%d]" % (ETYPE_NAME.get(pa[1], "?"), pa[2])
    oa = obj_arrays.get(oid)
    if oa:
        cn = classes.get(oa[1], {}).get("name", "?")
        return "%s[%d]" % (cn, oa[2])
    cid = obj_class.get(oid)
    if cid: return classes[cid]["name"]
    return "obj@%08x" % (oid & 0xffffffff)

def super_chain_names(cid):
    names, x, seen = [], classes.get(cid), set()
    while x is not None and x["cid"] not in seen:
        seen.add(x["cid"]); names.append(x["name"])
        x = classes.get(x.get("super", 0))
    return names

def path_to(oid, max_len=50):
    chain = []
    cur = oid
    while cur is not None and len(chain) <= max_len:
        p = parent.get(cur)
        if p is None: break
        chain.append((cur, p))
        cur = p >> 16
    chain.reverse()
    lines = ["      GC Root: %s (%s)" % (root_kind.get(chain[0][1] >> 16, "?"),
                                          class_of(chain[0][1] >> 16))]
    for node, pv in chain:
        src = pv >> 16; fi = pv & 0xFFFF
        lines.append("        %s" % class_of(src))
        lines.append("          .%s \u2193" % fnames[fi])
    lines.append("      \u2605 LEAKED: %s" % class_of(oid))
    return "\n".join(lines)

def read_bool_at(cid, i, fname):
    a = inst_arrays[cid]
    _, lf = layout(cid)
    fo = field_off(lf, fname)
    if fo and fo[0] == 4:
        return mm[a[1][i] + fo[1]] != 0
    return None

# ---------------- report ----------------
rep = open(OUT, "w", encoding="utf-8")
def W(s=""):
    rep.write(s + "\n")

rk = defaultdict(int)
for kk, _ in roots: rk[kk] += 1

W("=" * 78)
W("HPROF \u5206\u6790\u62a5\u544a: %s" % os.path.basename(HPROF))
W("=" * 78)
W("\u6587\u4ef6: %d bytes | hprof \u7248\u672c: %s | idSize: %d" % (N, VERSION, IS))
W("classes=%d instances=%d objArrays=%d primArrays=%d GCroots=%d" %
  (len(classes), n_inst, n_objarr, n_primarr, len(roots)))
W("GC root \u5206\u5e03: %s" % dict(rk))
W("\u672a\u77e5 heap \u5b50\u6807\u7b7e: %s | \u8df3\u8fc7 segment: %d" % (unknown_subtags or "\u65e0", skipped_segments))
W("\u53ef\u8fbe\u5bf9\u8c61: %d / ~%d" % (len(visited), n_nodes))
W("\u603b\u6d45\u5c42\u5927\u5c0f\u7ea6: %.1f MB" % (total_shallow / 1048576.0))
W("\u9879\u76ee\u5305\u540d\u8fc7\u6ee4 (%s): %s" % (proj_source, ", ".join(PROJ) or "\u65e0"))

W("")
W("=" * 78)
W("\u4e00\u3001\u6309\u5305\u540d\u7684\u5b9e\u4f8b\u6570\u91cf Top 60")
W("=" * 78)
for pkg, (cnt, byt) in sorted(pkg_stats.items(), key=lambda kv: -kv[1][0])[:60]:
    W("  %9d inst  %9.2f MB  %s" % (cnt, byt / 1048576.0, pkg))

W("")
W("=" * 78)
W("\u4e8c\u3001\u6309\u7c7b\u7684\u5b9e\u4f8b\u6570\u91cf Top 80")
W("=" * 78)
for nm, cnt, byt in sorted(cls_stats, key=lambda x: -x[1])[:80]:
    W("  %9d inst  %9.2f MB  %s" % (cnt, byt / 1048576.0, nm))

W("")
W("=" * 78)
W("\u4e09\u3001\u9879\u76ee\u81ea\u6709\u7c7b\u5168\u91cf\u6e05\u5355 (\u542b\u6240\u6709\u6a21\u5757)")
W("=" * 78)
proj_rows = [(nm, c, b) for (nm, c, b) in cls_stats if nm.startswith(PROJ)]
proj_rows.sort()
W("\u5171 %d \u4e2a\u7c7b, \u5408\u8ba1 %d \u5b9e\u4f8b" % (len(proj_rows), sum(c for _, c, _ in proj_rows)))
for nm, c, b in proj_rows:
    flag = ""
    sn = nm.rsplit(".", 1)[-1].split("$")[0]
    if sn.endswith(("Activity", "Service", "Fragment", "ViewModel", "Dialog", "Application", "Provider", "Receiver")):
        flag = "   <== " + sn
    W("  %7d inst  %8.1f KB  %s%s" % (c, b / 1024.0, nm, flag))

W("")
W("=" * 78)
W("\u56db\u3001Activity / Service / Fragment \u5b9e\u4f8b\u4e0e\u9500\u6bc1\u72b6\u6001 (mDestroyed)")
W("=" * 78)
destroyed_reachable = []
for base, label in (("android.app.Activity", "Activity"),
                    ("android.app.Service", "Service"),
                    ("androidx.fragment.app.Fragment", "Fragment")):
    rows = []
    for cid, c in classes.items():
        if base not in super_chain_names(cid): continue
        a = inst_arrays.get(cid)
        if not a or not len(a[0]): continue
        cnt = len(a[0])
        des = [a[0][i] for i in range(min(cnt, 2000)) if read_bool_at(cid, i, "mDestroyed")]
        rows.append((c["name"], cnt, des))
    if rows:
        W("  -- %s --" % label)
        rows.sort(key=lambda r: -r[1])
        for nm, cnt, des in rows:
            extra = ""
            if des:
                vis = [x for x in des if x in visited]
                extra = "   mDestroyed=True: %d \u4e2a, \u5176\u4e2d %d \u4e2a\u4ecd\u53ef\u8fbe" % (len(des), len(vis))
                destroyed_reachable.extend(vis)
            W("    %5d inst  %s%s" % (cnt, nm, extra))
for b in ("android.app.Dialog", "android.app.Application", "android.content.ContentProvider",
          "android.content.BroadcastReceiver", "android.os.Handler"):
    rows = [(c["name"], len(inst_arrays[cid][0])) for cid, c in classes.items()
            if b in super_chain_names(cid) and cid in inst_arrays and len(inst_arrays[cid][0])]
    if rows:
        W("  -- %s (top5) --" % b)
        for nm, cnt in sorted(rows, key=lambda r: -r[1])[:5]:
            W("    %5d inst  %s" % (cnt, nm))

W("")
W("=" * 78)
W("\u4e94\u3001LeakCanary KeyedWeakReference \u76d1\u89c6\u5bf9\u8c61\u4e0e\u6cc4\u6f0f\u94fe")
W("=" * 78)
n_watch = n_leak = 0
printed_paths = 0
for kcid in sorted(kwr_cids):
    a = inst_arrays.get(kcid)
    if not a: continue
    _, lf = layout(kcid)
    fr, fk, fnm, fcl = field_off(lf, "referent"), field_off(lf, "key"), field_off(lf, "name"), field_off(lf, "className")
    for i in range(len(a[0])):
        off = a[1][i]; n_watch += 1
        def rd(fo):
            if fo and fo[0] == 2:
                return struct.unpack_from(IF, mm, off + fo[1])[0]
            return 0
        ref = rd(fr)
        key = decode_string(rd(fk)) or "?"
        name = decode_string(rd(fnm)) or ""
        clsname = decode_string(rd(fcl)) or "?"
        W("")
        W("  \u76d1\u89c6\u5bf9\u8c61: %s   (key=%s, name=%s)" % (clsname, key, name))
        if not ref:
            W("    referent \u5df2\u6e05\u7a7a (weak ref cleared)")
            continue
        if ref not in visited:
            W("    referent \u4e0d\u53ef\u8fbe \u2014 \u65e0\u6cc4\u6f0f")
            continue
        n_leak += 1
        tcid = obj_class.get(ref)
        tgt_cls = classes.get(tcid, {}).get("name", "?") if tcid else "?"
        W("    \u72b6\u6001: \u4ecd\u53ef\u8fbe\u81ea GC Root \u2014 \u6cc4\u6f0f! %s" % tgt_cls)
        if printed_paths < 12:
            W(path_to(ref))
            printed_paths += 1
W("")
W("\u5171\u76d1\u89c6 %d \u4e2a\u5bf9\u8c61, \u5176\u4e2d %d \u4e2a\u4ecd\u53ef\u8fbe (\u6cc4\u6f0f\u5019\u9009)" % (n_watch, n_leak))

W("")
W("=" * 78)
W("\u516d\u3001\u5df2\u9500\u6bc1\u4f46\u53ef\u8fbe\u7684\u7ec4\u4ef6 (\u786c\u8bc1\u636e\u6cc4\u6f0f) \u8def\u5f84")
W("=" * 78)
shown = 0
seen_paths = set()
for oid in destroyed_reachable:
    tcid = obj_class.get(oid)
    nm = classes.get(tcid, {}).get("name", "?") if tcid else "?"
    p = path_to(oid)
    if p in seen_paths: continue
    seen_paths.add(p)
    W("")
    W("  %s (mDestroyed=True, \u4ecd\u88ab\u6301\u6709)" % nm)
    W(p)
    shown += 1
    if shown >= 10:
        W("  ... (\u5171 %d \u4e2a, \u4f59\u4e0b\u7701\u7565)" % len(destroyed_reachable))
        break
if not destroyed_reachable:
    W("  \u65e0")

rep.close()
log("report written: %s" % OUT)

if QUIET: sys.exit(0)
print()
print("==== TOP15 classes by instances ====")
for nm, cnt, byt in sorted(cls_stats, key=lambda x: -x[1])[:15]:
    print("  %8d inst  %8.2f MB  %s" % (cnt, byt / 1048576.0, nm))
print()
print("==== project classes (%s): %d types, %d instances ====" % (proj_source, len(proj_rows), sum(c for _, c, _ in proj_rows)))
for nm, c, b in sorted(proj_rows, key=lambda x: -x[1])[:25]:
    print("  %7d inst  %s" % (c, nm))
print()
print("==== KeyedWeakReference: %d watched, %d reachable ====" % (n_watch, n_leak))
print("==== destroyed-but-reachable components: %d ====" % len(destroyed_reachable))
print("report ->", OUT)
