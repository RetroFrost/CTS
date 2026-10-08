using System.Buffers.Binary;

namespace CubicalCompare.Core.Project;

public static class PcmAudioFade
{
    // Windows decodes only the small audible tail to 48 kHz stereo PCM16.
    // Apply the envelope to samples, rather than approximating it with volume steps.
    public static void Apply(byte[] wave, double startGain, double endGain, CancellationToken cancellationToken = default)
    {
        if (wave.Length < 12 || !wave.AsSpan(0, 4).SequenceEqual("RIFF"u8) || !wave.AsSpan(8, 4).SequenceEqual("WAVE"u8))
            throw new InvalidDataException("Fade audio is not a WAV file.");
        int channels = 0, blockAlign = 0, dataOffset = -1, dataLength = 0;
        for (var offset = 12; offset + 8 <= wave.Length;)
        {
            var size = BinaryPrimitives.ReadUInt32LittleEndian(wave.AsSpan(offset + 4, 4));
            if (size > int.MaxValue || (long)offset + 8 + size > wave.Length) throw new InvalidDataException("Truncated fade audio.");
            var body = offset + 8;
            if (wave.AsSpan(offset, 4).SequenceEqual("fmt "u8))
            {
                if (size < 16) throw new InvalidDataException("Invalid PCM format.");
                var format = BinaryPrimitives.ReadUInt16LittleEndian(wave.AsSpan(body, 2));
                channels = BinaryPrimitives.ReadUInt16LittleEndian(wave.AsSpan(body + 2, 2));
                blockAlign = BinaryPrimitives.ReadUInt16LittleEndian(wave.AsSpan(body + 12, 2));
                var bits = BinaryPrimitives.ReadUInt16LittleEndian(wave.AsSpan(body + 14, 2));
                if (format == 65534 && (size < 40 || !wave.AsSpan(body + 24, 16).SequenceEqual(new byte[]{1,0,0,0,0,0,16,0,128,0,0,170,0,56,155,113})))
                    throw new InvalidDataException("Unsupported extended PCM format.");
                if ((format != 1 && format != 65534) || bits != 16 || channels < 1 || blockAlign != channels * 2)
                    throw new InvalidDataException("Fade audio must be 16-bit PCM.");
            }
            else if (wave.AsSpan(offset, 4).SequenceEqual("data"u8)) { dataOffset = body; dataLength = (int)size; }
            var next = (long)body + size + (size & 1);
            if (next > int.MaxValue) throw new InvalidDataException("Invalid WAV chunk.");
            offset = (int)next;
        }
        if (dataOffset < 0 || blockAlign == 0 || dataLength == 0 || dataLength % blockAlign != 0)
            throw new InvalidDataException("Fade audio has no complete PCM samples.");
        if (!double.IsFinite(startGain) || !double.IsFinite(endGain)) throw new InvalidDataException("Invalid fade gain.");
        startGain = Math.Clamp(startGain, 0, 1); endGain = Math.Clamp(endGain, 0, 1);
        var frames = dataLength / blockAlign;
        for (var frame = 0; frame < frames; frame++)
        {
            if ((frame & 4095) == 0) cancellationToken.ThrowIfCancellationRequested();
            var gain = frames == 1 ? endGain : startGain + (endGain - startGain) * frame / (frames - 1d);
            for (var channel = 0; channel < channels; channel++)
            {
                var sample = wave.AsSpan(dataOffset + frame * blockAlign + channel * 2, 2);
                var value = BinaryPrimitives.ReadInt16LittleEndian(sample);
                BinaryPrimitives.WriteInt16LittleEndian(sample, (short)Math.Clamp(Math.Round(value * gain), short.MinValue, short.MaxValue));
            }
        }
    }
}
