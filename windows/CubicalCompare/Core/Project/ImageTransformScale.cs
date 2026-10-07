namespace CubicalCompare.Core.Project;

/// <summary>Shared artwork scale validation; imported and edited scales have no authored size cap.</summary>
public static class ImageTransformScale
{
    public static double Normalize(double value) => double.IsFinite(value) && value >= 0 ? value : 1;
}
