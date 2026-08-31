#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererImportActivity.kt"
text = path.read_text()
old = '''        sourceName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Text(spec.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${spec.id} • by ${spec.author}", style = MaterialTheme.typography.bodySmall)
        Text("${spec.engine} • ${spec.precisionMode}")
        Text("API ${spec.rendererApi} • ${spec.referenceWidth}×${spec.referenceHeight} @ ${spec.referenceFps} FPS")
        if (spec.canonicalFrameCount > 0) Text("${spec.canonicalFrameCount} canonical frames", style = MaterialTheme.typography.bodySmall)
        if (spec.canonicalCardCount > 0) Text("${spec.canonicalCardCount} canonical cards", style = MaterialTheme.typography.bodySmall)
        Text("SHA-256 ${pending.sha256}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(
            pending.report.summary(),
            fontWeight = FontWeight.SemiBold,
            color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
'''
new = '''        sourceName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Text(spec.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val installedVersion = BuildConfig.VERSION_NAME
        Text(
            if (pending.report.compatible) {
                "Ready for Cubical Compare $installedVersion"
            } else {
                "Can't use this renderer on $installedVersion • requires ${spec.minAppVersion}+"
            },
            fontWeight = FontWeight.SemiBold,
            color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text("By ${spec.author} • ${spec.engine} • ${spec.precisionMode}", style = MaterialTheme.typography.bodySmall)
        Text("${spec.referenceWidth}×${spec.referenceHeight} @ ${spec.referenceFps} FPS • renderer API ${spec.rendererApi}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
'''
if new not in text:
    if text.count(old) != 1:
        raise SystemExit(f"renderer import UX source changed; expected 1 match, got {text.count(old)}")
    text = text.replace(old, new, 1)
text = text.replace('Text("Pre-activation preview", fontWeight = FontWeight.SemiBold)', 'Text("Preview", fontWeight = FontWeight.SemiBold)')
path.write_text(text)
print("Applied concise renderer compatibility UX")
