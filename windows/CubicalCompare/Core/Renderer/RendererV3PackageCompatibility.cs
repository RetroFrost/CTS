using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Legacy = CubicalCompare.Windows;

namespace CubicalCompare.Core.Renderer;

/// <summary>
/// Audio authored inside a Renderer v3 package. The byte payload stays renderer-owned;
/// export materializes it only for the duration of the MediaComposition mux step.
/// </summary>
public sealed record RendererEmbeddedAudio(string FileName, byte[] Data, double Volume, bool Loop);

/// <summary>
/// Bridges Renderer v3 source-component packages onto the frozen 3.0.301 evaluator without
/// changing that evaluator's established API v2/v3 behaviour. Source-component packages use
/// object-space placement (x/y + movement) and baked raster resources, while the legacy image
/// primitive expects local image coordinates and transform.* placement. Normalizing once at load
/// time keeps preview and export on the exact same deterministic frame path.
/// </summary>
internal static class RendererV3PackageCompatibility
{
    private static readonly HashSet<string> RasterResourceTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        "source-card-raster",
        "source-component-raster",
        "component-raster",
    };

    public static void Normalize(Legacy.RendererSpec spec)
    {
        var scene = spec.SceneV3;
        if (spec.RendererApi != 3 || spec.Engine != "scene-v3" || scene is null)
            return;

        NormalizeRasterResources(scene);
        NormalizeClipSyntax(scene);

        // Project-data renderers intentionally let the legacy evaluator lay out live project
        // content. The source-component contract instead owns every pixel and therefore needs
        // its authored object placement translated to the evaluator's transform convention.
        if (RendererOwnsScene(scene.Root))
            NormalizeRendererOwnedPlacement(scene);
    }

    public static RendererEmbeddedAudio? EmbeddedAudio(Legacy.RendererSpec spec)
    {
        var scene = spec.SceneV3;
        if (spec.RendererApi != 3 || scene is null || scene.Root.ValueKind != JsonValueKind.Object)
            return null;
        if (!scene.Root.TryGetProperty("audio", out var audio) || audio.ValueKind != JsonValueKind.Object)
            return null;
        if (!audio.TryGetProperty("source", out var sourceElement) || sourceElement.ValueKind != JsonValueKind.String)
            return null;

        var source = NormalizeAssetPath(sourceElement.GetString());
        if (source.Length == 0)
            return null;

        var asset = scene.Assets.FirstOrDefault(pair =>
            pair.Key.Equals(source, StringComparison.OrdinalIgnoreCase) ||
            pair.Key.Replace('\\', '/').EndsWith('/' + source, StringComparison.OrdinalIgnoreCase));
        if (asset.Value is null || asset.Value.Length == 0)
            return null;

        var volume = audio.TryGetProperty("volume", out var volumeElement) && volumeElement.TryGetDouble(out var parsedVolume)
            ? Math.Clamp(parsedVolume, 0, 1)
            : 1d;
        var loop = audio.TryGetProperty("loop", out var loopElement) &&
                   (loopElement.ValueKind == JsonValueKind.True ||
                    loopElement.ValueKind == JsonValueKind.String && bool.TryParse(loopElement.GetString(), out var parsedLoop) && parsedLoop);
        var fileName = Path.GetFileName(source);
        if (string.IsNullOrWhiteSpace(fileName))
            fileName = "renderer-audio.m4a";

        return new RendererEmbeddedAudio(fileName, asset.Value.ToArray(), volume, loop);
    }

    private static bool RendererOwnsScene(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("dataBinding", out var binding) ||
            binding.ValueKind != JsonValueKind.Object)
            return false;
        return binding.TryGetProperty("mode", out var mode) &&
               mode.ValueKind == JsonValueKind.String &&
               string.Equals(mode.GetString(), "renderer-owned", StringComparison.OrdinalIgnoreCase);
    }

    private static void NormalizeRasterResources(Legacy.RendererSceneV3 scene)
    {
        foreach (var id in scene.Resources.Keys.ToArray())
        {
            var resource = scene.Resources[id];
            if (resource.ValueKind != JsonValueKind.Object ||
                !resource.TryGetProperty("type", out var typeElement) ||
                typeElement.ValueKind != JsonValueKind.String ||
                !RasterResourceTypes.Contains(typeElement.GetString() ?? ""))
                continue;

            var node = JsonNode.Parse(resource.GetRawText()) as JsonObject;
            if (node is null)
                continue;
            node["type"] = "image";
            scene.Resources[id] = ToElement(node);
        }
    }

    private static void NormalizeRendererOwnedPlacement(Legacy.RendererSceneV3 scene)
    {
        for (var i = 0; i < scene.Objects.Count; i++)
        {
            var obj = scene.Objects[i];
            var properties = JsonNode.Parse(obj.Properties.GetRawText()) as JsonObject ?? new JsonObject();
            var changed = false;

            changed |= NormalizeAxis(scene, obj, properties, "x");
            changed |= NormalizeAxis(scene, obj, properties, "y");

            if (!changed)
                continue;

            scene.Objects[i] = new Legacy.RendererObjectV3
            {
                Id = obj.Id,
                Kind = obj.Kind,
                Frame = obj.Frame,
                Resource = obj.Resource,
                LifespanStart = obj.LifespanStart,
                LifespanEnd = obj.LifespanEnd,
                Properties = ToElement(properties),
                Raw = obj.Raw,
            };
        }
    }

    private static bool NormalizeAxis(
        Legacy.RendererSceneV3 scene,
        Legacy.RendererObjectV3 obj,
        JsonObject properties,
        string axis)
    {
        var basePlacement = TakePlacement(properties, axis);
        var movement = WinningSelectorProperty(scene, obj, $"movement.{axis}");

        if (basePlacement is null && movement is null)
            return false;

        JsonNode? transform;
        if (movement is null)
        {
            transform = basePlacement?.DeepClone();
        }
        else if (basePlacement is null)
        {
            transform = movement.DeepClone();
        }
        else if (TryNumber(basePlacement, out var offset))
        {
            transform = OffsetTrack(movement, offset);
        }
        else
        {
            // Two independently animated placement tracks cannot be losslessly summed by the
            // frozen evaluator. Preserve the object's authored placement instead of guessing.
            transform = basePlacement.DeepClone();
        }

        if (transform is not null)
            properties[$"transform.{axis}"] = transform;
        return true;
    }

    private static JsonNode? TakePlacement(JsonObject properties, string axis)
    {
        if (properties.TryGetPropertyValue(axis, out var direct) && direct is not null)
        {
            properties.Remove(axis);
            return direct;
        }

        var dotted = $"position.{axis}";
        if (properties.TryGetPropertyValue(dotted, out var dottedValue) && dottedValue is not null)
        {
            properties.Remove(dotted);
            return dottedValue;
        }

        if (properties["position"] is JsonObject position &&
            position.TryGetPropertyValue(axis, out var nested) && nested is not null)
        {
            position.Remove(axis);
            if (position.Count == 0)
                properties.Remove("position");
            return nested;
        }

        return null;
    }

    private static JsonNode? WinningSelectorProperty(
        Legacy.RendererSceneV3 scene,
        Legacy.RendererObjectV3 obj,
        string propertyPath)
    {
        JsonNode? winner = null;
        var specificity = int.MinValue;
        var order = int.MinValue;

        foreach (var selector in scene.Selectors)
        {
            if (!Matches(selector, obj))
                continue;
            if (selector.Specificity < specificity ||
                selector.Specificity == specificity && selector.SourceOrder < order)
                continue;

            var root = JsonNode.Parse(selector.Properties.GetRawText());
            var candidate = FindPath(root, propertyPath);
            if (candidate is null)
                continue;

            winner = candidate.DeepClone();
            if (winner is JsonObject descriptor && descriptor["timeline"] is null)
                descriptor["timeline"] = selector.Timeline;
            specificity = selector.Specificity;
            order = selector.SourceOrder;
        }

        return winner;
    }

    private static bool Matches(Legacy.RendererSelectorV3 selector, Legacy.RendererObjectV3 obj)
    {
        if (!string.Equals(selector.Kind, obj.Kind, StringComparison.Ordinal))
            return false;

        var every = selector.Conditions.FirstOrDefault(c => c.Key == "every" && c.Op == "=")?.Value;
        var from = selector.Conditions.FirstOrDefault(c => c.Key == "from" && c.Op == "=")?.Value;
        var to = selector.Conditions.FirstOrDefault(c => c.Key == "to" && c.Op == "=")?.Value;
        if (every is not null)
        {
            var step = Convert.ToInt32(every, CultureInfo.InvariantCulture);
            var start = from is null ? 0 : Convert.ToInt32(from, CultureInfo.InvariantCulture);
            var end = to is null ? int.MaxValue : Convert.ToInt32(to, CultureInfo.InvariantCulture);
            if (step <= 0 || obj.Frame < start || obj.Frame > end || (obj.Frame - start) % step != 0)
                return false;
        }

        foreach (var condition in selector.Conditions.Where(c => c.Key is not ("every" or "from" or "to")))
        {
            object? lhs = condition.Key switch
            {
                "frame" => obj.Frame,
                "id" => obj.Id,
                "kind" => obj.Kind,
                _ => RawValue(obj.Raw, condition.Key),
            };
            if (!Compare(lhs, condition.Op, condition.Value))
                return false;
        }

        return true;
    }

    private static object? RawValue(JsonElement raw, string key)
    {
        if (raw.ValueKind != JsonValueKind.Object || !raw.TryGetProperty(key, out var value))
            return null;
        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number when value.TryGetInt64(out var integer) => integer,
            JsonValueKind.Number when value.TryGetDouble(out var number) => number,
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => value.ToString(),
        };
    }

    private static bool Compare(object? lhs, string op, object rhs)
    {
        if (op == "=")
            return string.Equals(lhs?.ToString(), rhs.ToString(), StringComparison.Ordinal);
        if (op == "!=")
            return !string.Equals(lhs?.ToString(), rhs.ToString(), StringComparison.Ordinal);
        if (!double.TryParse(lhs?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var left) ||
            !double.TryParse(rhs.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var right))
            return false;
        return op switch
        {
            ">=" => left >= right,
            "<=" => left <= right,
            ">" => left > right,
            "<" => left < right,
            _ => false,
        };
    }

    private static JsonNode OffsetTrack(JsonNode track, double offset)
    {
        var clone = track.DeepClone();
        if (Math.Abs(offset) <= double.Epsilon)
            return clone;

        if (TryNumber(clone, out var scalar))
            return JsonValue.Create(scalar + offset)!;

        if (clone is not JsonObject descriptor)
            return clone;

        if (descriptor["value"] is JsonNode value && TryNumber(value, out var staticValue))
            descriptor["value"] = staticValue + offset;

        if (descriptor["dense"] is JsonArray dense)
        {
            OffsetNumericArray(dense, offset);
        }
        else if (descriptor["dense"] is JsonObject denseObject && denseObject["values"] is JsonArray values)
        {
            OffsetNumericArray(values, offset);
        }

        if (descriptor["track"] is JsonArray keys)
        {
            foreach (var key in keys)
            {
                if (key is JsonArray pair && pair.Count >= 2 && pair[1] is JsonNode trackValue && TryNumber(trackValue, out var number))
                    pair[1] = number + offset;
                else if (key is JsonObject keyed && keyed["value"] is JsonNode objectValue && TryNumber(objectValue, out number))
                    keyed["value"] = number + offset;
            }
        }

        return descriptor;
    }

    private static void OffsetNumericArray(JsonArray values, double offset)
    {
        for (var i = 0; i < values.Count; i++)
        {
            if (values[i] is JsonNode value && TryNumber(value, out var number))
                values[i] = number + offset;
        }
    }

    private static bool TryNumber(JsonNode node, out double value)
    {
        if (node is JsonValue scalar)
        {
            if (scalar.TryGetValue<double>(out value))
                return true;
            if (scalar.TryGetValue<long>(out var integer))
            {
                value = integer;
                return true;
            }
            if (scalar.TryGetValue<string>(out var text) &&
                double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out value))
                return true;
        }
        else if (node is JsonObject descriptor && descriptor["value"] is JsonNode staticValue)
        {
            return TryNumber(staticValue, out value);
        }

        value = 0;
        return false;
    }

    private static JsonNode? FindPath(JsonNode? root, string dottedPath)
    {
        if (root is not JsonObject objectRoot)
            return null;
        if (objectRoot[dottedPath] is JsonNode direct)
            return direct;

        JsonNode? current = objectRoot;
        foreach (var part in dottedPath.Split('.'))
        {
            if (current is not JsonObject currentObject || currentObject[part] is not JsonNode next)
                return null;
            current = next;
        }
        return current;
    }

    private static void NormalizeClipSyntax(Legacy.RendererSceneV3 scene)
    {
        for (var i = 0; i < scene.Objects.Count; i++)
        {
            var obj = scene.Objects[i];
            var properties = JsonNode.Parse(obj.Properties.GetRawText()) as JsonObject;
            if (properties?["clip"] is not JsonObject clip || clip["left"] is not null || clip["right"] is not null)
                continue;
            if (clip["x"] is not JsonNode xNode || clip["y"] is not JsonNode yNode ||
                clip["width"] is not JsonNode widthNode || clip["height"] is not JsonNode heightNode ||
                !TryNumber(xNode, out var x) || !TryNumber(yNode, out var y) ||
                !TryNumber(widthNode, out var width) || !TryNumber(heightNode, out var height))
                continue;

            clip["left"] = x;
            clip["top"] = y;
            clip["right"] = x + width;
            clip["bottom"] = y + height;
            clip.Remove("x");
            clip.Remove("y");
            clip.Remove("width");
            clip.Remove("height");

            scene.Objects[i] = new Legacy.RendererObjectV3
            {
                Id = obj.Id,
                Kind = obj.Kind,
                Frame = obj.Frame,
                Resource = obj.Resource,
                LifespanStart = obj.LifespanStart,
                LifespanEnd = obj.LifespanEnd,
                Properties = ToElement(properties),
                Raw = obj.Raw,
            };
        }
    }

    private static string NormalizeAssetPath(string? source) =>
        (source ?? "").Replace('\\', '/').TrimStart('.', '/');

    private static JsonElement ToElement(JsonNode node)
    {
        using var document = JsonDocument.Parse(node.ToJsonString());
        return document.RootElement.Clone();
    }
}
