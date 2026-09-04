#!/usr/bin/env python3
"""CTS Renderer API v3 compiler (stdlib only, deterministic)."""
import argparse,gzip,json,math,re,struct,sys,zlib
from pathlib import Path

MAGIC=b"CCRNDR03"; API=3; CV=1
TIMELINES={"absolute","relative"}; INTERP={"raw","hold","linear","smoothstep","cubic-in","cubic-out","cubic-in-out"}; EXTRA={"none","hold"}
RESOURCE_TYPES={"polygon","rect","ellipse","path","image","video","text","text-raster","source-text-raster","group","shadow","independent-shadow","shine","mask","material","filter","custom","outro-overlay","exact-outro-overlay"}
BLENDS={"normal","src-over","multiply","screen","add","overlay","darken","lighten","difference"}
FEATURES=[
"per-frame-polygon-vertices","full-2d-transforms","shine-geometry-tracks","arbitrary-masks","track-interpolation-modes","per-item-animation","exact-text-tracks","explicit-layer-order","independent-shadow-resources","dense-frame-data","frame-addressed-objects","property-level-selector-inheritance","deterministic-selector-precedence","zero-implicit-animation","generic-renderer-resources","group-transforms","resource-lifespans","selector-shared-behaviour","renderer-owned-geometry","renderer-owned-materials","blend-compositing-modes","per-frame-filter-tracks","exact-artwork-transforms","absolute-integer-frame-clock","single-scene-preview-export-contract","reference-resolution-fps-lock","frame-checkpoints","pixel-diff-audit-contract","selector-cascade-inspection","renderer-api-v3-scene-ir","video-frame-resources"]
FEATURE_FOR_TYPE={"text-raster":"source-baked-text-raster","source-text-raster":"source-baked-text-raster","independent-shadow":"independent-shadow-resource","outro-overlay":"exact-outro-overlay","exact-outro-overlay":"exact-outro-overlay"}
SEL=re.compile(r"^(?P<kind>[A-Za-z_][\w.-]*)\[(?P<body>.*)\]$"); COND=re.compile(r"^(?P<key>[A-Za-z_][\w.-]*)(?P<op>>=|<=|!=|=|>|<)(?P<val>.+)$")
UNSET=object()
class E(ValueError): pass

def die(c,m): raise E(f"{c}: {m}")
def integer(v,c):
    if isinstance(v,bool) or not isinstance(v,int): die(c,"expected integer")
    return v
def text(v,c):
    if not isinstance(v,str) or not v.strip(): die(c,"expected non-empty string")
    return v.strip()
def load(p):
    try: v=json.loads(Path(p).read_text("utf-8"))
    except Exception as e: raise E(f"{p}: invalid JSON: {e}")
    if not isinstance(v,dict): die(p,"root must be object")
    return v

def terminal(v): return isinstance(v,dict) and any(k in v for k in ("value","track","dense"))
def flatten(d,p=""):
    if not isinstance(d,dict): die(p or "properties","expected object")
    out={}
    for k,v in d.items():
        q=f"{p}.{k}" if p else k
        if isinstance(v,dict) and not terminal(v): out.update(flatten(v,q))
        else: out[q]=v
    return out

def lit(s):
    s=s.strip()
    if re.fullmatch(r"-?\d+",s): return int(s)
    if re.fullmatch(r"-?(\d+\.\d*|\d*\.\d+)",s): return float(s)
    if s.lower() in ("true","false"): return s.lower()=="true"
    return s

def selector(s):
    m=SEL.match(text(s,"selector"))
    if not m: die("selector",f"invalid '{s}'")
    kind=m.group("kind"); body=m.group("body").strip(); cs=[]; base=100
    if body not in ("","*"):
        for tok in [x.strip() for x in body.split(",") if x.strip()]:
            if tok.startswith("frame=") and ".." in tok:
                a,b=tok[6:].split("..",1); cs += [("frame",">=",int(a)),("frame","<=",int(b))]; base=max(base,320); continue
            m2=COND.match(tok)
            if not m2: die("selector",f"invalid condition '{tok}'")
            k,o,v=m2.group("key"),m2.group("op"),lit(m2.group("val")); cs.append((k,o,v))
            base=max(base,500 if k=="frame" and o=="=" else 360 if k in {"every","from","to"} else 280 if k=="frame" else 220)
    ev=next((v for k,o,v in cs if k=="every" and o=="="),None)
    if ev is not None and (not isinstance(ev,int) or ev<=0): die("selector","every must be positive integer")
    if ev is not None and not any(k=="from" for k,_,_ in cs): cs.append(("from","=",0))
    return {"raw":s,"kind":kind,"conditions":[list(x) for x in cs],"specificity":base+len(cs)}

