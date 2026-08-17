from __future__ import annotations

"""Clean source-frame renderer used only by Cubical Compare Canary.

The renderer intentionally does not import the legacy renderer or legacy
motion helpers. Geometry and animation come from canary_reference.py.
"""

from collections import OrderedDict
from functools import lru_cache
from pathlib import Path
import math
import os
import subprocess

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

from .assets import materialize_remote_asset
from .models import Card, Project
from . import canary_reference as ref


BLACK = (0, 0, 0)
TITLE_BG = (241, 241, 241)
DESC_BG = (99, 94, 91)
DIVIDER = (25, 20, 18)
TITLE_TEXT = (8, 8, 8)
DESC_TEXT = (250, 250, 250)
BADGE_RED = (210, 9, 12)
BADGE_EDGE = (183, 4, 8)
BADGE_TEXT = (255, 255, 255)

_FADE_ALPHA = "eNoBTwCw//7+/Pv49fTz8e7s6+nm5ODc2tfT0MzJxsO/vbi1s6+sqaWioJyZl5KQjYqHhHt7enh1c25raWViYF1ZV1NPTkpHREE3NzUzLiwoIx8ZEgD7nS0H"


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


def lerp(a: float, b: float, p: float) -> float:
    return a + (b - a) * p


def smoothstep(v: float) -> float:
    v = clamp(v)
    return v * v * (3.0 - 2.0 * v)


