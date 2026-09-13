namespace CubicalCompare.Windows.Graphics;

/// <summary>
/// Keeps the preserved legacy CubicalCompare.Windows namespace from shadowing
/// the Windows SDK projection when the new WinUI shell sizes its AppWindow.
/// Delete this bridge if the legacy compatibility core is moved to a separate assembly.
/// </summary>
internal readonly struct SizeInt32
{
    private readonly int _width;
    private readonly int _height;

    public SizeInt32(int width, int height)
    {
        _width = width;
        _height = height;
    }

    public static implicit operator global::Windows.Graphics.SizeInt32(SizeInt32 value)
        => new(value._width, value._height);
}
