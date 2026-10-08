using global::Windows.Storage;
using global::Windows.Media.Editing;
using global::Windows.Media.MediaProperties;
using global::Windows.Media.Transcoding;
using CubicalCompare.Core.Project;

namespace CubicalCompare;
public sealed partial class MainWindow
{
    internal async Task RunAudioSmokeAsync()
    {
        var saved = CurrentSoundtrackPaths().ToArray();
        var rootPath = Path.Combine(Path.GetTempPath(), "audio-smoke-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(rootPath);
        var folder = await StorageFolder.GetFolderFromPathAsync(rootPath);
        try
        {
            async Task<StorageFile> Wave(string name, int seconds)
            {
                var file = await folder.CreateFileAsync(name);
                using var stream = new MemoryStream(); using var writer = new BinaryWriter(stream);
                var count = 8000 * seconds;
                writer.Write(System.Text.Encoding.ASCII.GetBytes("RIFF")); writer.Write(36 + count * 2);
                writer.Write(System.Text.Encoding.ASCII.GetBytes("WAVEfmt ")); writer.Write(16); writer.Write((short)1);writer.Write((short)1);
                writer.Write(8000);writer.Write(16000);writer.Write((short)2);writer.Write((short)16);
                writer.Write(System.Text.Encoding.ASCII.GetBytes("data"));writer.Write(count * 2);
                for(var i=0;i<count;i++)writer.Write((short)(Math.Sin(i * 2 * Math.PI * 440 / 8000) * 1000));
                await FileIO.WriteBytesAsync(file,stream.ToArray());return file;
            }
            var first = await Wave("first.wav",1);var second = await Wave("second.wav",2);
            var invalid = await folder.CreateFileAsync("invalid.mp3");await FileIO.WriteTextAsync(invalid,"not audio");
            SetSoundtrackPaths([]);
            var failures = await ImportSoundtracksAsync([first,invalid,second]);
            if(failures.Count!=1 || CurrentSoundtrackPaths().Count!=2 || _soundtrackListPanel?.Children.Count!=2)throw new Exception("Audio import/partial failure list failed");
            MoveSoundtrack(1,-1);if(CurrentSoundtrackPaths()[0]!=second.Path)throw new Exception("Audio reorder failed");
            var project = new ComparisonProject { SoundtrackPaths=CurrentSoundtrackPaths().ToList() };
            var roundtrip = System.Text.Json.JsonSerializer.Deserialize<ComparisonProject>(System.Text.Json.JsonSerializer.Serialize(project))!;
            if(!roundtrip.SoundtrackPaths.SequenceEqual(CurrentSoundtrackPaths()))throw new Exception("Playlist persistence failed");
            var tracks = await BuildPlaylistTracksAsync([first.Path,second.Path],TimeSpan.FromSeconds(2),1,false,CancellationToken.None);
            if(tracks.Count!=2 || tracks[1].Delay!=TimeSpan.FromSeconds(1) || tracks[1].TrimTimeFromEnd!=TimeSpan.FromSeconds(1))throw new Exception("Sequential audio trim failed");
            var repeated=await BuildPlaylistTracksAsync([first.Path,second.Path],TimeSpan.FromSeconds(7),1,true,CancellationToken.None);
            if(repeated.Count!=5 || repeated[^1].Delay!=TimeSpan.FromSeconds(6))throw new Exception("Playlist repeat failed");
            if(!DescribeAudioCoverage(TimeSpan.FromSeconds(3),TimeSpan.FromSeconds(2),true).Contains("Warning") || !DescribeAudioCoverage(TimeSpan.FromSeconds(2),TimeSpan.FromSeconds(3),false).Contains("silent"))throw new Exception("Coverage warning failed");
            var composition = new MediaComposition();composition.Clips.Add(MediaClip.CreateFromColor(global::Windows.UI.Color.FromArgb(255,0,0,0),TimeSpan.FromSeconds(2)));
            foreach(var track in tracks)composition.BackgroundAudioTracks.Add(track);
            var output=await folder.CreateFileAsync("mux.mp4");var profile=MediaEncodingProfile.CreateMp4(VideoEncodingQuality.Qvga);
            if(await composition.RenderToFileAsync(output,MediaTrimmingPreference.Precise,profile)!=TranscodeFailureReason.None)throw new Exception("Playlist mux failed");
            var properties=await output.Properties.GetVideoPropertiesAsync();
            if(Math.Abs(properties.Duration.TotalSeconds-2)>.1)throw new Exception("Audio changed video duration");
            App.WriteLog("Audio playlist import, order, persistence, coverage and mux smoke passed.");
        }
        finally { SetSoundtrackPaths(saved); await folder.DeleteAsync(StorageDeleteOption.PermanentDelete); }
    }
}