class FrameRenderer:
    """Deterministic 1920x1080/60 renderer for the Canary source contract."""

    def __init__(self) -> None:
        self._settings = None
        self._image_cache: OrderedDict[str, Image.Image | None] = OrderedDict()
        self._body_cache: OrderedDict[tuple[object, ...], Image.Image] = OrderedDict()
        self._max_image_cache = 16
        self._max_body_cache = 64

    @staticmethod
    @lru_cache(maxsize=256)
    def _resolve_font(value: str, bold: bool = False) -> str:
        requested = (value or "").strip().strip('"')
        if requested:
            p = Path(requested).expanduser()
            if p.is_file():
                return str(p)
            if os.name != "nt":
                try:
                    r = subprocess.run(
                        ["fc-match", "-f", "%{file}\n", requested],
                        capture_output=True, text=True, timeout=2, check=False,
                    )
                    if r.stdout.strip():
                        p = Path(r.stdout.splitlines()[0].strip())
                        if p.is_file():
                            return str(p)
                except Exception:
                    pass
        return "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"

    @classmethod
    @lru_cache(maxsize=512)
    def _load_font(cls, requested: str, size: int, bold: bool) -> ImageFont.ImageFont:
        try:
            return ImageFont.truetype(cls._resolve_font(requested, bold), max(1, int(size)))
        except OSError:
            try:
                return ImageFont.load_default(size=max(1, int(size)))
            except TypeError:
                return ImageFont.load_default()

    def _font(self, size: int, *, bold: bool, role: str) -> ImageFont.ImageFont:
        s = self._settings
        requested = ""
        if s is not None:
            requested = {
                "title": getattr(s, "font_title", ""),
                "description": getattr(s, "font_description", ""),
                "badge": getattr(s, "font_badge", ""),
                "credits": getattr(s, "font_credits", ""),
            }.get(role, "")
        return self._load_font(requested or "", size, bold)

    @staticmethod
    def _value_lines(value: str) -> list[str]:
        words = str(value or "").upper().split()
        if not words:
            return []
        if len(words) == 1:
            return words
        return [words[0], " ".join(words[1:])]

    def _fit_font(self, text: str, size: int, width: int, *, role: str, bold: bool, minimum: int) -> ImageFont.ImageFont:
        probe = ImageDraw.Draw(Image.new("L", (2, 2)))
        current = max(minimum, size)
        while current > minimum:
            f = self._font(current, bold=bold, role=role)
            b = probe.textbbox((0, 0), text, font=f)
            if b[2] - b[0] <= width:
                return f
            current -= 1
        return self._font(minimum, bold=bold, role=role)

    def _wrap(self, draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont, width: int, max_lines: int) -> list[str]:
        words = str(text or "").split()
        if not words:
            return []
        lines: list[str] = []
        current = ""
        for word in words:
            candidate = word if not current else current + " " + word
            if draw.textbbox((0, 0), candidate, font=font)[2] <= width:
                current = candidate
            else:
                if current:
                    lines.append(current)
                current = word
                if len(lines) >= max_lines:
                    break
        if current and len(lines) < max_lines:
            lines.append(current)
        return lines[:max_lines]

    def _load_image(self, source: str) -> Image.Image | None:
        key = str(source or "").strip()
        if not key:
            return None
        if key in self._image_cache:
            item = self._image_cache.pop(key)
            self._image_cache[key] = item
            return item.copy() if item else None
        loaded: Image.Image | None = None
        try:
            p = materialize_remote_asset(key) if key.lower().startswith(("http://", "https://")) else Path(key).expanduser()
            if p.is_file():
                with Image.open(p) as im:
                    loaded = im.convert("RGBA").copy()
        except Exception:
            loaded = None
        self._image_cache[key] = loaded.copy() if loaded else None
        while len(self._image_cache) > self._max_image_cache:
            self._image_cache.popitem(last=False)
        return loaded.copy() if loaded else None

    @staticmethod
    def _cover(im: Image.Image, size: tuple[int, int]) -> Image.Image:
        return ImageOps.fit(im.convert("RGBA"), size, Image.Resampling.LANCZOS, centering=(0.5, 0.5))

    @staticmethod
    def _contain(im: Image.Image, size: tuple[int, int]) -> Image.Image:
        c = im.convert("RGBA")
        c.thumbnail(size, Image.Resampling.LANCZOS)
        out = Image.new("RGBA", size, (0, 0, 0, 0))
        out.alpha_composite(c, ((size[0] - c.width)//2, (size[1] - c.height)//2))
        return out

    def _card_layer(self, card: Card) -> Image.Image:
        title = " ".join(str(card.title or "").split())
        desc = " ".join(str(card.description or "").split())
        key = (
            card.id, title, desc, card.image,
            getattr(self._settings, "font_title", ""),
            getattr(self._settings, "font_description", ""),
            getattr(self._settings, "image_fit_mode", "cover"),
        )
        hit = self._body_cache.get(key)
        if hit is not None:
            self._body_cache.move_to_end(key)
            return hit.copy()

        title_h = ref.TITLE_HEIGHT if title else 0
        desc_h = ref.DESCRIPTION_HEIGHT if desc else 0
        divider_h = ref.DIVIDER_HEIGHT if desc else 0
        bottom_h = ref.BOTTOM_BORDER_HEIGHT if desc else 0
        art_h = ref.HEIGHT - title_h - divider_h - desc_h - bottom_h

        layer = Image.new("RGB", (ref.BODY_WIDTH, ref.HEIGHT), BLACK)
        source = self._load_image(card.image)
        if source is None:
            art = Image.new("RGB", (ref.BODY_WIDTH, art_h), (0, 169, 224))
        else:
            fit = getattr(self._settings, "image_fit_mode", "cover")
            rgba = self._contain(source, (ref.BODY_WIDTH, art_h)) if fit == "contain" or source.getextrema()[3][0] < 255 else self._cover(source, (ref.BODY_WIDTH, art_h))
            bg = Image.new("RGBA", (ref.BODY_WIDTH, art_h), (0, 169, 224, 255))
            bg.alpha_composite(rgba)
            art = bg.convert("RGB")
        layer.paste(art, (0, 0))

        draw = ImageDraw.Draw(layer)
        y = art_h
        if title:
            draw.rectangle((0, y, ref.BODY_WIDTH-1, y+title_h-1), fill=TITLE_BG)
            font = self._fit_font(title, 33, ref.BODY_WIDTH-26, role="title", bold=True, minimum=20)
            lines = self._wrap(draw, title, font, ref.BODY_WIDTH-26, 2)
            lh = max(24, int(getattr(font, "size", 30)*1.05))
            ty = y + (title_h - len(lines)*lh)//2
            for line in lines:
                draw.text((ref.BODY_WIDTH/2, ty), line, font=font, fill=TITLE_TEXT, anchor="ma")
                ty += lh
            y += title_h
        if desc:
            draw.rectangle((0, y, ref.BODY_WIDTH-1, y), fill=DIVIDER)
            y += divider_h
            draw.rectangle((0, y, ref.BODY_WIDTH-1, y+desc_h-1), fill=DESC_BG)
            font = self._fit_font(desc, 23, ref.BODY_WIDTH-30, role="description", bold=False, minimum=14)
            lines = self._wrap(draw, desc, font, ref.BODY_WIDTH-30, 3)
            lh = max(17, int(getattr(font, "size", 20)*1.12))
            dy = y + (desc_h - len(lines)*lh)//2
            for line in lines:
                draw.text((ref.BODY_WIDTH/2, dy), line, font=font, fill=DESC_TEXT, anchor="ma")
                dy += lh
            y += desc_h
            draw.rectangle((0, y, ref.BODY_WIDTH-1, ref.HEIGHT-1), fill=DIVIDER)

        self._body_cache[key] = layer.copy()
        while len(self._body_cache) > self._max_body_cache:
            self._body_cache.popitem(last=False)
        return layer

    def _draw_body(self, canvas: Image.Image, card: Card, x: int) -> None:
        if x >= ref.WIDTH or x + ref.BODY_WIDTH <= 0:
            return
        canvas.paste(self._card_layer(card), (int(x), 0))

    def _badge_source(self, card: Card, text_progress: float = 1.0, shine: float = -1.0) -> Image.Image:
        layer = Image.new("RGBA", ref.OPENING_BADGE_CANVAS, (0, 0, 0, 0))
        polygon = [(int(round(x)), int(round(y))) for x, y in ref.OPENING_BADGE_POLYGON]
        shadow = Image.new("L", layer.size, 0)
        ImageDraw.Draw(shadow).polygon([(x+8, y+10) for x,y in polygon], fill=175)
        shadow = shadow.filter(ImageFilter.GaussianBlur(8))
        sh = Image.new("RGBA", layer.size, (0,0,0,145))
        sh.putalpha(shadow)
        layer.alpha_composite(sh)
        d = ImageDraw.Draw(layer)
        d.polygon(polygon, fill=(*BADGE_RED,255))
        d.line(polygon+[polygon[0]], fill=(*BADGE_EDGE,175), width=2, joint="curve")

        lines = self._value_lines(card.value)
        if text_progress > 0 and lines:
            text_layer = Image.new("RGBA", layer.size, (0,0,0,0))
            td = ImageDraw.Draw(text_layer)
            specs = [(lines[0], 202, 78)] if len(lines) == 1 else [(lines[0], 170, 75), (lines[1], 235, 38)]
            for line_i,(text,y,size) in enumerate(specs):
                p = clamp((text_progress - line_i*0.16)/0.84)
                if p <= 0:
                    continue
                font = self._fit_font(text, size, 286, role="badge", bold=True, minimum=20)
                yy = y - (1.0-smoothstep(p))*62
                alpha = int(255*clamp(p*1.45))
                td.text((239, yy+6), text, font=font, fill=(35,20,20,int(alpha*.55)), anchor="mm")
                td.text((235, yy), text, font=font, fill=(*BADGE_TEXT,alpha), anchor="mm")
            if text_progress < 1:
                text_layer = text_layer.filter(ImageFilter.GaussianBlur(max(0.0,(1-text_progress)*2.2)))
            layer.alpha_composite(text_layer)

        if 0.0 <= shine <= 1.0:
            mask = Image.new("L", layer.size, 0)
            ImageDraw.Draw(mask).polygon(polygon, fill=255)
            gloss = Image.new("RGBA", layer.size, (0,0,0,0))
            gd = ImageDraw.Draw(gloss)
            top = lerp(105, 430, shine)
            bottom = top - 205
            gd.polygon([(top-34,-50),(top+34,-50),(bottom+34,450),(bottom-34,450)], fill=(255,255,255,42))
            gd.polygon([(top-4,-50),(top+4,-50),(bottom+4,450),(bottom-4,450)], fill=(255,255,255,90))
            gloss = gloss.filter(ImageFilter.GaussianBlur(7))
            gloss.putalpha(ImageChops.multiply(gloss.getchannel("A"), mask))
            layer.alpha_composite(gloss)
        return layer

    @staticmethod
    def _forward(point: tuple[float,float], affine: tuple[float,float,float,float,float,float], body_x: int) -> tuple[float,float]:
        a,b,c,d,tx,ty=affine
        x,y=point
        return body_x+a*x+b*y+tx, c*x+d*y+ty

    def _warp_badge(self, canvas: Image.Image, source: Image.Image, body_x: int, affine: tuple[float,float,float,float,float,float]) -> None:
        a,b,c,d,tx,ty=affine
        corners=[(0.,0.),(float(source.width),0.),(float(source.width),float(source.height)),(0.,float(source.height))]
        transformed=[self._forward(p,affine,body_x) for p in corners]
        x0=max(0,math.floor(min(p[0] for p in transformed))-4); x1=min(ref.WIDTH,math.ceil(max(p[0] for p in transformed))+4)
        y0=max(0,math.floor(min(p[1] for p in transformed))-4); y1=min(ref.HEIGHT,math.ceil(max(p[1] for p in transformed))+4)
        if x1<=x0 or y1<=y0:return
        det=a*d-b*c
        if abs(det)<1e-9:return
        ia,ib,id_,ie=d/det,-b/det,-c/det,a/det
        gx=body_x+tx; gy=ty
        coeff=(ia,ib,ia*(x0-gx)+ib*(y0-gy),id_,ie,id_*(x0-gx)+ie*(y0-gy))
        warped=source.transform((x1-x0,y1-y0),Image.Transform.AFFINE,coeff,resample=Image.Resampling.BICUBIC)
        canvas.paste(warped.convert("RGB"),(x0,y0),warped.getchannel("A"))

    def _draw_bbox_badge(self, canvas: Image.Image, card: Card, x: int, y: int, w: int, h: int, *, local_frame: int) -> None:
        if x>=ref.WIDTH or x+w<=0 or y>=ref.HEIGHT or y+h<=0:
            return
        text_p = clamp((local_frame-158)/42) if local_frame >= 120 else clamp((local_frame-58)/32)
        shine = clamp((local_frame-205)/40) if 205 <= local_frame <= 245 else -1.0
        source=self._badge_source(card,text_p,shine)
        crop=source.crop((63,5,406,394))
        badge=crop.resize((max(1,w),max(1,h)),Image.Resampling.LANCZOS)
        canvas.paste(badge.convert("RGB"),(int(x),int(y)),badge.getchannel("A"))

    def _draw_badge(self, canvas: Image.Image, project: Project, index: int, body_x: int, frame: int) -> None:
        if not getattr(project.settings,"show_badges",True):
            return
        card=project.cards[index]
        if not str(card.value or "").strip():
            return
        start=ref.card_start_frame(index)
        local=frame-start
        if index<4:
            delay=ref.FOURTH_OPENING_BADGE_DELAY if index==3 else 0
            effective=local-delay
            if ref.OPENING_ENTRY_FIRST_LOCAL_FRAME <= effective < ref.OPENING_ENTRY_LAST_LOCAL_FRAME:
                affine=ref.opening_entry_affine(effective)
                if affine is None:return
                text_p=clamp((effective-58)/32)
                shine=clamp((effective-100)/28) if effective>=100 else -1.0
                self._warp_badge(canvas,self._badge_source(card,text_p,shine),body_x,affine)
                return
            stage=ref.opening_badge_stage(index,frame)
            if stage is None:return
            xoff,y,w,h=stage
            self._draw_bbox_badge(canvas,card,body_x+xoff,y,w,h,local_frame=max(120,effective))
            return

        state=ref.continuous_badge_state(local)
        if state is None:return
        w,h,y=state
        x=int(round(body_x+ref.CONTINUOUS_BADGE_CENTER_X-w/2))
        self._draw_bbox_badge(canvas,card,x,y,w,h,local_frame=local)

    def _draw_credits(self, canvas: Image.Image, x: int, project: Project) -> None:
        if not getattr(project.settings,"credits_enabled",True):
            return
        if x>=ref.WIDTH or x+ref.BODY_WIDTH<=0:return
        panel=Image.new("RGB",(ref.BODY_WIDTH,ref.HEIGHT),(28,28,29))
        d=ImageDraw.Draw(panel)
        small=self._font(22,bold=False,role="credits")
        bold=self._font(29,bold=True,role="credits")
        top=getattr(project.settings,"credits_top_text","").strip()
        if top:
            yy=48
            for line in self._wrap(d,top,small,ref.BODY_WIDTH-56,4):
                d.text((ref.BODY_WIDTH/2,yy),line,font=small,fill=(244,244,244),anchor="ma");yy+=29
        d.line((45,202,ref.BODY_WIDTH-45,202),fill=(160,160,160),width=2)
        heading=getattr(project.settings,"credits_heading","Credits").strip()
        if heading:d.text((ref.BODY_WIDTH/2,280),heading,font=self._fit_font(heading,42,ref.BODY_WIDTH-60,role="credits",bold=True,minimum=20),fill="white",anchor="mm")
        rows=[
            getattr(project.settings,"credits_project_name","Cubical Compare Canary"),
            getattr(project.settings,"credits_created_with_label","Created with"),
            getattr(project.settings,"credits_created_with_value","Cubical Compare Canary"),
            getattr(project.settings,"credits_design_label","Design & Rendering"),
            getattr(project.settings,"credits_design_value","Cubical"),
        ]
        yy=370
        for n,text in enumerate(rows):
            text=str(text or "").strip()
            if text:
                f=bold if n in (0,2,4) else small
                d.text((ref.BODY_WIDTH/2,yy),text,font=f,fill="white",anchor="mm")
            yy+=45
        canvas.paste(panel,(x,0))

    def _positions(self, frame: int, count: int) -> dict[int,int]:
        out:dict[int,int]={}
        if frame < ref.CONTINUOUS_START_FRAME:
            for i in range(min(4,count)):
                x=ref.opening_card_x(i,frame)
                if x is not None and -ref.BODY_WIDTH < x < ref.WIDTH:
                    out[i]=x
            return out
        for i in range(count):
            x=ref.continuous_body_x(frame,i)
            if x is not None and -ref.BODY_WIDTH < x < ref.WIDTH:
                out[i]=x
        return out

    def _render_content(self, project: Project, frame: int) -> Image.Image:
        canvas=Image.new("RGB",(ref.WIDTH,ref.HEIGHT),BLACK)
        positions=self._positions(frame,len(project.cards))
        if frame < ref.CONTINUOUS_START_FRAME:
            active=max(positions,default=-1)
            order=([active] if active>=0 else [])+[i for i in sorted(positions) if i!=active]
        else:
            order=sorted(positions)
        for i in order:
            self._draw_body(canvas,project.cards[i],positions[i])
        if frame < ref.CONTINUOUS_START_FRAME:
            self._draw_credits(canvas,ref.credits_x(frame),project)
        for i in sorted(positions):
            self._draw_badge(canvas,project,i,positions[i],frame)
        return canvas

    @staticmethod
    def _cover_y(local:int)->int:
        values=(0,0,0,0,0,0,0,0,0,0,28,72,128,196,273,357,445,535,626,714,798,874,942,999,1042,1070,1080)
        return values[min(max(0,local),len(values)-1)]

    @staticmethod
    def _end_group_top(local:int)->int|None:
        keys=((43,-210),(44,-210),(45,-183),(46,-144),(47,-108),(48,-78),(49,-51),(50,-30),(51,-14),(52,-4),(53,0))
        if local<43:return None
        if local>=53:return 0
        for (f0,y0),(f1,y1) in zip(keys,keys[1:]):
            if local<=f1:return int(round(lerp(y0,y1,(local-f0)/(f1-f0))))
        return 0

    def _draw_end_group(self,canvas:Image.Image,top:int,project:Project)->None:
        layer=Image.new("RGBA",(1440,ref.HEIGHT),(0,0,0,0));d=ImageDraw.Draw(layer)
        for x0,y0,x1,y1,label in (
            (40,210,689,669,getattr(project.settings,"end_best_label","BEST VIDEO FOR YOU")),
            (750,210,1400,669,getattr(project.settings,"end_newest_label","NEWEST VIDEO")),
        ):
            d.rounded_rectangle((x0,y0,x1,y1),radius=18,fill=(*BADGE_RED,255))
            f=self._fit_font(str(label),35,x1-x0-50,role="title",bold=True,minimum=20)
            d.text(((x0+x1)/2,y0+30),str(label),font=f,fill="white",anchor="ma")
        d.rounded_rectangle((468,741,970,1010),radius=22,fill=(81,77,67,255))
        d.text((719,770),str(getattr(project.settings,"end_credit_label","Video Made By")),font=self._font(25,bold=True,role="credits"),fill="white",anchor="ma")
        d.text((719,835),str(getattr(project.settings,"end_credit_value","Cubical Compare Canary") or project.name),font=self._font(20,bold=False,role="credits"),fill="white",anchor="ma")
        canvas.paste(layer.convert("RGB"),(0,top),layer.getchannel("A"))

    @staticmethod
    def _interp_box(local:int,keys:tuple[tuple[int,int,int,int,int],...])->tuple[int,int,int,int]|None:
        if local<keys[0][0]:return None
        if local>=keys[-1][0]:return keys[-1][1:]
        for a,b in zip(keys,keys[1:]):
            if local<=b[0]:
                p=(local-a[0])/max(1,b[0]-a[0])
                return tuple(int(round(lerp(a[i],b[i],p))) for i in range(1,5))
        return keys[-1][1:]

    def _draw_action_bar(self,canvas:Image.Image,local:int)->None:
        keys=((54,716,98,42,8),(56,696,93,82,18),(58,665,85,143,33),(60,632,77,211,49),(62,580,64,314,75),(64,563,60,349,84),(66,548,56,379,91),(68,536,53,403,97),(70,517,49,441,106),(72,510,47,455,109),(74,503,45,469,113),(76,498,44,479,115),(78,489,42,497,120),(80,485,41,505,122),(82,482,40,511,123),(84,479,39,517,125),(86,474,38,526,127),(88,473,38,529,127),(90,471,37,533,129),(92,471,37,533,129),(94,470,37,535,129),(96,468,37,539,129),(98,468,37,539,130),(100,468,37,540,130),(102,468,37,540,130))
        box=self._interp_box(local,keys)
        if box is None:return
        x,y,w,h=box
        layer=Image.new("RGBA",canvas.size,(0,0,0,0));d=ImageDraw.Draw(layer)
        d.rounded_rectangle((x,y,x+w,y+h),radius=max(2,min(24,h//4)),fill=(236,236,236,255))
        subkeys=((74,796,103,22,7),(76,782,98,52,15),(78,754,89,110,32),(80,746,86,128,37),(82,740,84,140,40),(84,735,82,150,44),(86,728,80,164,48),(88,726,79,169,49),(90,724,78,173,51),(92,724,78,173,51),(94,722,78,177,51),(96,720,78,182,52),(98,719,77,183,53),(100,718,77,185,53),(102,718,77,185,53))
        sb=self._interp_box(local,subkeys)
        if sb:
            sx,sy,sw,sh=sb;d.rounded_rectangle((sx,sy,sx+sw,sy+sh),radius=max(1,sh//5),fill=(253,67,69,255))
            if sh>=28:d.text((sx+sw/2,sy+sh/2),"Subscribe",font=self._font(max(10,int(sh*.45)),bold=True,role="credits"),fill="white",anchor="mm")
        canvas.paste(layer.convert("RGB"),(0,0),layer.getchannel("A"))

    @staticmethod
    @lru_cache(maxsize=1)
    def _fade_values()->bytes:
        import base64,zlib
        return zlib.decompress(base64.b64decode(_FADE_ALPHA))

    def _render_outro(self,project:Project,frame:int,content_end:int)->Image.Image:
        local=max(0,frame-content_end)
        final=self._render_content(project,max(0,content_end-1))
        if local<ref.END_WIPE_FRAMES:
            out=final.copy();cover=self._cover_y(local)
            if cover:ImageDraw.Draw(out).rectangle((0,0,1439,cover-1),fill=BLACK)
            return out
        out=Image.new("RGB",(ref.WIDTH,ref.HEIGHT),BLACK)
        out.paste(final.crop((1440,0,ref.WIDTH,ref.HEIGHT)),(1440,0))
        top=self._end_group_top(local)
        if top is not None:self._draw_end_group(out,top,project)
        self._draw_action_bar(out,local)
        fade_start=ref.END_WIPE_FRAMES+ref.END_RISE_FRAMES+ref.END_HOLD_FRAMES
        if local>=fade_start:
            fi=local-fade_start
            vals=self._fade_values()
            remain=vals[min(max(0,fi),len(vals)-1)]/255.0 if vals else 0.0
            black=Image.new("RGB",out.size,BLACK)
            out=Image.blend(black,out,remain)
        return out

    def render(self,project:Project,seconds:float,output_size:tuple[int,int]|None=None)->Image.Image:
        self._settings=project.settings
        if not project.cards:
            empty=Image.new("RGB",(ref.WIDTH,ref.HEIGHT),BLACK)
            d=ImageDraw.Draw(empty)
            d.text((ref.WIDTH/2,ref.HEIGHT/2),"Click to Insert Data",font=self._font(58,bold=True,role="title"),fill="white",anchor="mm")
            return self._scale(empty,output_size)
        frame=max(0,int(max(0.0,float(seconds))*ref.FPS+1e-9))
        content_end=ref.content_end_frame(len(project.cards))
        total=ref.total_frame_count(len(project.cards))
        if frame>=total-ref.BLACK_TAIL_FRAMES:
            image=Image.new("RGB",(ref.WIDTH,ref.HEIGHT),BLACK)
        elif frame<content_end:
            image=self._render_content(project,frame)
        else:
            image=self._render_outro(project,frame,content_end)
        return self._scale(image,output_size)

    def _scale(self,image:Image.Image,output_size:tuple[int,int]|None)->Image.Image:
        if not output_size or output_size==image.size:return image
        w,h=max(2,int(output_size[0])),max(2,int(output_size[1]))
        fit=ImageOps.contain(image,(w,h),Image.Resampling.LANCZOS)
        out=Image.new("RGB",(w,h),BLACK);out.paste(fit,((w-fit.width)//2,(h-fit.height)//2));return out

    def render_output_frame(self,project:Project,seconds:float)->Image.Image:
        return self.render(project,seconds,(int(project.settings.width),int(project.settings.height)))

    def duration(self,project:Project)->float:
        return ref.total_frame_count(len(project.cards))/ref.FPS
