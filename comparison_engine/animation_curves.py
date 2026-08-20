"""Comprehensive animation easing curves and physics helpers."""

import math

def clamp(val: float, min_v: float = 0.0, max_v: float = 1.0) -> float:
    return max(min_v, min(max_v, val))

def ease_linear(t: float) -> float:
    return clamp(t)

def ease_out_cubic(t: float) -> float:
    t = clamp(t)
    return 1.0 - math.pow(1.0 - t, 3)

def ease_in_cubic(t: float) -> float:
    t = clamp(t)
    return math.pow(t, 3)

def ease_in_out_cubic(t: float) -> float:
    t = clamp(t)
    if t < 0.5:
        return 4.0 * t * t * t
    return 1.0 - math.pow(-2.0 * t + 2.0, 3) / 2.0

def ease_out_back(t: float, overshoot: float = 1.4) -> float:
    """Overshoot spring curve (great for badges dropping and cards popping)."""
    t = clamp(t)
    c1 = overshoot
    c3 = c1 + 1.0
    return 1.0 + c3 * math.pow(t - 1.0, 3) + c1 * math.pow(t - 1.0, 2)

def ease_out_bounce(t: float) -> float:
    """Bounce easing curve."""
    t = clamp(t)
    n1 = 7.5625
    d1 = 2.75
    if t < 1.0 / d1:
        return n1 * t * t
    elif t < 2.0 / d1:
        t -= 1.5 / d1
        return n1 * t * t + 0.75
    elif t < 2.5 / d1:
        t -= 2.25 / d1
        return n1 * t * t + 0.9375
    else:
        t -= 2.625 / d1
        return n1 * t * t + 0.984375

def bell_wiggle(t: float, frequency: float = 6.0) -> float:
    """Oscillating rotation angle in degrees for the notification bell ring."""
    t = clamp(t)
    decay = math.exp(-4.0 * t)
    return math.sin(t * math.pi * frequency) * 18.0 * decay
