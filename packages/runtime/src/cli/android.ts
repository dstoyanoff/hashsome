import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const USAGE = `Usage: hashsome android <build|deploy|provision|uninstall|icons> [options]

Builds the project's native Android app and puts it on the displays listed in devices.json.

Commands:
  build       Build the release APK (./gradlew -p android assembleRelease)
  deploy      Install the APK on each device and hand it its settings (server, dashboard, ...)
  provision   deploy, plus the one-time permissions and kiosk set-up for a device
  uninstall   Have the app remove its own device admin, then uninstall it (a device admin blocks pm uninstall,
              and adb cannot remove one that is not a test admin). The app's kiosk module handles the
              dev.hashsome.REMOVE_ADMIN broadcast; an app built without withKiosk cannot be uninstalled this
              way while an admin is active. A device with "home": true prints a reminder to choose its home
              app again: the previous one is not known.
  icons       Convert an icon pack's SVGs to drawables and a Kotlin object (hashsome android icons --help)

Options (build, deploy, provision, uninstall):
  --device <id>   Only this device from devices.json
  --home          provision: also make the app the device's home screen
  --dry-run       Print every command instead of running it

devices.json (project root):
  [{ "id": "bathroom", "host": "10.1.1.50", "profile": "shelly-jenna",
     "dashboard": "bathroom", "server": "http://10.1.1.2:3000", "mqtt": "mqtt://10.1.1.2",
     "home": true }]
  profile: shelly-stargate | shelly-jenna | generic. dashboard, server, mqtt and home are optional.
  host is ip or ip:port (port 5555 if omitted); IPv6 is not supported.
`;

export const PROFILES = ['shelly-stargate', 'shelly-jenna', 'generic'] as const;
export type Profile = (typeof PROFILES)[number];

export interface Device {
  id: string;

  /** `ip` or `ip:port` (5555 if omitted). IPv6 is not supported. */
  host: string;
  profile: Profile;
  dashboard?: string;
  server?: string;
  mqtt?: string;
  home?: boolean;
}

export const APK = 'android/app/build/outputs/apk/release/app-release.apk';
const PROVISION_ACTION = 'dev.hashsome.PROVISION';
const REMOVE_ADMIN_ACTION = 'dev.hashsome.REMOVE_ADMIN';
const ACTIVITY = 'dev.hashsome.ui.DashboardActivity';
const ADMIN = 'dev.hashsome.kiosk.HashsomeDeviceAdmin';
export const HOME_ALIAS = 'dev.hashsome.kiosk.HomeActivityAlias';
const SHELLY = 'cloud.shelly.stargate';

export type Command = string[];

// --- Flags ---

/** Splits `args` into positional words, `--flag value` / `--flag=value` options and bare `--flag`s. Unknown flags throw. */
export function parseFlags(
  args: string[],
  known: { values?: string[]; flags?: string[] },
): { positional: string[]; values: Record<string, string>; flags: Set<string> } {
  const positional: string[] = [];
  const values: Record<string, string> = {};
  const flags = new Set<string>();

  for (let i = 0; i < args.length; i++) {
    const arg = args[i]!;
    if (!arg.startsWith('--')) {
      positional.push(arg);
      continue;
    }

    const [name = '', inline] = arg.slice(2).split(/=(.*)/s, 2);
    if (known.flags?.includes(name)) {
      flags.add(name);
    } else if (known.values?.includes(name)) {
      const value = inline ?? args[++i];
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`--${name} needs a value`);
      }

      values[name] = value;
    } else {
      throw new Error(`Unknown option "${arg}"`);
    }
  }

  return { positional, values, flags };
}

// --- Gradle ---

/** The string assigned to `key` in a Gradle Kotlin script, e.g. `namespace = "com.example"`. */
export function readGradleValue(text: string, key: string): string | undefined {
  return new RegExp(`^\\s*${key}\\s*=\\s*"([^"]+)"`, 'm').exec(text)?.[1];
}

function applicationId(root: string): string {
  const file = join(root, 'android', 'app', 'build.gradle.kts');
  if (!existsSync(file)) {
    throw new Error('No android/app/build.gradle.kts here. Run this from your project.');
  }

  const id = readGradleValue(readFileSync(file, 'utf8'), 'applicationId');
  if (!id) {
    throw new Error('No `applicationId = "..."` in android/app/build.gradle.kts.');
  }

  return id;
}