def cmp(a,o,b):
    try: return {"=":a==b,"!=":a!=b,">=":a>=b,"<=":a<=b,">":a>b,"<":a<b}[o]
    except TypeError: return False

def matches(p,o):
    if o.get("kind")!=p["kind"]: return False
    cs=p["conditions"]; every=next((v for k,op,v in cs if k=="every" and op=="="),None); fr=next((v for k,op,v in cs if k=="from" and op=="="),0); to=next((v for k,op,v in cs if k=="to" and op=="="),None)
    if every is not None:
        f=o.get("frame")
        if not isinstance(f,int) or f<fr or (to is not None and f>to) or (f-fr)%every: return False
    for k,op,v in cs:
        if k in {"every","from","to"}: continue
        if k not in o or not cmp(o[k],op,v): return False
    return True

def norm_track(v,c,default="absolute"):
    if not terminal(v): return v
    if "value" in v and not ("track" in v or "dense" in v): return {"kind":"constant","value":v["value"]}
    tl=v.get("timeline",default); it=v.get("interpolation","raw"); ex=v.get("extrapolate","none")
    if tl not in TIMELINES: die(c,"bad timeline")
    if it not in INTERP: die(c,"bad interpolation")
    if ex not in EXTRA: die(c,"bad extrapolate")
    if "dense" in v:
        d=v["dense"]
        if isinstance(d,list): start=integer(v.get("start",0),c+".start"); vals=d
        elif isinstance(d,dict): start=integer(d.get("start",0),c+".dense.start"); vals=d.get("values")
        else: die(c+".dense","expected array/object")
        if not isinstance(vals,list) or not vals: die(c+".dense.values","expected non-empty array")
        return {"kind":"dense","timeline":tl,"interpolation":it,"extrapolate":ex,"start":start,"values":vals}
    ks=v.get("track")
    if not isinstance(ks,list) or not ks: die(c+".track","expected non-empty array")
    prev=None; out=[]
    for i,k in enumerate(ks):
        if not isinstance(k,list) or len(k)!=2: die(f"{c}.track[{i}]","expected [frame,value]")
        f=integer(k[0],f"{c}.track[{i}]")
        if prev is not None and f<=prev: die(c+".track","frames must strictly increase")
        prev=f; out.append([f,k[1]])
    return {"kind":"sparse","timeline":tl,"interpolation":it,"extrapolate":ex,"keys":out}

def props(v,c,default="absolute"):
    if v is None: return {}
    return {k:norm_track(x,f"{c}.{k}",default) for k,x in flatten(v).items()}

