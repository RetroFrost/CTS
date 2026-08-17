from __future__ import annotations
"""Integer source-frame contract for Cubical Compare Canary.

Measurements come from the 1920x1080/60 Evolution Of Language reference.
No renderer easing is used for measured motion.
"""
import base64
import struct
import zlib
from functools import lru_cache

WIDTH=1920
HEIGHT=1080
FPS=60
SLOT_PITCH=477
BODY_INSET=8
BODY_WIDTH=469
BODY_HEIGHT=1080
IMAGE_HEIGHT=872
TITLE_TOP=872
TITLE_HEIGHT=92
DIVIDER_Y=964
DIVIDER_HEIGHT=1
DESCRIPTION_TOP=965
DESCRIPTION_HEIGHT=110
BOTTOM_BORDER_TOP=1075
BOTTOM_BORDER_HEIGHT=5

OPENING_END_FRAME=528
CONTINUOUS_START_FRAME=528
CONTINUOUS_STEP_FRAMES=214
CANONICAL_CARD_COUNT=57
CONTENT_END_FRAME=11858
END_WIPE_FRAMES=43
END_RISE_FRAMES=11
END_HOLD_FRAMES=268
FADE_FRAMES=79
BLACK_TAIL_FRAMES=8
TOTAL_FRAMES=12267
_SENTINEL=32767

