// Baut Backend-JAR und Frontend, falls nötig. SKIP_BUILD=1 überspringt das (z. B. in der CI, wenn schon gebaut wurde).
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const isWin = process.platform === 'win32';

function run(cmd, args, cwd) {
  console.log(`> ${cmd} ${args.join(' ')}   (${path.relative(root, cwd) || '.'})`);
  execFileSync(cmd, args, { cwd, stdio: 'inherit', shell: isWin });
}

if (process.env.SKIP_BUILD === '1') {
  console.log('SKIP_BUILD=1: Build übersprungen');
  process.exit(0);
}

run(isWin ? 'mvnw.cmd' : './mvnw', ['-q', '-B', '-DskipTests', 'package'], root);
const target = path.join(root, 'target');
if (!existsSync(target) || !readdirSync(target).some((f) => /^group-purse-.*\.jar$/.test(f))) {
  throw new Error('Backend-JAR nicht gefunden');
}
// npm ci nur, wenn noch nichts installiert ist (sonst würde ein laufender Dev-Server zerstört)
if (!existsSync(path.join(root, 'frontend', 'node_modules'))) {
  run('npm', ['ci', '--no-audit', '--no-fund'], path.join(root, 'frontend'));
}
run('npm', ['run', 'build'], path.join(root, 'frontend'));
