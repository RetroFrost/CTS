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
            var fadeFiles = new List<string>();
            try
            {
                if (!new ComparisonProject().SoundtrackFadeOut || _audioFadeOutCheckBox?.IsChecked != true) throw new Exception("Audio fade default is off");
                _audioFadeOutCheckBox.IsChecked = false;
                if (_soundtrackFadeOut) throw new Exception("Fade toggle did not disable");
                _audioFadeOutCheckBox.IsChecked = true;
                if (!_soundtrackFadeOut) throw new Exception("Fade toggle did not enable");
                var off = new ComparisonProject { SoundtrackFadeOut = false, Cards = [new()] };
                var offPath = Path.Combine(folder.Path, "fade-off.ccproject");
                await ProjectFileService.SaveAsync(off, offPath);
                if ((await ProjectFileService.LoadAsync(offPath)).SoundtrackFadeOut) throw new Exception("Fade toggle persistence failed");
                foreach (var scenario in new[]{(Seconds:1d,Repeat:false),(Seconds:2d,Repeat:false),(Seconds:7d,Repeat:true),(Seconds:5d,Repeat:false)})
                {
                    var schedule = await BuildPlaylistTracksAsync([first.Path,second.Path], TimeSpan.FromSeconds(scenario.Seconds), 1, scenario.Repeat, CancellationToken.None);
                    var audibleEnd = schedule.Max(t=>t.Delay+t.TrimmedDuration);
                    var faded = await FadePlaylistEndingAsync(schedule,[first.Path,second.Path],fadeFiles,CancellationToken.None);
                    if (Math.Abs((faded.Max(t=>t.Delay+t.TrimmedDuration)-audibleEnd).TotalSeconds)>.05) throw new Exception("Fade changed audible duration");
                    var lastWave = File.ReadAllBytes(fadeFiles[^1]);
                    var lastSample = System.Buffers.Binary.BinaryPrimitives.ReadInt16LittleEndian(lastWave.AsSpan(lastWave.Length-2));
                    if(Math.Abs((int)lastSample)>1) throw new Exception("Fade does not finish at silence");
                }
                // A longer compressed source exercises nonzero seeking and a video cutoff.
                var longWave = await Wave("long.wav",10);
                var compressed = await folder.CreateFileAsync("compressed.m4a");
                var compression = await new MediaTranscoder().PrepareFileTranscodeAsync(longWave,compressed,MediaEncodingProfile.CreateM4a(AudioEncodingQuality.High));
                if(!compression.CanTranscode) throw new Exception("Could not create compressed audio fixture");
                await compression.TranscodeAsync();
                var longTracks = await BuildPlaylistTracksAsync([compressed.Path],TimeSpan.FromSeconds(5),1,false,CancellationToken.None);
                var finalTracks = await FadePlaylistEndingAsync(longTracks,[compressed.Path],fadeFiles,CancellationToken.None);
                var fadedComposition = new MediaComposition();fadedComposition.Clips.Add(MediaClip.CreateFromColor(global::Windows.UI.Color.FromArgb(255,0,0,0),TimeSpan.FromSeconds(5)));
                foreach(var track in finalTracks)fadedComposition.BackgroundAudioTracks.Add(track);
                var fadedOutput = await folder.CreateFileAsync("faded.mp4");
                if(await fadedComposition.RenderToFileAsync(fadedOutput,MediaTrimmingPreference.Fast,profile)!=TranscodeFailureReason.None)throw new Exception("Faded audio mux failed");
                var decoded = await folder.CreateFileAsync("decoded.wav");
                var decodeProfile=MediaEncodingProfile.CreateWav(AudioEncodingQuality.High);decodeProfile.Audio=AudioEncodingProperties.CreatePcm(48000,2,16);
                var decode=await new MediaTranscoder().PrepareFileTranscodeAsync(fadedOutput,decoded,decodeProfile);
                if(!decode.CanTranscode)throw new Exception("Could not verify exported fade audio");await decode.TranscodeAsync();
                var samples = File.ReadAllBytes(decoded.Path);
                // Compare PCM energy near the beginning and final 100 ms of the actual export.
                double Energy(int begin,int count)
                {
                    double sum=0;for(int i=begin;i<begin+count;i+=2){var value=System.Buffers.Binary.BinaryPrimitives.ReadInt16LittleEndian(samples.AsSpan(i,2));sum+=(double)value*value;}return sum/(count/2);
                }
                var last=Energy(samples.Length-19200,19200);var beginning=Energy(40000,19200);
                if(last>=beginning*.02)throw new Exception("Exported audio did not smoothly fade to silence");
                App.WriteLog("Audio fade defaults, toggle persistence, early/cutoff/repeated endings and decoded export energy smoke passed.");
            }
            finally { foreach(var path in fadeFiles)TryDeleteExport(path); }
            App.WriteLog("Audio playlist import, order, persistence, coverage and mux smoke passed.");
        }
        finally { SetSoundtrackPaths(saved); await folder.DeleteAsync(StorageDeleteOption.PermanentDelete); }
    }
}
