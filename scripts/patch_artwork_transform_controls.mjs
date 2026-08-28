import fs from 'node:fs';

const path = 'android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt';
let source = fs.readFileSync(path, 'utf8');

if (source.includes('Move, zoom, rotate and crop the selected artwork.')) {
  console.log('Artwork transform controls are already present.');
  process.exit(0);
}

const startMarker = '                        Text("Artwork scale ';
const endMarker = '                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {';
const start = source.indexOf(startMarker);
const end = source.indexOf(endMarker, start);
if (start < 0 || end < 0 || end <= start) {
  throw new Error('Could not locate the existing artwork-scale controls.');
}

const replacement = String.raw`                        Text("Artwork transform", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Move, zoom, rotate and crop the selected artwork.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Scale \${"%.2f".format(card.imageScale)}×")
                        androidx.compose.material3.Slider(
                            value = card.imageScale.toFloat().coerceIn(0.10f, 6f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageScale = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0.10f..6f,
                        )
                        Text("Horizontal \${card.imageX.roundToInt()} px")
                        androidx.compose.material3.Slider(
                            value = card.imageX.toFloat().coerceIn(-600f, 600f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageX = it.toDouble()), onProjectChange)
                            },
                            valueRange = -600f..600f,
                        )
                        Text("Vertical \${card.imageY.roundToInt()} px")
                        androidx.compose.material3.Slider(
                            value = card.imageY.toFloat().coerceIn(-800f, 800f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageY = it.toDouble()), onProjectChange)
                            },
                            valueRange = -800f..800f,
                        )
                        Text("Rotation \${card.imageRotation.roundToInt()}°")
                        androidx.compose.material3.Slider(
                            value = card.imageRotation.toFloat().coerceIn(-180f, 180f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageRotation = it.toDouble()), onProjectChange)
                            },
                            valueRange = -180f..180f,
                        )
                        Text("Crop left \${(card.imageCropLeft * 100).roundToInt()}%")
                        androidx.compose.material3.Slider(
                            value = card.imageCropLeft.toFloat().coerceIn(0f, 0.45f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageCropLeft = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0f..0.45f,
                        )
                        Text("Crop right \${(card.imageCropRight * 100).roundToInt()}%")
                        androidx.compose.material3.Slider(
                            value = card.imageCropRight.toFloat().coerceIn(0f, 0.45f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageCropRight = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0f..0.45f,
                        )
                        Text("Crop top \${(card.imageCropTop * 100).roundToInt()}%")
                        androidx.compose.material3.Slider(
                            value = card.imageCropTop.toFloat().coerceIn(0f, 0.45f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageCropTop = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0f..0.45f,
                        )
                        Text("Crop bottom \${(card.imageCropBottom * 100).roundToInt()}%")
                        androidx.compose.material3.Slider(
                            value = card.imageCropBottom.toFloat().coerceIn(0f, 0.45f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageCropBottom = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0f..0.45f,
                        )
                        OutlinedButton(
                            onClick = {
                                updateCard(
                                    project,
                                    selectedCard,
                                    card.copy(
                                        imageX = 0.0,
                                        imageY = 0.0,
                                        imageScale = 1.0,
                                        imageRotation = 0.0,
                                        imageCropLeft = 0.0,
                                        imageCropTop = 0.0,
                                        imageCropRight = 0.0,
                                        imageCropBottom = 0.0,
                                    ),
                                    onProjectChange,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset artwork transform")
                        }
`;

source = source.slice(0, start) + replacement + source.slice(end);
fs.writeFileSync(path, source);
console.log('Patched artwork transform controls.');
