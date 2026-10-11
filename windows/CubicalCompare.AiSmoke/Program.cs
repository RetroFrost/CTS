using CubicalCompare.Core.Project;
using CubicalCompare.Core.Project.Ai;

static void Check(bool condition, string message)
{
    if (!condition) throw new Exception("AI smoke test failed: " + message);
}

var drafts = AiResponseParser.ParseCards(
    """{"cards":[{"title":"Day 1","badgeValue":"1","badgeUnit":"day","description":"The first day."},{"title":"Day 2","badgeValue":"2","badgeUnit":"days","description":"The second day."}]}""",
    2);
Check(drafts.Count == 2 && drafts[1].Title == "Day 2", "structured card parsing");
try
{
    AiResponseParser.ParseCards("""{"cards":[{"title":"One","description":"Only one."}]}""", 2);
    throw new Exception("Wrong card count was accepted.");
}
catch (InvalidDataException) { }

var pages = AiResponseParser.ParseIconPages(
    """{"icons":[{"index":0,"url":"https://www.flaticon.com/free-icon/sun_12345"},{"index":1,"url":"https://evil.example/free-icon/moon_456"},{"index":2,"url":"https://cdn-icons-png.flaticon.com/512/12/12345.png"}]}""",
    3);
Check(pages.Count == 1 && pages[0].Contains("sun_12345"), "free-icon URL allowlist");

var project = new ComparisonProject
{
    Cards =
    [
        new ComparisonCard { Id = "test-card", Title = drafts[0].Title, Description = drafts[0].Description }
    ],
    ArtworkSources =
    [
        new ProjectArtworkSource
        {
            CardId = "test-card",
            PageUrl = "https://www.flaticon.com/free-icon/sun_12345",
        },
        new ProjectArtworkSource
        {
            CardId = "missing-card",
            PageUrl = "https://www.flaticon.com/free-icon/moon_456",
        }
    ]
};
var path = Path.Combine(Path.GetTempPath(), "cc-ai-smoke-" + Guid.NewGuid().ToString("N") + ".ccproject");
try
{
    await ProjectFileService.SaveAsync(project, path);
    var restored = await ProjectFileService.LoadAsync(path);
    Check(restored.Cards.Count == 1, "project card roundtrip");
    Check(restored.ArtworkSources.Count == 1, "asset source persists and stale card source is filtered");
    Check(restored.ArtworkSources[0].LicenseStatus == "NeedsReview", "license not falsely approved");
    Check(restored.Cards[0].Description == "The first day.", "card content persists");
}
finally
{
    if (File.Exists(path)) File.Delete(path);
    if (File.Exists(path + ".bak")) File.Delete(path + ".bak");
}
Console.WriteLine("V5 AI smoke tests passed.");
