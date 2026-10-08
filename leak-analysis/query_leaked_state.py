#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Inspect lifecycle state of leaked ExplorerView / DrawerFragment instances."""
import io, contextlib, struct, sys

which = sys.argv[1] if len(sys.argv) > 1 else "after2"
path = r"G:\code\autojs\source\AutoJs6\leak-analysis\%s.hprof" % which
sys.argv = ["x", path, path + ".tmp.txt"]
buf = io.StringIO()
src = open(r"G:\code\autojs\source\AutoJs6\leak-analysis\hprof_analyzer.py", encoding="utf-8").read()
g = {"__name__": "analyzer"}
with contextlib.redirect_stdout(buf):
    exec(compile(src, "hprof_analyzer.py", "exec"), g)

mm = g["mm"]; IF = g["IF"]; IS = g["IS"]
classes = g["classes"]; inst_arrays = g["inst_arrays"]; layout = g["layout"]
obj_class = g["obj_class"]; field_off = g["field_off"]
_id2off = {}
def inst_off(oid):
    cid = obj_class.get(oid)
    if cid is None: return None
    m = _id2off.get(cid)
    if m is None:
        a = inst_arrays[cid]; m = _id2off[cid] = dict(zip(a[0], a[1]))
    return m.get(oid)

def rd_ref(oid, field):
    cid = obj_class.get(oid)
    if not cid: return None
    _, lf = layout(cid); fo = field_off(lf, field)
    if not fo or fo[0] != 2: return None
    o = inst_off(oid)
    return None if o is None else struct.unpack_from(IF, mm, o + fo[1])[0]

def rd_int(oid, field):
    cid = obj_class.get(oid)
    if not cid: return None
    _, lf = layout(cid); fo = field_off(lf, field)
    if not fo or fo[0] != 10: return None
    o = inst_off(oid)
    return None if o is None else struct.unpack_from(">i", mm, o + fo[1])[0]

def rd_bool(oid, field):
    cid = obj_class.get(oid)
    if not cid: return None
    _, lf = layout(cid); fo = field_off(lf, field)
    if not fo or fo[0] != 4: return None
    o = inst_off(oid)
    return None if o is None else mm[o + fo[1]]

def cname(oid):
    cid = obj_class.get(oid)
    return classes[cid]["name"] if cid else ("class " + classes[oid]["name"] if oid in classes else "?")

# ---- subscriber sets on defaultInstance bus ----
eb_cid = next(cid for cid, c in classes.items() if c["name"] == "org.greenrobot.eventbus.EventBus")
st = dict((n, v) for n, t, v in classes[eb_cid].get("statics", []) if not n.startswith("$class"))
bus = st["defaultInstance"]
types_map = rd_ref(bus, "typesBySubscriber")
node_cls = next(cid for cid, c in classes.items() if c["name"] == "java.util.HashMap$Node")
subscribers = set()
hm_off = inst_off(types_map)
_, hlf = layout(obj_class[types_map])
tbl = struct.unpack_from(IF, mm, hm_off + field_off(hlf, "table")[1])[0]
toff, _, tcnt = g["obj_arrays"][tbl]
for i in range(tcnt):
    nid = struct.unpack_from(IF, mm, toff + i * IS)[0]
    if not nid or obj_class.get(nid) != node_cls: continue
    k = rd_ref(nid, "key")
    if k: subscribers.add(k)
    # follow collision chains
    nx = rd_ref(nid, "next")
    seen = set()
    while nx and nx not in seen and obj_class.get(nx) == node_cls:
        seen.add(nx)
        k2 = rd_ref(nx, "key")
        if k2: subscribers.add(k2)
        nx = rd_ref(nx, "next")
print("typesBySubscriber subscribers (incl. chains): %d" % len(subscribers))
sub_names = {}
for s in subscribers:
    sub_names.setdefault(cname(s), []).append(s)
for k, v in sorted(sub_names.items(), key=lambda kv: -len(kv[1])):
    print("   %3d x %s" % (len(v), k))

ma_cid = next(cid for cid, c in classes.items() if c["name"] == "org.autojs.autojs.ui.main.MainActivity")
ma_ids = set(inst_arrays[ma_cid][0])
ma_off = inst_arrays[ma_cid][1]
des_fo = field_off(layout(ma_cid)[1], "mDestroyed")
ma_destroyed = set()
for i, oid in enumerate(inst_arrays[ma_cid][0]):
    if mm[ma_off[i] + des_fo[1]]: ma_destroyed.add(oid)
print("MainActivity instances=%d destroyed=%d" % (len(ma_ids), len(ma_destroyed)))

def ma_desc(oid):
    if oid in ma_destroyed: return "DESTROYED-MA"
    if oid in ma_ids: return "current-MA"
    return cname(oid)[:40] if oid else "null"

# ---- ExplorerView instances ----
print()
print("=== ExplorerView instances ===")
ev_cid = next(cid for cid, c in classes.items() if c["name"] == "org.autojs.autojs.ui.explorer.ExplorerView")
a = inst_arrays[ev_cid]
att_fo = field_off(layout(ev_cid)[1], "mAttachInfo")
par_fo = field_off(layout(ev_cid)[1], "mParent")
mex_fo = field_off(layout(ev_cid)[1], "mExplorer")
ctx_fo = field_off(layout(ev_cid)[1], "mContext")
for i in range(len(a[0])):
    oid = a[0][i]; off = a[1][i]
    att = struct.unpack_from(IF, mm, off + att_fo[1])[0] if att_fo and att_fo[0] == 2 else 0
    par = rd_ref(oid, "mParent")
    mex = rd_ref(oid, "mExplorer")
    ctx = rd_ref(oid, "mContext")
    print("  0x%-9x attached=%-5s parent=%-38s inBus=%-5s mExplorer=%s mContext=%s"
          % (oid, bool(att), (cname(par)[:38] if par else "null"), oid in subscribers,
             ("0x%x" % mex) if mex else "null", ma_desc(ctx)))

# ---- DrawerFragment instances ----
print()
print("=== DrawerFragment instances ===")
df_cid = next(cid for cid, c in classes.items() if c["name"] == "org.autojs.autojs.ui.main.drawer.DrawerFragment")
a = inst_arrays[df_cid]
st_fo = field_off(layout(df_cid)[1], "mState")
add_fo = field_off(layout(df_cid)[1], "mAdded")
host_fo = field_off(layout(df_cid)[1], "mHost")
who_fo = field_off(layout(df_cid)[1], "mWho")
if who_fo and who_fo[0] == 2:
    for i in range(len(a[0])):
        oid = a[0][i]
        state = rd_int(oid, "mState")
        added = rd_bool(oid, "mAdded")
        host = rd_ref(oid, "mHost")
        act = rd_ref(host, "mActivity") if host else 0
        print("  0x%-9x mState=%-3s mAdded=%-5s inBus=%-5s host=%s" %
              (oid, state, added, oid in subscribers, ma_desc(act)))