def compile_source(s):
    if s.get("api",3)!=3: die("api","must be 3")
    rid=text(s.get("id"),"id"); name=text(s.get("name",rid),"name")
    ca=s.get("canvas",{}); w=integer(ca.get("width",1920),"canvas.width"); h=integer(ca.get("height",1080),"canvas.height"); fps=integer(ca.get("fps",60),"canvas.fps")
    if not (1<=w<=16384 and 1<=h<=16384 and 1<=fps<=240): die("canvas","out of range")
    tl=s.get("timeline",{}); frames=integer(tl.get("frames",0),"timeline.frames")
    if frames<=0: die("timeline.frames","must be > 0")
    if tl.get("clock","absolute")!="absolute" or tl.get("implicitAnimation",False) is not False: die("timeline","v3 requires absolute clock and implicitAnimation=false")
    declared=[]
    for key in ("features","requiredFeatures"):
        values=s.get(key,[])
        if not isinstance(values,list) or not all(isinstance(x,str) and x.strip() for x in values): die(key,"expected non-empty string array")
        declared.extend(x.strip() for x in values)
    features=list(dict.fromkeys(FEATURES+declared))
    rin=s.get("resources",{})
    if not isinstance(rin,dict): die("resources","expected object")
    rs={}
    for n,r in rin.items():
        if not isinstance(r,dict): die(f"resources.{n}","expected object")
        t=text(r.get("type"),f"resources.{n}.type")
        if t not in RESOURCE_TYPES: die(f"resources.{n}.type",f"unsupported {t}")
        required=FEATURE_FOR_TYPE.get(t)
        if required and required not in features: die(f"resources.{n}.type",f"{t} requires feature {required}")
        rr=dict(r)
        if t=="polygon":
            pts=r.get("points")
            if not isinstance(pts,list) or len(pts)<3: die(f"resources.{n}.points","polygon needs >=3 points")
        if t=="group":
            ch=r.get("children",[])
            if not isinstance(ch,list) or not all(isinstance(x,str) for x in ch): die(f"resources.{n}.children","must be string array")
        if "properties" in rr: rr["properties"]=props(rr["properties"],f"resources.{n}.properties")
        rs[n]=rr
    for n,r in rs.items():
        if r["type"]=="group":
            for ch in r.get("children",[]):
                if ch not in rs: die(f"resources.{n}",f"unknown child {ch}")
    oi=s.get("objects",[])
    if not isinstance(oi,list): die("objects","expected array")
    objs=[]; ids=set()
    for i,o in enumerate(oi):
        if not isinstance(o,dict): die(f"objects[{i}]","expected object")
        oid=text(o.get("id"),f"objects[{i}].id")
        if oid in ids: die("objects",f"duplicate id {oid}")
        ids.add(oid); kind=text(o.get("kind"),f"objects[{i}].kind"); fr=integer(o.get("frame"),f"objects[{i}].frame")
        if not 0<=fr<frames: die(f"objects[{i}].frame","outside timeline")
        res=o.get("resource")
        if res is not None and res not in rs: die(f"objects[{i}].resource",f"unknown {res}")
        life=o.get("lifespan",{"start":fr,"end":frames-1}); a=integer(life.get("start",fr),f"objects[{i}].lifespan.start"); b=integer(life.get("end",frames-1),f"objects[{i}].lifespan.end")
        if not 0<=a<=b<frames: die(f"objects[{i}].lifespan","invalid")
        x={k:v for k,v in o.items() if k not in {"properties","lifespan"}}; x.update({"id":oid,"kind":kind,"frame":fr,"lifespan":{"start":a,"end":b},"properties":props(o.get("properties",{}),f"objects[{i}].properties",o.get("timeline","relative"))}); objs.append(x)
    si=s.get("selectors",[])
    if not isinstance(si,list): die("selectors","expected array")
    sels=[]
    for i,x in enumerate(si):
        if not isinstance(x,dict): die(f"selectors[{i}]","expected object")
        p=selector(x.get("select")); d=x.get("timeline","relative")
        if d not in TIMELINES: die(f"selectors[{i}].timeline","bad timeline")
        sels.append({"select":p["raw"],"parsed":p,"timeline":d,"properties":props(x.get("properties",{}),f"selectors[{i}].properties",d),"sourceOrder":i})
    layers=s.get("layers",[]); checkpoints=s.get("checkpoints",[])
    if not isinstance(layers,list) or not isinstance(checkpoints,list): die("layers/checkpoints","must be arrays")
    out={"format":"CTS Renderer API v3 Scene IR","api":3,"id":rid,"name":name,"author":s.get("author",""),"canvas":{"width":w,"height":h,"fps":fps,"lock":bool(ca.get("lock",True))},"timeline":{"frames":frames,"clock":"absolute","implicitAnimation":False},"features":features,"resources":rs,"objects":objs,"selectors":sels,"layers":layers,"checkpoints":checkpoints,"audit":s.get("audit",{}),"runtimeContract":{"integerFrameClock":True,"noImplicitAnimation":True,"selectorMerge":"property-level","selectorTieBreak":"specificity-then-source-order","previewExportSceneEvaluator":"same","undeclaredTrackBehavior":"static/no-op"}}
    return out

def psel(s): return {"raw":s["parsed"]["raw"],"kind":s["parsed"]["kind"],"conditions":s["parsed"]["conditions"],"specificity":s["parsed"]["specificity"]}
def resolve(scene,o):
    win={}
    r=scene.get("resources",{}).get(o.get("resource"),{})
    for k,v in r.get("properties",{}).items(): win[k]=(0,-1,v)
    for s in scene.get("selectors",[]):
        if matches(psel(s),o):
            for k,v in s["properties"].items():
                c=(s["parsed"]["specificity"],s["sourceOrder"],v)
                if k not in win or c[:2]>=win[k][:2]: win[k]=c
    for k,v in o.get("properties",{}).items(): win[k]=(1000,10**9,v)
    return {k:x[2] for k,x in win.items()}

