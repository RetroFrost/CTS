from __future__ import annotations

"""Compatibility doorway for Cubical Compare Canary.

All actual frame rendering lives in canary_renderer.py and all measured motion
lives in canary_reference.py.  The helpers below remain only for old callers
and tests; the Canary renderer never uses them to animate the source model.
"""

from .canary_renderer import FrameRenderer, clamp, lerp, smoothstep


# Legacy helper API retained so existing integrations do not break while the
# renderer itself is a clean replacement.
_BODY_PROGRESS_KEYFRAMES = (
    (0.000, 0.000), (0.033, 0.000), (0.083, 0.019), (0.166, 0.101),
    (0.250, 0.300), (0.333, 0.515), (0.416, 0.653), (0.500, 0.746),
    (0.583, 0.813), (0.666, 0.864), (0.750, 0.901), (0.833, 0.931),
    (0.916, 0.954), (1.000, 0.971), (1.083, 0.983), (1.166, 0.994),
    (1.250, 0.998), (1.333, 1.000),
)

_OPENING_BADGE_FRAME_KEYFRAMES = (
    (35, 0.493398, -0.085460, -0.331527, 1.161492, -150.997648, -39.870887),
    (40, 0.592169, -0.078765, -0.283855, 1.188568, -125.999331, -19.155194),
    (44, 0.653847, -0.078786, -0.293365, 1.172057, -105.417801, -5.568381),
    (48, 0.696013, -0.090493, -0.273790, 1.202435, -82.436779, -19.898183),
    (52, 0.721844, -0.076480, -0.273350, 1.200237, -60.473294, -18.705733),
    (56, 0.729938, -0.029255, -0.225309, 1.111702, -45.263002, -10.894799),
    (60, 0.774691, -0.031915, -0.189815, 1.114362, -38.958629, -14.476950),
    (64, 0.817901, -0.031915, -0.168210, 1.114362, -34.569740, -17.032506),
    (68, 0.859568, -0.039894, -0.121914, 1.087766, -30.989953, -17.599882),
    (72, 0.898148, -0.037234, -0.114198, 1.069149, -29.794326, -14.969267),
    (76, 0.922840, -0.026596, -0.101852, 1.053191, -28.178487, -11.698582),
    (80, 0.945988, -0.029255, -0.067901, 1.058511, -23.818558, -15.196217),
    (84, 0.964506, -0.018617, -0.064815, 1.042553, -23.258274, -10.258865),
    (88, 0.979938, -0.023936, -0.038580, 1.039894, -19.316194, -13.121158),
    (92, 0.987654, -0.021277, -0.023148, 1.029255, -16.898345, -10.625887),
    (96, 1.001543, -0.013298, -0.021605, 1.021277, -15.978132, -8.657210),
    (100, 1.010802, -0.013298, -0.006173, 1.005319, -14.144799, -6.608747),
    (104, 1.013889, -0.007979, -0.006173, 1.010638, -11.420213, -6.661939),
    (108, 1.010802, 0.002660, -0.015432, 1.015957, -8.304374, -3.548463),
    (112, 1.021605, -0.010638, 0.009259, 1.005319, -4.449173, -6.219858),
    (116, 1.024691, -0.010638, 0.020062, 0.986702, -2.171395, -1.311466),
    (120, 1.000000, 0.000000, 0.000000, 1.000000, 0.000000, 0.000000),
)

_POST_BADGE_FALL_FRAME_OFFSETS = (
    (650, -430.0), (670, -410.0), (679, -386.0), (680, -381.0),
    (682, -381.0), (684, -341.0), (686, -321.0), (688, -300.0),
    (690, -279.0), (692, -266.0), (694, -246.0), (696, -226.0),
    (698, -206.0), (700, -187.0), (702, -175.0), (704, -156.0),
    (706, -138.0), (708, -121.0), (710, -105.0), (712, -94.0),
    (714, -80.0), (716, -66.0), (718, -53.0), (720, -41.0),
    (722, -34.0), (724, -25.0), (726, -17.0), (728, -10.0),
    (730, -5.0), (732, -2.0), (734, 0.0),
)


def _sample_scalar(points: tuple[tuple[float, float], ...], value: float) -> float:
    if value <= points[0][0]: return points[0][1]
    if value >= points[-1][0]: return points[-1][1]
    for (x0,y0),(x1,y1) in zip(points,points[1:]):
        if value <= x1:
            p=smoothstep((value-x0)/max(1e-9,x1-x0))
            return lerp(y0,y1,p)
    return points[-1][1]


def body_progress(local_time: float) -> float:
    return _sample_scalar(_BODY_PROGRESS_KEYFRAMES, max(0.0, local_time))


def _sample_affine(frame: float) -> tuple[float,float,float,float,float,float]:
    keys=_OPENING_BADGE_FRAME_KEYFRAMES
    if frame <= keys[0][0]: return keys[0][1:]
    if frame >= keys[-1][0]: return keys[-1][1:]
    for a,b in zip(keys,keys[1:]):
        if frame <= b[0]:
            p=(frame-a[0])/max(1e-9,b[0]-a[0])
            return tuple(lerp(a[i],b[i],p) for i in range(1,7))
    return keys[-1][1:]


def badge_entry_affine(age: float) -> tuple[float,float,float,float,float,float]:
    frame=35.0+clamp(age/2.9)*85.0
    return _sample_affine(frame)


def post_badge_fall_affine(age: float) -> tuple[float,float,float,float,float,float]:
    frame=650.0+max(0.0,age)*(103.0/2.25)
    keys=_POST_BADGE_FALL_FRAME_OFFSETS
    if frame <= keys[0][0]: y=keys[0][1]
    elif frame >= keys[-1][0]: y=keys[-1][1]
    else:
        y=keys[-1][1]
        for a,b in zip(keys,keys[1:]):
            if frame <= b[0]:
                y=lerp(a[1],b[1],(frame-a[0])/max(1e-9,b[0]-a[0]));break
    return 1.0,0.0,0.0,1.0,0.0,y
