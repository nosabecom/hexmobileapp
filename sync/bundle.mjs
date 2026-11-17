import {build} from 'esbuild';
import {realpath, stat, writeFile} from 'node:fs/promises';
import path from 'node:path';

// Compile the actual NewEra config; never execute desktop code in this process.
const [configPath, outputPath] = process.argv.slice(2);
if (!configPath || !outputPath) throw new Error('Expected config path and output path');
const entryPoint = await realpath(configPath);
const root = path.dirname(entryPoint);
const buildResult = await build({
  absWorkingDir: root,
  entryPoints: [entryPoint], bundle: true, write: false, format: 'iife',
  globalName: 'hexMobileConfig', platform: 'browser', target: 'es2022',
  sourcemap: false, logLevel: 'silent',
  plugins: [{
    name: 'hex-mobile-config',
    setup(build) {
      build.onResolve({filter: /^@hex\/commands$/}, () => ({path: 'commands', namespace: 'hex'}));
      build.onLoad({filter: /.*/, namespace: 'hex'}, () => ({contents: 'export const defineHexConfig = config => config;', loader: 'js'}));
      build.onResolve({filter: /.*/}, async args => {
        if (args.kind !== 'entry-point' && !args.path.startsWith('.'))
          throw new Error('Only relative imports and @hex/commands are supported');
      });
      build.onLoad({filter: /.*/, namespace: 'file'}, async args => {
        const resolved = await realpath(args.path);
        const relative = path.relative(root, resolved);
        if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Config imports must stay inside the Hex config folder');
        if ((await stat(resolved)).size > 256 * 1024) throw new Error('Config module too large');
      });
    }
  }],
});
const code = buildResult.outputFiles[0].text;
if (Buffer.byteLength(code) > 256 * 1024) throw new Error('Bundled transformations are too large');
await writeFile(outputPath, code, 'utf8');
