#!/usr/bin/env node

// Makes this file a module, which top-level `await` below requires.
// oxlint-disable-next-line unicorn/require-module-specifiers
export {};

const commands = {
  dev: async () => (await import('../src/cli/dev.ts')).dev(),
  build: async () => (await import('../src/cli/build.ts')).build(),
  start: async () => (await import('../src/cli/start.ts')).start(process.argv.slice(3)),
  package: async () =>
    (await import('../src/cli/package.ts')).packageCommand(process.argv.slice(3)),
  upgrade: async () =>
    (await import('../src/cli/upgrade.ts')).upgradeCommandLine(process.argv.slice(3)),
  android: async () =>
    (await import('../src/cli/android.ts')).androidCommand(process.argv.slice(3)),
} as const;

const command = process.argv[2] as keyof typeof commands | undefined;
const run = command && commands[command];
if (!run) {
  console.error(`Usage: hashsome <${Object.keys(commands).join('|')}>`);
  process.exit(1);
}

await run();
