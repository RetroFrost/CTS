import fs from 'node:fs';
const path = 'android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt';
let source = fs.readFileSync(path, 'utf8');
const before = source;
source = source.replaceAll('\\${', '${');
if (source === before) {
  console.log('No escaped Kotlin interpolation markers found.');
  process.exit(0);
}
fs.writeFileSync(path, source);
console.log('Fixed Kotlin interpolation markers.');
