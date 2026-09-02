#!/usr/bin/env python3
"""CTS Renderer API v3 compiler (stdlib only, deterministic)."""
import argparse,gzip,json,math,re,struct,sys,zlib
from pathlib import Path

MAGIC=b"CCRNDR03"; API=3; CV=1
TIMELINES={"absolute","relative"}; INTERP={"raw","hold","linear","smoothstep","cubic-in","cubic-out","cubic-in-out"}; EXTRA={"none","hold"}
RESOURCE_TYPES={"polygon","rect","ellipse","path","image","text","group","shadow","shine","mask","material","filter","custom"}
BLENDS={"normal","src-over","multiply","screen","add","overlay","darken","lighten","difference"}
FEATURES=[
"per-frame-polygon-vertices","full-2d-transforms","shine-geometry-tracks","arbitrary-masks","track-interpolation-modes","per-item-animation","exact-text-tracks","explicit-layer-order","independent-shadow-resources","dense-frame-data","frame-addressed-objects","property-level-selector-inheritance","deterministic-selector-precedence","zero-implicit-animation","generic-renderer-resources","group-transforms","resource-lifespans","selector-shared-behaviour","renderer-owned-geometry","renderer-owned-materials","blend-compositing-modes","per-frame-filter-tracks","exact-artwork-transforms","absolute-integer-frame-clock","single-scene-preview-export-contract","reference-resolution-fps-lock","frame-checkpoints","pixel-diff-audit-contract","selector-cascade-inspection","renderer-api-v3-scene-ir"]
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
    rin=s.get("resources",{})
    if not isinstance(rin,dict): die("resources","expected object")
    rs={}
    for n,r in rin.items():
        if not isinstance(r,dict): die(f"resources.{n}","expected object")
        t=text(r.get("type"),f"resources.{n}.type")
        if t not in RESOURCE_TYPES: die(f"resources.{n}.type",f"unsupported {t}")
        q=dict(r)
        if t=="polygon" and (not isinstance(r.get("points"),list) or len(r["points"])<3): die(f"resources.{n}.points","polygon needs >=3 points")
        if "blend" in r and r["blend"] not in BLENDS: die(f"resources.{n}.blend","unsupported")
        if "properties" in r: q["properties"]=props(r["properties"],f"resources.{n}.properties")
        rs[n]=q
    for n,r in rs.items():
        if r.get("type")=="group":
            for x in r.get("children",[]):
                if x not in rs: die(f"resources.{n}",f"unknown child '{x}'")
    oi=s.get("objects",[])
    if not isinstance(oi,list): die("objects","expected array")
    os=[]; ids=set()
    for i,o in enumerate(oi):
        c=f"objects[{i}]"
        if not isinstance(o,dict): die(c,"expected object")
        oid=text(o.get("id"),c+".id"); kind=text(o.get("kind"),c+".kind"); f=integer(o.get("frame"),c+".frame")
        if oid in ids: die(c,"duplicate id")
        ids.add(oid)
        if not 0<=f<frames: die(c+".frame","outside timeline")
        if o.get("resource") is not None and o["resource"] not in rs: die(c+".resource","unknown resource")
        life=o.get("lifespan",{}); a=integer(life.get("start",f),c+".lifespan.start"); b=integer(life.get("end",frames-1),c+".lifespan.end")
        if not 0<=a<=b<frames: die(c+".lifespan","invalid")
        q={k:v for k,v in o.items() if k not in {"properties","lifespan"}}; q.update({"id":oid,"kind":kind,"frame":f,"lifespan":{"start":a,"end":b},"properties":props(o.get("properties",{}),c+".properties",o.get("timeline","relative"))}); os.append(q)
    si=s.get("selectors",[])
    if not isinstance(si,list): die("selectors","expected array")
    ss=[]
    for i,x in enumerate(si):
        c=f"selectors[{i}]"
        if not isinstance(x,dict): die(c,"expected object")
        p=selector(x.get("select")); d=x.get("timeline","relative")
        if d not in TIMELINES: die(c+".timeline","invalid")
        ss.append({"select":p["raw"],"parsed":p,"timeline":d,"properties":props(x.get("properties",{}),c+".properties",d),"sourceOrder":i})
    cps=s.get("checkpoints",[])
    if not isinstance(cps,list): die("checkpoints","expected array")
    for i,x in enumerate(cps):
        if not isinstance(x,dict) or not 0<=integer(x.get("frame"),f"checkpoints[{i}].frame")<frames: die(f"checkpoints[{i}]","invalid")
    layers=s.get("layers",[]); audit=s.get("audit",{})
    if not isinstance(layers,list) or not isinstance(audit,dict): die("layers/audit","invalid")
    return {"format":"CTS Renderer API v3 Scene IR","api":3,"id":rid,"name":name,"author":s.get("author",""),"canvas":{"width":w,"height":h,"fps":fps,"lock":bool(ca.get("lock",True))},"timeline":{"frames":frames,"clock":"absolute","implicitAnimation":False},"features":FEATURES,"resources":rs,"objects":os,"selectors":ss,"layers":layers,"checkpoints":cps,"audit":audit,"runtimeContract":{"integerFrameClock":True,"noImplicitAnimation":True,"selectorMerge":"property-level","selectorTieBreak":"specificity-then-source-order","previewExportSceneEvaluator":"same","undeclaredTrackBehavior":"static/no-op"}}

