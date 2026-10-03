import { build } from 'esbuild';
import { readFile, writeFile, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { runInNewContext } from 'node:vm';
const assets = '../app/src/main/assets/codemode/';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const prelude = await readFile('vendor/prelude-source.ts');
if (hash(prelude) !== 'bd544106d5bd36543be4e5fc21128c9cd46b26856fb0b17931250b955f93c24a') {
  throw Error('The pi a276dabe5 prelude was modified; update the alignment baseline explicitly');
}
const bundled = await build({ entryPoints: ['vendor/prelude-source.ts'], bundle: true,
  format: 'cjs', platform: 'node', write: false });
const module = { exports: {} };
runInNewContext(bundled.outputFiles[0].text, { module, exports: module.exports });
await writeFile(assets + 'prelude.js', module.exports.PRELUDE_SOURCE);
await copyFile('vendor/PI-LICENSE', assets + 'PI-LICENSE');
await copyFile('../app/src/main/cpp/quickjs/LICENSE', assets + 'QUICKJS-LICENSE');
const manifest = { sources: {}, assets: {} };
for (const name of ['vendor/prelude-source.ts', 'package.json', 'package-lock.json', 'build.mjs']) {
  manifest.sources[name] = hash(await readFile(name));
}
for (const name of ['prelude.js', 'PI-LICENSE', 'QUICKJS-LICENSE']) manifest.assets[name] = hash(await readFile(assets + name));
await writeFile(assets + 'manifest.json', JSON.stringify(manifest, null, 2) + '\n');