// --- devices.json ---

const OPTIONAL_STRINGS = ['dashboard', 'server', 'mqtt'] as const;

/** Checks the parsed devices.json and returns it typed. Every problem names the device and the field. `host` is `ip[:port]` (default port 5555); IPv6 is not supported. */
export function validateDevices(json: unknown): Device[] {
  if (!Array.isArray(json)) {
    throw new Error('devices.json must be an array of devices.');
  }

  const ids = new Set<string>();
  return json.map((raw: unknown, index) => {
    const where = (raw as { id?: unknown } | null)?.id
      ? `devices.json: "${(raw as { id: string }).id}"`
      : `devices.json: entry ${index + 1}`;

    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
      throw new Error(`${where} must be an object.`);
    }

    const device = raw as Record<string, unknown>;
    const known = new Set(['id', 'host', 'profile', 'home', ...OPTIONAL_STRINGS]);
    const extra = Object.keys(device).find((key) => !known.has(key));
    if (extra) {
      throw new Error(`${where}: unknown field "${extra}".`);
    }

    for (const field of ['id', 'host'] as const) {
      if (typeof device[field] !== 'string' || !device[field]) {
        throw new Error(`${where}: "${field}" is required and must be a non-empty string.`);
      }
    }

    if (!PROFILES.includes(device.profile as Profile)) {
      throw new Error(`${where}: "profile" must be one of ${PROFILES.join(', ')}.`);
    }

    for (const field of OPTIONAL_STRINGS) {
      if (device[field] !== undefined && (typeof device[field] !== 'string' || !device[field])) {
        throw new Error(`${where}: "${field}" must be a non-empty string when set.`);
      }
    }

    if (device.home !== undefined && typeof device.home !== 'boolean') {
      throw new Error(`${where}: "home" must be true or false.`);
    }

    const id = device.id as string;
    if (ids.has(id)) {
      throw new Error(`devices.json: id "${id}" appears twice.`);
    }

    ids.add(id);
    return device as unknown as Device;
  });
}

function loadDevices(root: string, only: string | undefined): Device[] {
  const file = join(root, 'devices.json');
  if (!existsSync(file)) {
    throw new Error('No devices.json here. Run this from your project.');
  }

  let json: unknown;
  try {
    json = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`devices.json is not valid JSON: ${(error as Error).message}`, {
      cause: error,
    });
  }

  const devices = validateDevices(json);
  if (!only) {
    return devices;
  }

  const match = devices.filter((device) => device.id === only);
  if (match.length === 0) {
    throw new Error(
      `No device "${only}" in devices.json (have: ${devices.map((d) => d.id).join(', ') || 'none'}).`,
    );
  }

  return match;
}

// --- Command planners ---