_OPENING_X='eNrt1ktI1FEUBvD73dO5lSUVbqSwRUGLwWghuJDosREhKAJdSBS1MRBpEUmBRGFFLpIWUlgYpamp2eSzNNN0LI0BrcwS0zRfTdhLrOx9T3/H2dVyFtIMv/0Hh/PguKwrIM7G2wS7xSbaHTbVptlMe9oW2FrrtSN2xkbIKomVBEmSZNkr6XJIjsopyZVzUiBFUiZuqZUGaRaPdIhXuqVHnkm/DMqwjMqE+GRS3skHmZJp+Swz8k2+y0/5Jb9FRCkorUgtUOwwaqFjkd/igIggCm5aWNj/am735Fhoma18iYpUy1WUilar1Tq1QSWoJJWqMtQJdVFVq041qD46R2sZYuBCPLZiG1KwB2k4gExkIRs5yEUe8nEJhShBOW6gGnVoQBNa0IZ2dOAhvOjCIzxBD3rxHH3oxwsM4CWGMIxXGHGMYgzjjgm/1wG+IAt+YliomJ3HULsPocaHN5jEe0zjKyyMXqFj9Hq9WSfrdJ2t87Vbe3SvHtefnBcukqJpLcVSPG2iRNpOKbSL9tF+yqCDdJiy6DidpBw6Q2cpj87TBSqgy1RIxVRKZVRBleSmKqqhWqqnW9RAjXSHmuguNVML3XO0UpvD49f+l/th88xcX8IbFBb2LznmiEk3u81Ok2g2mjjjMmvMShNllhpjlPnBX3iK37KPx3iIB7iPn/Jj7mIvd/ID9nArN3MTN/Jtruc6ruEqvsluruTrXMHlXMbXuNRRwsWOq46igMJ544rfH93mYUc='
_CREDITS_X='eNrt08FHA2AcxnHieZ5D7BAdml2mQyLSDh3aYaR06FBiRKmYMemcjjvt0B/QJapVq7bZkiyTTWJFpI3RYdGhw2iHdYnUZf1aIuqekc/lfW8v7/cJK/zDouY1pWH1y6k21VhmnnGuMcJlhjjLSY7SSw976WYXO9hOsIFXPOMJNVTxgHtUcIsyirjGFS5RwDnOkMMpsjhBBsc4wiHSSCGJBOI4wD72EMOu2THbTdGmrX8t5uvPNs3nacN8v0f/6F0fbcWstqTVl7EeC7ixUh/xBge7OWRlB7nCVa4zwSwvWOIdq6zzhQ1KDnXKJbd61KcBDcorn0Y0pnFN2F78mtaM5rSggIIK2YqWftlWq3kHoUs9tQ=='
_CONTINUOUS_BASE_DELTA='eNrNWtm2qjAM9fH8/9+cX/N2t7eKQIeMpcWjri6FJMQ02Rng5/F4pJg/+Z1ifiVExBTyEkN+vxbkT17+5Tfygrw88zfk5RmfyJ/Xt3zgXPKxjThFZImf5SX/Lf5Y9hNI+9mOpCCOG0dIO8e20MTVJXlijs5FLKi76Vxy6MTVT2o5zLS9oNCVNlUkHxo4iKOmc62GTV5cK3kGMa6bpNxDqxocB/rDQ5unsyE6/qCoM8MGu0ksktFqYlIDzNn9ih6XQhWoql9VR14YzEh+n/HGTLuNhbiLMaNGQGk6QXzwEA/o3EkuXYRhg2fPux2HuI2i4qe2pOv1G+pFJM4ajRqM5BSwCIFLSqdkP6iasb0VgLgioWEWWPKYo7MVjldy5Crms7RRUQLKUFwmntgidDznLSxxLiArrtcvM9Rw6izj5Lu1kNmwOd9eXh/f+GwwF32sYNliVJ1PCf0SlWzLRE9xtwzFQeZSZE44ONh/Tuts7Rdurr27Us0RoWF1J7KgbTElJR9o1f2C2ktaCyUaBcaBFtNBX83dau9ClqbwoYXX/5RqfXknbNjB4CzG/kiAdTBS+wZm63LJ9WZZw2ns9hplfuoAyj6SmoTtjemczUX0jB2mVYyOvUQL1wLYgKgcMH/6NmGkd4JD1zusi3PZwaVM1zoI2TZj9kjUEefKcICnK8ruwnWYJOwarrjHYzSdJd3NAEEV9ZQ/iKo5FjuWSaaj4zzobQaThdr0098FiBzg8B1Lm9EaOXVNw0+jMBApiue4tsJRqEtD8kYy6Cw0qcJztf8XasdWfHqIr18y17prms7BbUMO582IkwPCXlKZazzbxwtgHux++rFXYAGUQ6RppYFriMDmI0rnMNQCDBVt0yTfMxNcVwmqgchy4GtDGKJ6GAbfOly4KTbi/a430U/D1UlWsMd5YcY1dyjPnbkdGTAKUC6Lu/qFS48tjOSKQasFr2RxiEAqLvaIw9UhQReie24xiso2oXBfXCqoqEcU1oXQknSsgqC/iBlrBWHviQdTZfjKU1yKsRGvwuaqu/Dr7jsvD5cvPlonWwNWne+6o+3dwbYjwZKnOuuh3ZKprpDjm37+P0ahs9Q='
_OPENING_ENTRY_AFFINE='eNpVyn9M1HUcx/H3h19iEUPSanTRwYTvD973/TbGnGMzyHZQLrwUAkGQCoUDo/RowmwNUqgYblitUrHG+rHaWj/UVVj5Y7GIgH4Iuqg/aNxxP+R0HXgcx/e+9/303s0/6J/XHnvtWaEOsn9udeGienW2Ur3MdgW70Gy5OltD/iM4gGl512afUo+xyeAHmC297axQ97AXlj7FQmGGbGf3h45iYc6Cs0ZtZp6QHVuydWe1WkluxBvmeNdutZY9u9yIS5nxLk2pZ+eWm/HXBwynptSxHeFm/Mh0pytJ7WahsAnvyjjguqm8ysLkHfdNuH5W+th7KyZsuHfCdYL8+cqD+PA9e+dOKe+wjVomOtcfmDtNztFMeObutrluZYA56O9MPzx3VDnBOjQbfrvuT/ovse3abpTTDrprlUEW1Orwt9SD7rqYqzEptd1dT86NVONEyjW3XRliL0UKMDHlhrtN+Z69HCnEk3ds9RxSvmHHI03oWHvE037bg8lHPGbqhyI2tCZv9YpkjdywpoR8gaXqVuRJv3gLYm7AnKQx7yvKMLkdCxLHvV3KPrZJ78CdCePedxU7qyf74se9O5X97KTeg1/ETXnLlefYYfK2uCd8LUoZu0S+yEp9j5BHyDXsY1+xksFG9U7yl74pi8x8eh8eYgHfKQsyKfomCkwn57PN5AuQfn2AXEiugr3XSy2JrIIskkss30EluQhen7dZ9kEVuQ9e+59zLHboj/ZgLSzPp1jK4Xy0F3Mhzh/ADcCMXlwDH86vtg17QI9ekdu4GLBiGaw1huVpPh0AzIes2x7KexRs5L+4O9CaZ4WnjTHyUiA7Zo88w1MXAnIJ7Iq5d+Gi/Dh0UDPDMxbfkK3QbRTJTt53aw/ZYRSTxeBDcim8aGySR/lnQS49BgHDL43xM0GXlAWjxteSi/+7dFYywzrukJy8LdQphfiUUSTlg3+5ROLcT24ib5OGucjTpQbYEs6UJsnJUiP8EPaLk3wjd4qNsGXlPDmLj4gtkKC9T87lP4oOqNIc4u9c4lfELrislYsjvIybxQE4q68X/yZnxXxTmIn5HGyITgrzvJw8RP4qZkk8Bvuj/cIid5D7yQ5hhbdyt3AcnjeqhQR4i/9EbjWeFAJ8mjcJz4BhbF7lYrLGTUKYf8LTBIBIbAFW73+T7d65'
_CONTINUOUS_BADGE='eNrt079rE2EYB/DnK1JEVMol1rSUWkoIoc1d0zaWGoqGNneIg4N0kCJSOohDKaU428XJP8DJuVOHzp0cHZw6dnAUB0eRqiUx+eaGF457ee6HHSQcfDi+PPe+7/PcXfegO7yG1/D6b642XqPT6Xu129ehJVqhNdqgLRrQDbpJt+gO3adv6Fv6jr6nH+ghPaLH9IR+pJ/oZ3pKz+gX+pV+o9/pD/qTXtAOFWFfdIRepzcMbxmOKt1DUXzeB0Y+uPcj9Wa+bl05iEkCaxKX+9bcfs7oCvZz6qfhW8+prwwivcSdPFoTqHv0FT3qT+4nfGtt6wq+9Zz68+Q1jXbmaeT1beQ1jXXs91wL3YUjD2kTrzAm9/ESJbmHbUxIA1uYlCW86LlI63iOKVmg86GbPevUwzNMx1qjrpG4Rh7noGYuknvqxEv1rGvN7UlSvYT1bqoVXPXunnoX/TTchL1kXznd5LNMQz95N9c3a6/UfxvRyrlcd8n+B3kZ3vVseL+BGamGPkVZyniCikzjMapyF48wK5NooxY6gRY8GTcs0TtYRV3GDIvG/W0jKaKJRXFo0XCQFCKJrnLFskLBuoJj3b3Alc28kGqXuI6cnKYRt6amx7hn7Wv+i47S9ajf5TKnke8k9R0lnVu6Z/N6a86lfxumo1hGI/QmGliWa/CwIiOooSlXUMWqgArKeCAdqdAZtOTC8E/oFNZ6Sd/fhr8Mo8m5OklXea6o/AuWj+im'
_STAGE_DATA={
0:(120,574,(109, 66, 248, 286),'eNrt1P1Lk1EUB/DvV81WjkzTlNQxbI2ly8ayZWPNNR98WWNamYlZZi8i5qToPSIi+qNbL9TXu1l3+uRSBkI8P3245zz3nIfDvXcUx5DnC6YtCzIDv9av5FH57rdZmdtmOb75/RuOyWvSkTlXy/sc+DDF97JZfpBN8qNswCQ/ScjPnJRZOSWvKp+TmYppy7xMqc6mSVmQOWOD6TONRkyo7zQOYZxvZTMcvpY+jPElZ3BE9Z/LFtV/Jv0Y5VNe1zyucEO2qv66bFP9NdmOy1zlDXTgEldkJxJ8LLtwkQ95E924wGV5CnEuyR7EeFf24TwXOYsAznFBBhHl/IE6eMAOeP4XDnKOt9CPs5zlnIxUGUKYM7wtz8h5hBFiwXiaeS4ggiBzxoC8gwH0cZyLstcYlY6xx/Ke7GZWDsmMqycrdlkuIaZ42tjJFO/Ljn16wtW4TNbdZdlewzZPz7/4QB7f4Ygclgljq+UjJODncMW4HEELY1yRPg7JJA4zylXZZEzJiLHRcg1pgOFthow/YZuR/Xwif8h1+b2mQcssvukF2atF+bViyXJDfvF00ZG9ddCbp2fA5Ty4n48/97Ocd7uvW/lSjf12fmtd/Of1znjJ+u+iZanK+rwvdl33PtXzc8uXds3vHv8F8O1FLQ=='),
1:(240,759,(115, 68, 246, 284),'eNrt1ftLG0EQB/CZUoqUYK2IiKQhiIQgQUKQEEIIIQ1RY+2Dvt/0obZoW927Fin9u69BSvHrcnfZ29u9OyWl/pCfPuztzOzuXDbXpxzd4WPuw024TtdpwD/hFPwVjjfhhjQHj0MHcB1u0TT1+YfBG/A7b9MM9dg12mUR8x7sSGfhoe9XOEdtPuAHsCWdpyZ/4oewIV2gOu/6fuTHtEhr/N5gnmr8hp/CaqIF+Eq6avUFP4eVRIvw2YVcmTjxkvgSlvkJXIKPjJYUX9MyLfN9xRIt8V1+S2Uq8kBagO9ohfK84dtXrNAi9/gDXAjtwlXYiThvcAfOSauwrdlKdDbRGmymugtvwj04M3Hif7WhuEbTXA/9DHMR67DG+3BK2oBV2KRrXOEv8Kq0RVe4HPoNkrQNS9K/uPmBR9ShP/gHSLYoPZF2YSHUgcNz6ikK/7nQ5oOxq41H+T2Yl/7WPHsu4NBXjRPa/Gi9YByto9Y709V0/pH6Our+hoY42/yoD7fhrdh5hSXe0eaFoa/RvKB+cI7oOIh3tfhRHT3flqf3K/7+PMP+sseZ36/aR0/btzoW1jzzurZ19H07KfG2eo7lfGn7cGL7jt67tHOJlHUveg73HPcl7XzeWPqSLU9kzBtXPSelXlrdrH3O2i/3EvQ5Sz3T79nL0GfX8r0Y972MP4/ey1PXCcOJ'),
2:(360,972,(114, 67, 250, 285),'eNrt1PtLVEEUB/BzQsTKxNTEbDUzFRMRWxaJRUVtMVtMeiEWJuauVqTeR9Gb6M/eXSL67uzede7cmZ1Z7ZfAnz7MmXvOPO7M5KmNNvgX56md1vmnZAf8IcwLL8Lv8BL8JnwovExr/BV2wi+SV+Bn2EUP+JNwFa7DnLAbftS6Ah/RVVrmD7xBPbTEIeyFHuyjRT6G/fCQH8N54QBl+W3dA3id7vE+P6dBynBRcQ/egK9hitK8y5twlnc0DsFXcBhut+SM5BacjnkTvoAjcOvcc/8TR+GmcAq+hJMxb8NncAw+5W0apwl+AidonDd4hyZplNeFI5yHUzTMa7wLUzGn4apwkHO8BweEM3BFcUnjLPXzotECvAaLsM/BAt2FC5JpmEV/mnrgvqLab/quqHxf0NRp9r0aN/WbvvtXNlt/n8P6dfug7l+vJq6Oo8vX5VXH75baB5ShLp6DczDDb2AnfEdZ6uCZhu9pntp5ig9pgdr4DlykCzxZd4yPIPEEe7REf3DSjyDxKB/X25Feor1Mv+kW+7AiXEF7GPGa1XZF044MDG1PapeU/pLUb47fh0ONeKDEvbq+EjfNy0vUi76vGSrz9A3zU+uGyC+JujX9xPyjfrXeSTw+bvN1BMp46r7I85HXV22XY3nxfQwMeWFjXfG475gXKPlhYv7meiWHera8wPJ/kuvT/59m/0/932VNXnL/k/XKmrh5vOj8nYxTauHc2s6fS71SC/VC6z0wrc92/vT3OXA8L/6Zz19yHfF6OcRTZz7PQSKvVte33LvAUs/2btju+enva6v7crp9Vvtt6zLdV9/yzgaO/y10fC+DJnG39zQHU9K+ROuvxUND/CSvtXpqvykeSG3XemVLPbmuuj5TPXUepnnq9ussebr5tFLvtPvsWfJ8yzimeYZN/mvFIS903JfkeYmfS7me6Z5H74BLveQ5Mt+DipRvmkf0noTKO2XKC+vxvzSjh+Q='),
3:(496,1188,(115, 68, 246, 284),'eNrdlvtrE0EQx2dERKSEGEoIJQ0hlBJCKCGUEEIJpQ3F1vpCxRiq1j7SorXtPRDxDw9BRByvt7m9zc7tJpwP/OnDzN7Mzn53dpJduAf7+AV3A37Vci/gAuwG32XgAX4O6eNDyMIOugF7xEfErQQ+gRx08TrkJT6DRejgBT6HPLTxnFiAFg7xJSzBOh4FbMZYJL7FV8RGyLUYS8SDKfaJdRxILBP7+JpYIwp7oNhiXXAgsar4q2Fcki3vp/Nz69x3aVHdp2/5Hbfet/BX59BR1WfW80zXXSH7xYSDkAewAiv4lLgKFXyMb4hl3Md3UIUS7uEh1KCIO2Tf8BDqsIQ9fE8sEI9hDfK4iUfEAlG285J9DA1YxC6eBNzAU2KOKOwTyc5J60NiNuZvEjvkb5K/E9j3Q8p+eV39XuaZ5vusJs9Q+j4jxenyZSziuHqGlvWkfY6hIZ+Jatyp5fnkOjLYDv1tstfJbk14Di1YwCZ+IN4lfoQ23MEGXkAHbmM9ZA0/wQbcwipeQRcAV/Ey5C/7B3X6FWwSK3hN/C7xSrK/TVhGZ8ItYinGsUSXoUccJfjVdX/Kv03+ZWKPWCR/j/wRI7+Ii/zxdRG3TVxOjBtr8rqW+3H7RHGRf6TxR9Tv50r1jBPqcazPJ+pRddbHcfqb70fv91POZ8qr9p3H6Oka61H7iKtHfQeczvE6onzcfc+ni6nP1XXHEMfp7ITn5Po5YvI74XXmdOHymXSxrUOcK7l/on3E3DO9K7GPPp/PvH81n8jjTPouqjt+PnUOi7nBzWeun7k+Utfj/cD5PcNcnP/e9H2p1uHPOJ8dw1yfjhfrYr8b25+K0+viGc4n6vdZndXfqdnmKa9LPN40N3T9q6tD7SM/oX5d/zlzzlPP8F75e+Pe5ew629RnivMt79v0u+xZzlOzLty9patL2vlsfwddw+/x39B59I/obKOL7dyYV2d3xv+Zaev8u+7tT+iiewdeSu/8f+xnmzrU+J8xkzg2'),
}