def resolve(scene,o):
    win={}; hist={}
    rn=o.get("resource")
    if rn:
        for k,v in scene["resources"].get(rn,{}).get("properties",{}).items(): win[k]=(0,-1,"resource:"+rn,v); hist.setdefault(k,[]).append({"source":"resource:"+rn,"specificity":0,"sourceOrder":-1})
    for x in scene["selectors"]:
        p=x["parsed"]
        if not matches(p,o): continue
        rank=(p["specificity"],x["sourceOrder"])
        for k,v in x["properties"].items():
            hist.setdefault(k,[]).append({"source":x["select"],"specificity":rank[0],"sourceOrder":rank[1]})
            if k not in win or rank>=(win[k][0],win[k][1]): win[k]=(rank[0],rank[1],x["select"],v)
    for k,v in o.get("properties",{}).items(): win[k]=(1000,10**9,"object:"+o["id"],v); hist.setdefault(k,[]).append({"source":"object:"+o["id"],"specificity":1000,"sourceOrder":10**9})
    return {k:x[3] for k,x in win.items()},{k:{"winner":win[k][2],"candidates":v} for k,v in hist.items()}

def curve(x,n):
    if n=="smoothstep": return x*x*(3-2*x)
    if n=="cubic-in": return x**3
    if n=="cubic-out": return 1-(1-x)**3
    if n=="cubic-in-out": return 4*x**3 if x<.5 else 1-(-2*x+2)**3/2
    return x

def evalv(v,g,a):
    if not isinstance(v,dict) or v.get("kind") not in {"constant","dense","sparse"}: return v
    if v["kind"]=="constant": return v["value"]
    f=g if v["timeline"]=="absolute" else g-a; ex=v["extrapolate"]
    if v["kind"]=="dense":
        i=f-v["start"]; vs=v["values"]
        if 0<=i<len(vs): return vs[i]
        return (vs[0] if i<0 else vs[-1]) if ex=="hold" else UNSET
    ks=v["keys"]
    if f<ks[0][0]: return ks[0][1] if ex=="hold" else UNSET
    if f>ks[-1][0]: return ks[-1][1] if ex=="hold" else UNSET
    for t,x in ks:
        if f==t:return x
    it=v["interpolation"]
    if it=="raw": return UNSET
    l=max(k for k in ks if k[0]<f); r=min(k for k in ks if k[0]>f)
    if it=="hold": return l[1]
    if not isinstance(l[1],(int,float)) or not isinstance(r[1],(int,float)): die("track","interpolation needs numbers")
    p=curve((f-l[0])/(r[0]-l[0]),it); return l[1]+(r[1]-l[1])*p

def obj(scene,oid):
    for o in scene["objects"]:
        if o["id"]==oid:return o
    die("object",f"'{oid}' not found")