/** A word for a shell on the device: single-quoted when it has anything but safe characters. */
export function shellQuote(word: string): string {
  return /^[\w@%+=:,./-]+$/.test(word) ? word : `'${word.replace(/'/g, `'\\''`)}'`;
}

/** The adb serial for a device: `host` is `ip[:port]`, on port 5555 unless it names one (IPv6 is not supported). */
export const serialOf = (device: Device) =>
  device.host.includes(':') ? device.host : `${device.host}:5555`;

const shell = (device: Device, ...words: string[]): Command => [
  'adb',
  '-s',
  serialOf(device),
  'shell',
  words.map(shellQuote).join(' '),
];

/** `./gradlew assembleRelease`, run inside `android/` (the wrapper lives there, in the monorepo and in a consumer alike). */
export const buildCommands = (): Command[] => [['./gradlew', 'assembleRelease']];

const connect = (device: Device): Command => ['adb', 'connect', serialOf(device)];
const install = (device: Device, apk: string): Command => [
  'adb',
  '-s',
  serialOf(device),
  'install',
  '-r',
  '-d',
  apk,
];

/** The settings broadcast: only the extras the device sets. Addressed to the app (-p) so Android 8+ delivers it to the manifest receiver; `--include-stopped-packages` because a freshly installed app is in the stopped state. */
function provisionBroadcast(device: Device, pkg: string): Command {
  const extras = [
    ['server', device.server],
    ['dashboard', device.dashboard],
    ['device', device.id],
    ['profile', device.profile],
    ['mqtt', device.mqtt],
  ].flatMap(([key, value]) => (value ? ['--es', key!, value] : []));

  return shell(
    device,
    'am',
    'broadcast',
    '--include-stopped-packages',
    '-a',
    PROVISION_ACTION,
    '-p',
    pkg,
    ...extras,
  );
}

const start = (device: Device, pkg: string): Command =>
  shell(device, 'am', 'start', '-n', `${pkg}/${ACTIVITY}`);

/** Install the APK, hand the device its settings, start the app. */
export function deployCommands(device: Device, apk: string, pkg: string): Command[] {
  return [
    connect(device),
    install(device, apk),
    provisionBroadcast(device, pkg),
    start(device, pkg),
  ];
}

/** deploy plus the one-time set-up: permissions, battery whitelist, device admin (Jenna, generic), Jenna's own kiosk host put out of the way, and the home screen if asked. The permissions come before the app starts. */
export function provisionCommands(
  device: Device,
  apk: string,
  pkg: string,
  home: boolean = device.home === true,
): Command[] {
  const admin = device.profile !== 'shelly-stargate';
  return [
    connect(device),
    install(device, apk),
    ...['WRITE_SETTINGS', 'SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS'].map((op) =>
      shell(device, 'appops', 'set', pkg, op, 'allow'),
    ),
    shell(device, 'dumpsys', 'deviceidle', 'whitelist', `+${pkg}`),
    ...(admin ? [shell(device, 'dpm', 'set-active-admin', `${pkg}/${ADMIN}`)] : []),
    ...(device.profile === 'shelly-jenna'
      ? [
          shell(device, 'appops', 'set', SHELLY, 'SYSTEM_ALERT_WINDOW', 'deny'),
          shell(device, 'am', 'force-stop', SHELLY),
        ]
      : []),
    provisionBroadcast(device, pkg),
    start(device, pkg),
    ...(home
      ? [
          shell(device, 'pm', 'enable', `${pkg}/${HOME_ALIAS}`),
          shell(device, 'cmd', 'package', 'set-home-activity', `${pkg}/${HOME_ALIAS}`),
        ]
      : []),
  ];
}

/**
 * Take the app off a device. A device admin that is not a test one cannot be removed from outside
 * (`dpm remove-active-admin` fails, and `pm uninstall` fails while the admin is active), so the app is
 * asked to remove its own: the broadcast, a moment for it to run, then the uninstall.
 */
export function uninstallCommands(device: Device, pkg: string): Command[] {
  return [
    connect(device),
    shell(
      device,
      'am',
      'broadcast',
      '--include-stopped-packages',
      '-a',
      REMOVE_ADMIN_ACTION,
      '-p',
      pkg,
    ),
    shell(device, 'sleep', '2'),
    // Once more after a further pause if the first attempt fails: the admin may not be gone yet.
    shell(device, 'sh', '-c', `pm uninstall ${pkg} || (sleep 2 && pm uninstall ${pkg})`),
  ];
}

/** What a person has to do after `uninstall` on a device that had the app as its home screen: the previous home app is not known. */
export const HOME_NOTE =
  "The app was this display's home screen: choose the home app again by hand (Settings > Apps > Default apps > Home app, or the launcher chooser after pressing Home).";

// --- Running ---

/** A command as a line you can paste into a shell (each word single-quoted when it needs it, so `adb shell '<string>'` survives). */
const show = (command: Command) => `$ ${command.map(shellQuote).join(' ')}`;

const CONNECT_FAILED = /failed to connect|cannot connect|unable to connect/i;

export type Exec = (
  command: Command,
  cwd: string,
) => {
  status: number | null;
  error?: string | undefined;

  /** What the command printed, for the commands whose output decides if they worked (`adb connect`). */
  output?: string | undefined;
};

const spawnExec: Exec = ([program, ...rest], cwd) => {
  // `adb connect` exits 0 even when it cannot connect, so its output is read (and still shown).
  const capture = program === 'adb' && rest[0] === 'connect';
  const result = spawnSync(program!, rest, {
    cwd,
    stdio: capture ? ['inherit', 'pipe', 'pipe'] : 'inherit',
    encoding: 'utf8',
  });

  const output = capture ? `${result.stdout ?? ''}${result.stderr ?? ''}` : undefined;
  if (output) {
    process.stdout.write(output);
  }

  return {
    status: result.status,
    output,
    error: result.error?.message.includes('ENOENT')
      ? `${program} is not installed or not on the PATH`
      : result.error?.message,
  };
};

/** Runs the commands in order (or just prints them on a dry run), stopping at the first failure with that command printed. */
export function runCommands(
  commands: Command[],
  cwd: string,
  options: { dryRun: boolean; exec?: Exec | undefined },
): void {
  for (const command of commands) {
    console.log(show(command));
    if (options.dryRun) {
      continue;
    }

    const result = (options.exec ?? spawnExec)(command, cwd);
    if (result.error || result.status !== 0 || CONNECT_FAILED.test(result.output ?? '')) {
      throw new Error(
        `Failed: ${show(command)}\n${result.error ?? (result.status === 0 ? result.output?.trim() : `exit status ${result.status}`)}. Nothing after it was run.`,
      );
    }
  }
}

/** `hashsome android`: build, deploy and provision the native app. */
export async function androidCommand(
  args: string[],
  root: string = resolve(process.cwd()),
  exec?: Exec,
): Promise<void> {
  const [sub, ...rest] = args;
  if (!sub || sub === '--help' || sub === '-h' || sub === 'help') {
    console.log(USAGE);
    return;
  }

  if (sub === 'icons') {
    (await import('./android-icons.ts')).iconsCommand(rest, root);
    return;
  }

  if (!['build', 'deploy', 'provision', 'uninstall'].includes(sub)) {
    throw new Error(`Unknown command "${sub}"\n\n${USAGE}`);
  }

  if (rest.includes('--help') || rest.includes('-h')) {
    console.log(USAGE);
    return;
  }

  const { positional, values, flags } = parseFlags(rest, {
    values: ['device'],
    flags: ['dry-run', 'home'],
  });

  if (positional.length > 0) {
    throw new Error(`Unexpected argument "${positional[0]}"\n\n${USAGE}`);
  }

  if (flags.has('home') && sub !== 'provision') {
    throw new Error('--home only applies to provision.');
  }

  if (values.device !== undefined && sub === 'build') {
    throw new Error('--device does not apply to build (one APK serves every device).');
  }

  const dryRun = flags.has('dry-run');
  if (sub === 'build') {
    const android = join(root, 'android');
    if (!dryRun && !existsSync(join(android, 'gradlew'))) {
      throw new Error(
        `No Gradle wrapper at ${join(android, 'gradlew')}. Run this from your project.`,
      );
    }

    runCommands(buildCommands(), android, { dryRun, exec });
    console.log(`\nAPK: ${APK}`);
    return;
  }

  const devices = loadDevices(root, values.device);
  const pkg = applicationId(root);
  const apk = join(root, APK);
  if (sub !== 'uninstall' && !dryRun && !existsSync(apk)) {
    throw new Error(`No APK at ${APK}. Run \`hashsome android build\` first.`);
  }

  // A device that fails stops there; the others still get their turn.
  const failed: string[] = [];
  for (const device of devices) {
    console.log(`\n# ${device.id} (${serialOf(device)}, ${device.profile})`);
    const plan =
      sub === 'uninstall'
        ? uninstallCommands(device, pkg)
        : sub === 'deploy'
          ? deployCommands(device, apk, pkg)
          : provisionCommands(device, apk, pkg, flags.has('home') || device.home === true);

    try {
      runCommands(plan, root, { dryRun, exec });
      if (sub === 'uninstall' && device.home === true) {
        console.log(`${device.id}: ${HOME_NOTE}`);
      }
    } catch (error) {
      console.error((error as Error).message);
      failed.push(device.id);
    }
  }

  if (failed.length > 0) {
    throw new Error(
      `${sub} failed for ${failed.length} of ${devices.length} device(s): ${failed.join(', ')}`,
    );
  }
}