def evalv(v,g,a):
    if not isinstance(v,dict) or v.get("kind") not in {"constant","dense","sparse"}: return v
    if v["kind"]=="constant": return v["value"]
    f=g if v.get("timeline")=="absolute" else g-a; ex=v.get("extrapolate","none"); it=v.get("interpolation","raw")
    if v["kind"]=="dense":
        i=f-v["start"]; vals=v["values"]
        if 0<=i<len(vals): return vals[i]
        return vals[0 if i<0 else -1] if ex=="hold" else UNSET
    ks=v["keys"]
    for t,x in ks:
        if f==t:return x
    if f<ks[0][0]: return ks[0][1] if ex=="hold" else UNSET
    if f>ks[-1][0]: return ks[-1][1] if ex=="hold" else UNSET
    if it=="raw":return UNSET
    l=max(x for x in ks if x[0]<f); r=min(x for x in ks if x[0]>f)
    if it=="hold":return l[1]
    if not isinstance(l[1],(int,float)) or not isinstance(r[1],(int,float)): die("track","numeric interpolation required")
    p=(f-l[0])/(r[0]-l[0]); p=p*p*(3-2*p) if it=="smoothstep" else p**3 if it=="cubic-in" else 1-(1-p)**3 if it=="cubic-out" else 4*p**3 if it=="cubic-in-out" and p<.5 else 1-(-2*p+2)**3/2 if it=="cubic-in-out" else p
    return l[1]+(r[1]-l[1])*p

def write(scene,path):
    raw=json.dumps(scene,ensure_ascii=False,separators=(",",":"),sort_keys=True).encode(); payload=gzip.compress(raw,9,mtime=0); crc=zlib.crc32(payload)&0xffffffff
    Path(path).write_bytes(MAGIC+struct.pack(">III",CV,len(payload),crc)+payload)
def read(path):
    d=Path(path).read_bytes()
    if d[:8]!=MAGIC: die(path,"bad magic")
    cv,n,crc=struct.unpack(">III",d[8:20]); p=d[20:]
    if cv!=CV or len(p)!=n or zlib.crc32(p)&0xffffffff!=crc: die(path,"bad container")
    return json.loads(gzip.decompress(p))
def summary(s): print(f"{s['id']} | API {s['api']} | {s['canvas']['width']}x{s['canvas']['height']}@{s['canvas']['fps']} | {s['timeline']['frames']} frames | {len(s['resources'])} resources | {len(s['objects'])} objects | {len(s['selectors'])} selectors | {len(s['features'])} features")

def selftest():
    s={"api":3,"id":"selftest","name":"selftest","canvas":{"width":100,"height":100,"fps":60},"timeline":{"frames":1000,"clock":"absolute","implicitAnimation":False},"resources":{"badge":{"type":"polygon","points":[[0,0],[1,0],[1,1]]}},"objects":[{"id":"b0","kind":"badge","frame":0,"resource":"badge","properties":{"x":{"value":9}}},{"id":"b120","kind":"badge","frame":120,"resource":"badge"}],"selectors":[{"select":"badge[*]","properties":{"x":{"value":1}}},{"select":"badge[frame>=120]","properties":{"y":{"dense":{"start":0,"values":[-2,-1,0]},"extrapolate":"hold"}}}]}
    c=compile_source(s); assert evalv(resolve(c,c["objects"][0])["x"],0,0)==9; assert evalv(resolve(c,c["objects"][1])["y"],121,120)==-1
    d={"api":3,"id":"dedicated","name":"dedicated","canvas":{"width":100,"height":100,"fps":60},"timeline":{"frames":1,"clock":"absolute","implicitAnimation":False},"features":["source-baked-text-raster"],"resources":{"label":{"type":"source-text-raster","source":"assets/label.png"}},"objects":[{"id":"label","kind":"text-raster","frame":0,"resource":"label"}]}
    dc=compile_source(d); assert dc["resources"]["label"]["type"]=="source-text-raster"; assert "source-baked-text-raster" in dc["features"]
    d["features"]=[]
    try: compile_source(d); raise AssertionError("dedicated feature declaration was not enforced")
    except E: pass
    print("renderer_v3_compiler selftest: PASS")

def main():
    p=argparse.ArgumentParser(); sub=p.add_subparsers(dest="cmd",required=True)
    for n in ("lint","compile","inspect","dump-ir"):
        x=sub.add_parser(n); x.add_argument("source" if n!="inspect" else "bundle");
        if n=="compile": x.add_argument("-o","--output")
    sub.add_parser("selftest")
    a=p.parse_args()
    try:
        if a.cmd=="selftest": selftest(); return
        if a.cmd=="inspect": s=read(a.bundle); summary(s); return
        s=compile_source(load(a.source)); summary(s)
        if a.cmd=="lint": print("lint: PASS")
        elif a.cmd=="compile":
            o=a.output or str(Path(a.source).with_suffix(".renderer3")); write(s,o); print("compiled:",o)
        else: print(json.dumps(s,indent=2,ensure_ascii=False,sort_keys=True))
    except E as e: print("error:",e,file=sys.stderr); sys.exit(2)
if __name__=="__main__": main()