def evaluate(scene,o,f):
    if not o["lifespan"]["start"]<=f<=o["lifespan"]["end"]: return {"visible":False,"properties":{}}
    ps,_=resolve(scene,o); out={}
    for k,v in ps.items():
        x=evalv(v,f,o["frame"])
        if x is not UNSET: out[k]=x
    return {"visible":True,"properties":out}

def canon(s): return json.dumps(s,ensure_ascii=False,separators=(",",":"),sort_keys=True).encode()
def write(scene,p):
    raw=gzip.compress(canon(scene),compresslevel=9,mtime=0); crc=zlib.crc32(raw)&0xffffffff; p=Path(p); p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(MAGIC+struct.pack(">III",CV,len(raw),crc)+raw)
def read(p):
    d=Path(p).read_bytes()
    if len(d)<20 or d[:8]!=MAGIC: die(p,"not Renderer v3")
    cv,n,crc=struct.unpack(">III",d[8:20]); raw=d[20:]
    if cv!=CV or n!=len(raw) or zlib.crc32(raw)&0xffffffff!=crc: die(p,"bad container")
    return json.loads(gzip.decompress(raw))
def scene(p): return read(p) if str(p).endswith(".renderer3") else compile_source(load(p))
def summary(s): print(f"{s['id']} | API {s['api']} | {s['canvas']['width']}x{s['canvas']['height']}@{s['canvas']['fps']} | {s['timeline']['frames']} frames | {len(s['resources'])} resources | {len(s['objects'])} objects | {len(s['selectors'])} selectors | {len(s['features'])} features")

def selftest():
    s=compile_source({"api":3,"id":"test","name":"test","timeline":{"frames":1000,"implicitAnimation":False},"resources":{"b":{"type":"polygon","points":[[0,0],[1,0],[0,1]]}},"objects":[{"id":"b@120","kind":"badge","frame":120,"resource":"b","properties":{"movement":{"x":{"value":9}}}},{"id":"b@528","kind":"badge","frame":528,"resource":"b"},{"id":"b@742","kind":"badge","frame":742,"resource":"b"}],"selectors":[{"select":"badge[*]","properties":{"movement":{"x":{"value":1}}}},{"select":"badge[every=214,from=528]","properties":{"movement":{"y":{"dense":{"start":0,"values":[-10,-5,0]},"extrapolate":"hold"}}}}]})
    assert evaluate(s,obj(s,"b@120"),120)["properties"]["movement.x"]==9
    assert evaluate(s,obj(s,"b@528"),529)["properties"]["movement.y"]==-5
    assert evaluate(s,obj(s,"b@742"),744)["properties"]["movement.y"]==0
    print("renderer_v3_compiler selftest: PASS")

def main():
    p=argparse.ArgumentParser(); sp=p.add_subparsers(dest="cmd",required=True)
    for n in ("lint","compile","inspect","dump-ir"):
        q=sp.add_parser(n); q.add_argument("source")
        if n=="compile": q.add_argument("-o","--output")
    q=sp.add_parser("explain"); q.add_argument("source"); q.add_argument("--object",required=True); q.add_argument("--frame",type=int,required=True)
    sp.add_parser("selftest"); a=p.parse_args()
    try:
        if a.cmd=="selftest": selftest(); return
        s=scene(a.source)
        if a.cmd=="lint": summary(s); print("lint: PASS")
        elif a.cmd=="compile": out=a.output or str(Path(a.source).with_suffix(".renderer3")); write(s,out); summary(s); print("compiled:",out)
        elif a.cmd=="inspect": summary(s)
        elif a.cmd=="dump-ir": print(json.dumps(s,indent=2,ensure_ascii=False,sort_keys=True))
        elif a.cmd=="explain":
            o=obj(s,a.object); ps,h=resolve(s,o); ev=evaluate(s,o,a.frame); print(json.dumps({"object":{"id":o["id"],"kind":o["kind"],"anchorFrame":o["frame"],"frame":a.frame,"visible":ev["visible"]},"resolvedProperties":ev["properties"],"cascade":h},indent=2,ensure_ascii=False,sort_keys=True))
    except E as e: print("error:",e,file=sys.stderr); raise SystemExit(2)
if __name__=="__main__": main()
