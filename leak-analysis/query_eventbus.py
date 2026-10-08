#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Enumerate greenrobot EventBus subscriber tables from the heap dump (fixed id->offset)."""
import io, contextlib, struct, sys
from collections import Counter

which = sys.argv[1] if len(sys.argv) > 1 else "after"
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

def cname(oid):
    cid = obj_class.get(oid)
    return classes[cid]["name"] if cid else ("class " + classes[oid]["name"] if oid in classes else "?")

# per-class instance id -> data offset maps (built lazily, arrays small per class)
_id2off_cache = {}
def inst_off(oid):
    cid = obj_class.get(oid)
    if cid is None: return None
    m = _id2off_cache.get(cid)
    if m is None:
        a = inst_arrays[cid]
        m = _id2off_cache[cid] = dict(zip(a[0], a[1]))
    return m.get(oid)

def read_ref_at_instance(oid, field):
    cid = obj_class[oid]
    _, lf = layout(cid)
    fo = field_off(lf, field)
    if not fo or fo[0] != 2: return None
    off = inst_off(oid)
    if off is None: return None
    return struct.unpack_from(IF, mm, off + fo[1])[0]

eb_cid = next(cid for cid, c in classes.items() if c["name"] == "org.greenrobot.eventbus.EventBus")
st = dict((n, v) for n, t, v in classes[eb_cid].get("statics", []) if not n.startswith("$class"))
bus = st["defaultInstance"]
print("EventBus defaultInstance: 0x%x" % bus)

node_cls = next(cid for cid, c in classes.items() if c["name"] == "java.util.HashMap$Node")

for mapname in ("subscriptionsByEventType", "typesBySubscriber", "stickyEvents"):
    hmap = read_ref_at_instance(bus, mapname)
    if not hmap:
        print("  %s: null" % mapname); continue
    size = None
    cid = obj_class.get(hmap)
    if cid:
        _, hlf = layout(cid)
        sfo = field_off(hlf, "size")
        if sfo and sfo[0] == 10:
            size = struct.unpack_from(">i", mm, inst_off(hmap) + sfo[1])[0]
    tbl = read_ref_at_instance(hmap, "table")
    oa = g["obj_arrays"].get(tbl) if tbl else None
    print("  %s: size=%s table=%s" % (mapname, size, ("Node[%d]" % oa[2]) if oa else "n/a"))
    if not oa: continue
    toff, _, tcnt = oa
    keys = Counter()
    subs_sample = Counter()
    sub_cls = next((cid for cid, c in classes.items()
                    if c["name"] == "org.greenrobot.eventbus.Subscription"), 0)
    for i in range(tcnt):
        nid = struct.unpack_from(IF, mm, toff + i * IS)[0]
        if not nid or obj_class.get(nid) != node_cls: continue
        k = read_ref_at_instance(nid, "key")
        v = read_ref_at_instance(nid, "value")
        if not k: continue
        keys[cname(k)] += 1
        if mapname == "typesBySubscriber" and v:
            # value = List of event classes; just count
            pass
        if mapname == "subscriptionsByEventType" and sub_cls and v:
            # value = CopyOnWriteArrayList<Subscription> -> 'array' field -> Subscription.subscriber
            vc = obj_class.get(v)
            if vc:
                arr = read_ref_at_instance(v, "array")
                oarr = g["obj_arrays"].get(arr) if arr else None
                if oarr:
                    aoff, _, acnt = oarr
                    for j in range(min(acnt, 256)):
                        sid = struct.unpack_from(IF, mm, aoff + j * IS)[0]
                        if sid and obj_class.get(sid) == sub_cls:
                            s = read_ref_at_instance(sid, "subscriber")
                            if s: subs_sample[cname(s)] += 1
    for k, n in keys.most_common(15):
        print("     key: %4d x %s" % (n, k))
    if subs_sample:
        print("     subscribers (from Subscription objects):")
        for k, n in subs_sample.most_common(15):
            print("       %4d x %s" % (n, k))
