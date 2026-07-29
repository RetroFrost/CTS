#pragma once

// Win32 RECT members are LONG while the wheel-crop code uses an integer zero.
// Supply the exact mixed-type overload expected by that expression on MSVC.
namespace std {
constexpr long max(int left, long right) noexcept {
    return static_cast<long>(left) < right ? right : static_cast<long>(left);
}
}
