namespace CubicalCompare.Core.Project;

public sealed class ComparisonProject
{
    public string Name { get; set; } = "Untitled comparison";
    public int Width { get; set; } = 1920;
    public int Height { get; set; } = 1080;
    public int Fps { get; set; } = 60;
    public List<ComparisonCard> Cards { get; } = [];
}

public sealed class ComparisonCard
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "Untitled";
    public string Value { get; set; } = "";
    public string Description { get; set; } = "";
    public string ImagePath { get; set; } = "";
}
