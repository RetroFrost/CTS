namespace CubicalCompare.Updates;

internal static class StringComparisonCompat
{
    // .NET exposes StartsWith(char), but the StringComparison overload accepts a string.
    // Keep the path-safety call expressive without allocating unless this overload is used.
    public static bool StartsWith(this string value, char prefix, StringComparison comparison)
        => value.Length > 0 && value[..1].Equals(prefix.ToString(), comparison);
}
