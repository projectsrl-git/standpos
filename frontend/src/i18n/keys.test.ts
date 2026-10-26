import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import it_ from './it.json';

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

function lookup(key: string): unknown {
  return key
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], it_);
}

describe('Italian translations', () => {
  it('define every literal key used with t()', () => {
    const missing: string[] = [];
    for (const file of sourceFiles(join(process.cwd(), 'src'))) {
      for (const match of readFileSync(file, 'utf8').matchAll(/\bt\('([a-zA-Z0-9_.]+)'/g)) {
        const key = match[1] ?? '';
        if (typeof lookup(key) !== 'string') missing.push(`${file}: ${key}`);
      }
    }
    expect(missing).toEqual([]);
  });
});