OPENING_BADGE_POLYGON=((232.,12.),(400.,104.),(398.,293.),(238.,387.),(72.,293.),(71.,100.))
OPENING_BADGE_CANVAS=(469,400)
OPENING_ENTRY_FIRST_LOCAL_FRAME=35
OPENING_ENTRY_LAST_LOCAL_FRAME=120
FOURTH_OPENING_BADGE_DELAY=16
CONTINUOUS_BADGE_CENTER_X=235.
CONTINUOUS_BADGE_TAIL=(248,286,66)

def _i16(payload:str)->tuple[int,...]:
    raw=zlib.decompress(base64.b64decode(payload))
    return struct.unpack("<"+"h"*(len(raw)//2),raw)

@lru_cache(maxsize=1)
def _opening()->tuple[int,...]:
    v=_i16(_OPENING_X)
    if len(v)!=4*OPENING_END_FRAME: raise ValueError("bad opening table")
    return v

def opening_card_x(card_index:int,global_frame:int)->int|None:
    i,f=int(card_index),int(global_frame)
    if not 0<=i<4 or not 0<=f<OPENING_END_FRAME:return None
    v=_opening()[i*OPENING_END_FRAME+f]
    return None if v==_SENTINEL else v

@lru_cache(maxsize=1)
def _credits()->tuple[int,...]:
    v=_i16(_CREDITS_X)
    if len(v)!=OPENING_END_FRAME: raise ValueError("bad credits table")
    return v

def credits_x(global_frame:int)->int:
    return _credits()[max(0,min(OPENING_END_FRAME-1,int(global_frame)))]

@lru_cache(maxsize=1)
def _continuous_base()->tuple[int,...]:
    raw=zlib.decompress(base64.b64decode(_CONTINUOUS_BASE_DELTA))
    first=struct.unpack_from("<i",raw,0)[0]
    out=[first]
    for b in raw[4:]:
        d=b if b<128 else b-256
        out.append(out[-1]+d)
    expected=CONTENT_END_FRAME-CONTINUOUS_START_FRAME+1
    if len(out)!=expected: raise ValueError("bad conveyor table")
    return tuple(out)

def continuous_body_x(global_frame:int,card_index:int)->int|None:
    f,i=int(global_frame),int(card_index)
    if f<CONTINUOUS_START_FRAME or i<0:return None
    held=min(f,CONTENT_END_FRAME)
    return _continuous_base()[held-CONTINUOUS_START_FRAME]+i*SLOT_PITCH

@lru_cache(maxsize=1)
def _entry()->tuple[int,...]:
    return _i16(_OPENING_ENTRY_AFFINE)

def opening_entry_affine(local_frame:int)->tuple[float,float,float,float,float,float]|None:
    f=int(local_frame)
    if f<OPENING_ENTRY_FIRST_LOCAL_FRAME:return None
    if f>=OPENING_ENTRY_LAST_LOCAL_FRAME:return (1.,0.,0.,1.,0.,0.)
    o=(f-OPENING_ENTRY_FIRST_LOCAL_FRAME)*6
    a,b,c,d,tx,ty=_entry()[o:o+6]
    return a/10000.,b/10000.,c/10000.,d/10000.,tx/100.,ty/100.

@lru_cache(maxsize=4)
def _stage(card_index:int)->tuple[int,...]:
    return _i16(_STAGE_DATA[int(card_index)][3])

def opening_badge_stage(card_index:int,global_frame:int)->tuple[int,int,int,int]|None:
    i,f=int(card_index),int(global_frame)
    if i not in _STAGE_DATA:return None
    start,end,tail,_=_STAGE_DATA[i]
    if f<start:return None
    if f>end:return tail
    v=_stage(i);o=(f-start)*4
    return tuple(v[o:o+4])

@lru_cache(maxsize=1)
def _continuous_badge()->tuple[int,...]:
    return _i16(_CONTINUOUS_BADGE)

def continuous_badge_state(local_frame:int)->tuple[int,int,int]|None:
    f=int(local_frame)
    if f<0:return None
    if f>850:return CONTINUOUS_BADGE_TAIL
    v=_continuous_badge();o=f*3;w,h,y=v[o:o+3]
    return None if w==_SENTINEL else (w,h,y)

def card_start_frame(index:int)->int:
    i=int(index)
    if i<0:raise IndexError(i)
    if i<4:return (0,120,240,360)[i]
    return CONTINUOUS_START_FRAME+(i-4)*CONTINUOUS_STEP_FRAMES

def content_end_frame(card_count:int)->int:
    n=max(0,int(card_count))
    if n==0:return 0
    if n==CANONICAL_CARD_COUNT:return CONTENT_END_FRAME
    if n<=4:return (120,240,360,528)[n-1]
    return CONTINUOUS_START_FRAME+(n-4)*CONTINUOUS_STEP_FRAMES

def total_frame_count(card_count:int)->int:
    if card_count<=0:return 0
    return content_end_frame(card_count)+END_WIPE_FRAMES+END_RISE_FRAMES+END_HOLD_FRAMES+FADE_FRAMES+BLACK_TAIL_FRAMES
