import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
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
  --system        provision / uninstall, Shelly displays only: install the app as a system app (adb root,
                  /system/priv-app/Hashsome, a reboot), or remove that copy. For the X1, whose stock app
                  force-stops every non-system app at boot. Needs a userdebug image (the X2i is a user
                  build). Later updates are a plain deploy, which also refreshes
                  the system copy (no reboot).
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
  withInstall: boolean = true,
): Command[] {
  const admin = device.profile !== 'shelly-stargate';
  return [
    connect(device),
    ...(withInstall ? [install(device, apk)] : []),
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
export function uninstallCommands(device: Device, pkg: string, system: boolean = false): Command[] {
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
    // A system app cannot be uninstalled (only its /data update can): the copy in priv-app goes next (uninstallSystem).
    shell(
      device,
      'sh',
      '-c',
      `pm uninstall ${pkg} || (sleep 2 && pm uninstall ${pkg})${system ? ' || true' : ''}`,
    ),
  ];
}

// --- System install (Shelly displays) ---
//
// The procedure (adb root, remount /system, a directory in /system/priv-app, push, permissions and label, a
// reboot so the package scanner picks it up) is the one ShellyElevate's tools/install-privapp.sh uses
// (Apache-2.0, see android/NOTICE). Rewritten here; the device-specific extras of that script are not taken.

export const SYSTEM_DIR = '/system/priv-app/Hashsome';
export const SYSTEM_APK = `${SYSTEM_DIR}/Hashsome.apk`;
const SYSTEM_PROFILES: Profile[] = ['shelly-stargate', 'shelly-jenna'];
const REMOUNT_RW = 'mount -o rw,remount /system || mount -o rw,remount /';
const REMOUNT_RO = 'mount -o ro,remount /system || mount -o ro,remount /';

/** The room /system must have beyond the APK itself, in KB. */
export const SYSTEM_SPARE_KB = 1024;

/** Throws unless `--system` makes sense for this device: only the Shelly displays. */
export function assertSystemProfile(device: Device): void {
  if (!SYSTEM_PROFILES.includes(device.profile)) {
    throw new Error(
      `${device.id}: --system is for Shelly displays; a tablet keeps the normal install.`,
    );
  }
}

/** True for an image `adb root` works on: `ro.build.type` is userdebug, or `ro.debuggable` is 1. */
export const isRootable = (buildType: string, debuggable: string): boolean =>
  buildType.trim() === 'userdebug' || debuggable.trim() === '1';

/** Free space in KB on the last row of `df -k` output (the Available column), or undefined if it cannot be read. */
export function parseFreeKb(df: string): number | undefined {
  const rows = df
    .split('\n')
    .map((row) => row.trim().split(/\s+/))
    .filter((cols) => cols.length >= 4 && /^\d+$/.test(cols[1]!) && /^\d+$/.test(cols[3]!));

  const row = rows[rows.length - 1];
  return row ? Number(row[3]) : undefined;
}

/** Whether `needKb` fits in `freeKb`: the APK (rounded up) plus SYSTEM_SPARE_KB. */
export const hasRoomFor = (apkBytes: number, freeKb: number): boolean =>
  freeKb >= Math.ceil(apkBytes / 1024) + SYSTEM_SPARE_KB;

/** The `versionCode=N` of `dumpsys package` output, or undefined. */
export function parseVersionCode(dumpsys: string): number | undefined {
  const match = /versionCode=(\d+)/.exec(dumpsys);
  return match ? Number(match[1]) : undefined;
}

/** The `versionCode='N'` of `aapt dump badging` output, or undefined. */
export function parseAaptVersionCode(badging: string): number | undefined {
  const match = /versionCode='(\d+)'/.exec(badging);
  return match ? Number(match[1]) : undefined;
}

/** Whether `pm path` says the package is installed outside /system (a /data/app copy). */
export const isDataInstall = (pmPath: string): boolean => /^package:\/data\//m.test(pmPath);

/** Whether a `dumpsys package | grep flags` line carries the SYSTEM flag. */
export const hasSystemFlag = (flags: string): boolean => /\bSYSTEM\b/.test(flags);

/**
 * Whether a /data copy has to be uninstalled before the system copy goes in: it is there and its versionCode
 * is lower than the pushed one (a same-signature update only layers over the system copy when it is not
 * older). With either version unknown the copy is left, and the package scanner sorts it out on boot.
 */
export const shouldUninstallDataCopy = (
  installed: number | undefined,
  pushed: number | undefined,
): boolean => installed !== undefined && pushed !== undefined && installed < pushed;

/** A shell command line given whole (pipes, `||`): the device's shell parses it, so its words are not quoted one by one. */
const shellLine = (device: Device, line: string): Command => adbOn(device, 'shell', line);

const adbOn = (device: Device, ...words: string[]): Command => [
  'adb',
  '-s',
  serialOf(device),
  ...words,
];

/** The stages of a system provision. `read` ones decide whether to go on; the rest change the device. */
export interface SystemStages {
  /** Is the image rootable? */
  image: Command[];

  /** adbd as root; over the network adbd restarts and the connection must be made again. */
  root: Command[];

  /** Free space on /system, and whether (and which version of) the package is installed. */
  inspect: Command[];

  /** Remount /system read-write; the writes; remount read-only; reboot. */
  rw: Command;
  write: Command[];
  ro: Command;
  reboot: Command;

  /** After the reboot: wait for the device, then the settings and permissions. */
  afterReboot: Command[];
  provision: Command[];

  /** `dumpsys package <pkg> | grep flags`. */
  verify: Command;
}

const imageCommands = (device: Device): Command[] => [
  connect(device),
  shell(device, 'getprop', 'ro.build.type'),
  shell(device, 'getprop', 'ro.debuggable'),
];

const rootAgain = (device: Device): Command[] => [
  adbOn(device, 'root'),
  connect(device),
  adbOn(device, 'wait-for-device'),
];

export function systemProvisionStages(
  device: Device,
  apk: string,
  pkg: string,
  home: boolean = device.home === true,
): SystemStages {
  assertSystemProfile(device);
  return {
    image: imageCommands(device),
    root: rootAgain(device),
    inspect: [
      shell(device, 'df', '-k', '/system'),
      shell(device, 'pm', 'path', pkg),
      shellLine(device, `dumpsys package ${pkg} | grep versionCode`),
    ],
    rw: shellLine(device, REMOUNT_RW),
    write: [
      shell(device, 'mkdir', '-p', SYSTEM_DIR),
      adbOn(device, 'push', apk, SYSTEM_APK),
      shell(device, 'chmod', '755', SYSTEM_DIR),
      shell(device, 'chmod', '644', SYSTEM_APK),
      shell(device, 'chown', 'root:root', SYSTEM_DIR, SYSTEM_APK),
      shellLine(
        device,
        `if command -v restorecon >/dev/null 2>&1; then restorecon -R ${SYSTEM_DIR}; fi`,
      ),
    ],
    ro: shellLine(device, REMOUNT_RO),
    // The package manager only learns of the app in the boot scan, so the permissions below wait for it.
    reboot: adbOn(device, 'reboot'),
    afterReboot: [connect(device), adbOn(device, 'wait-for-device')],
    provision: provisionCommands(device, apk, pkg, home, false),
    verify: shellLine(device, `dumpsys package ${pkg} | grep flags`),
  };
}

/** The removal of the system copy (adb root and the image check come before, the usual uninstall steps too): remount, delete, remount read-only, reboot. */
export function systemRemoveStages(device: Device) {
  assertSystemProfile(device);
  return {
    image: imageCommands(device),
    root: rootAgain(device),
    rw: shellLine(device, REMOUNT_RW),
    write: [shell(device, 'rm', '-rf', SYSTEM_DIR)],
    ro: shellLine(device, REMOUNT_RO),
    reboot: adbOn(device, 'reboot'),
  };
}

/** What a person has to do after `uninstall` on a device that had the app as its home screen: the previous home app is not known. */
export const HOME_NOTE =
  "The app was this display's home screen: choose the home app again by hand (Settings > Apps > Default apps > Home app, or the launcher chooser after pressing Home).";

// --- Running ---

/** A command as a line you can paste into a shell (each word single-quoted when it needs it, so `adb shell '<string>'` survives). */
const show = (command: Command) => `$ ${command.map(shellQuote).join(' ')}`;

const CONNECT_FAILED = /failed to connect|cannot connect|unable to connect|cannot run as root/i;

/** The adb verb of a command (`connect`, `root`, `shell`, `push`, ...), past the `-s <serial>` pair. */
const adbVerb = (command: Command) => (command[1] === '-s' ? command[3] : command[1]) ?? '';
const CAPTURED = new Set(['connect', 'root', 'shell']);

export type Exec = (
  command: Command,
  cwd: string,

  /** Give up on the command after this long (a hung adb); unset means no limit. */
  timeoutMs?: number,
) => {
  status: number | null;
  error?: string | undefined;

  /** What the command printed, for the commands whose output decides if they worked (`adb connect`). */
  output?: string | undefined;
};

const spawnExec: Exec = ([program, ...rest], cwd, timeoutMs) => {
  // `adb connect` and `adb root` exit 0 even when they fail, and the shell commands' output decides the
  // system install's checks, so their output is read (and still shown).
  const capture = program === 'adb' && CAPTURED.has(adbVerb([program, ...rest]));
  const result = spawnSync(program!, rest, {
    cwd,
    stdio: capture ? ['inherit', 'pipe', 'pipe'] : 'inherit',
    encoding: 'utf8',
    ...(timeoutMs === undefined ? {} : { timeout: timeoutMs }),
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
    const checked = ['connect', 'root'].includes(adbVerb(command));
    if (
      result.error ||
      result.status !== 0 ||
      (checked && CONNECT_FAILED.test(result.output ?? ''))
    ) {
      throw new Error(
        `Failed: ${show(command)}\n${result.error ?? (result.status === 0 ? result.output?.trim() : `exit status ${result.status}`)}. Nothing after it was run.`,
      );
    }
  }
}

// --- System provision, run ---

export type Sleep = (ms: number) => void;
const sleepSync: Sleep = (ms) => {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
};

/** The newest `aapt` under the Android SDK's build-tools, if there is one (it reads the APK's versionCode). */
function findAapt(): string | undefined {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  if (!sdk) {
    return undefined;
  }

  const tools = join(sdk, 'build-tools');
  if (!existsSync(tools)) {
    return undefined;
  }

  return readdirSync(tools)
    .toSorted((a, b) => b.localeCompare(a, undefined, { numeric: true }))
    .map((version) => join(tools, version, 'aapt'))
    .find((file) => existsSync(file));
}

/** The versionCode of the APK, read with aapt; undefined without an SDK that has it. */
function apkVersionCode(apk: string): number | undefined {
  const aapt = findAapt();
  if (!aapt) {
    return undefined;
  }

  const result = spawnSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8' });
  return result.status === 0 ? parseAaptVersionCode(result.stdout) : undefined;
}

/** Runs `command` and returns what it printed; a failure throws as in runCommands. */
function readOutput(command: Command, cwd: string, exec: Exec): string {
  console.log(show(command));
  const result = exec(command, cwd);
  if (result.error || result.status !== 0) {
    throw new Error(
      `Failed: ${show(command)}\n${result.error ?? `exit status ${result.status}`}. Nothing after it was run.`,
    );
  }

  return result.output ?? '';
}

const POLL_TIMEOUT_MS = 10_000;

/** Polls until the device answers and says its boot is done (5 minutes at most). */
function waitForBoot(device: Device, cwd: string, exec: Exec, sleep: Sleep): void {
  // The old boot can still answer for a moment after `adb reboot`.
  sleep(15_000);
  const deadline = Date.now() + 300_000;
  let last = 'no answer yet';
  while (Date.now() < deadline) {
    exec(connect(device), cwd, POLL_TIMEOUT_MS);
    const booted = exec(shell(device, 'getprop', 'sys.boot_completed'), cwd, POLL_TIMEOUT_MS);
    if (booted.status === 0 && booted.output?.trim().endsWith('1')) {
      return;
    }

    last = booted.error ?? `exit status ${booted.status}, output "${booted.output?.trim() ?? ''}"`;
    sleep(3000);
  }

  throw new Error(`${device.id} did not finish booting within 5 minutes (last: ${last}).`);
}

function printRoot(commands: Command[], cwd: string): void {
  runCommands([commands[0]!], cwd, { dryRun: true });
  console.log('# (2 s for adbd to restart)');
  runCommands(commands.slice(1), cwd, { dryRun: true });
}

/** Whether the image behind `image` (connect, then the two getprops) is rootable. */
function imageIsRootable(image: Command[], cwd: string, exec: Exec): boolean {
  const [first, buildType, debuggable] = image;
  runCommands([first!], cwd, { dryRun: false, exec });
  return isRootable(readOutput(buildType!, cwd, exec), readOutput(debuggable!, cwd, exec));
}

/** `adb root` and the reconnect (adbd restarts); throws when adbd will not run as root. */
function becomeRoot(device: Device, rootSteps: Command[], cwd: string, exec: Exec, sleep: Sleep) {
  try {
    runCommands([rootSteps[0]!], cwd, { dryRun: false, exec });
    sleep(2000);
    runCommands(rootSteps.slice(1), cwd, { dryRun: false, exec });
  } catch (error) {
    throw new Error(
      `${device.id}: adb root failed, so /system cannot be written.\n${(error as Error).message}`,
      { cause: error },
    );
  }
}

/** The image check and `adb root`, before anything on the device is touched: throws on a user build or when adbd will not run as root. */
function requireRoot(
  device: Device,
  image: Command[],
  rootSteps: Command[],
  cwd: string,
  exec: Exec,
  sleep: Sleep,
): void {
  if (!imageIsRootable(image, cwd, exec)) {
    throw new Error(
      `${device.id}: this image has no adb root (ro.build.type is not userdebug and ro.debuggable is not 1); the X2i is a user build. Keep the normal install.`,
    );
  }

  becomeRoot(device, rootSteps, cwd, exec, sleep);
}

/** The writes to /system between the two remounts and the reboot (none when `reboot` is absent); a failure puts /system back to read-only (best effort) before it is thrown. */
function writeSystem(
  stages: { rw: Command; write: Command[]; ro: Command; reboot?: Command },
  cwd: string,
  exec: Exec,
): void {
  try {
    runCommands([stages.rw, ...stages.write], cwd, { dryRun: false, exec });
  } catch (error) {
    try {
      exec(stages.ro, cwd);
    } catch {
      // best effort: the original failure is the one to report
    }

    throw error;
  }

  runCommands(stages.reboot ? [stages.ro, stages.reboot] : [stages.ro], cwd, {
    dryRun: false,
    exec,
  });
}

/**
 * uninstall --system for one device: the image check and adb root first (a user build fails before anything
 * is removed), then the usual uninstall steps, then the copy in priv-app and a reboot.
 */
function uninstallSystem(
  device: Device,
  pkg: string,
  opts: { dryRun: boolean; root: string; exec: Exec | undefined; sleep: Sleep },
): void {
  const { dryRun, root } = opts;
  const stages = systemRemoveStages(device);
  const usual = uninstallCommands(device, pkg, true);
  if (dryRun) {
    runCommands(stages.image, root, { dryRun });
    printRoot(stages.root, root);
    runCommands([...usual, stages.rw, ...stages.write, stages.ro, stages.reboot], root, { dryRun });
    return;
  }

  const exec = opts.exec ?? spawnExec;
  requireRoot(device, stages.image, stages.root, root, exec, opts.sleep);
  runCommands(usual, root, { dryRun, exec });
  writeSystem(stages, root, exec);
}

/**
 * provision --system for one device. The image and free-space checks come first and nothing on the device
 * changes until they pass. The permissions and the settings broadcast come after the reboot, because the
 * package manager only knows a priv-app after the boot scan. A /data copy of the same app with a lower
 * versionCode is uninstalled first; any other stays and becomes an update of the system app.
 */
function provisionSystem(
  device: Device,
  apk: string,
  pkg: string,
  home: boolean,
  opts: { dryRun: boolean; root: string; exec: Exec | undefined; sleep: Sleep },
): void {
  const { dryRun, root } = opts;
  const exec = opts.exec ?? spawnExec;
  const stages = systemProvisionStages(device, apk, pkg, home);
  if (dryRun) {
    runCommands(stages.image, root, { dryRun });
    printRoot(stages.root, root);
    runCommands(stages.inspect, root, { dryRun });
    console.log(
      `# if the /data copy has a lower versionCode than the APK: adb shell pm uninstall -k ${pkg} (-k keeps its data)`,
    );

    runCommands([stages.rw, ...stages.write, stages.ro, stages.reboot], root, { dryRun });
    runCommands(stages.afterReboot, root, { dryRun });
    console.log('# (polls getprop sys.boot_completed until the device has booted)');
    runCommands([...stages.provision, stages.verify], root, { dryRun });
    return;
  }

  requireRoot(device, stages.image, stages.root, root, exec, opts.sleep);

  const [df, pmPath, versions] = stages.inspect;
  const free = parseFreeKb(readOutput(df!, root, exec));
  const size = statSync(apk).size;
  if (free === undefined || !hasRoomFor(size, free)) {
    throw new Error(
      `${device.id}: not enough room on /system (${free ?? 'unknown'} KB free; the APK needs ${Math.ceil(size / 1024) + SYSTEM_SPARE_KB} KB with ${SYSTEM_SPARE_KB} KB to spare).`,
    );
  }

  if (isDataInstall(readOutput(pmPath!, root, exec))) {
    const installed = parseVersionCode(readOutput(versions!, root, exec));
    const pushed = apkVersionCode(apk);

    if (shouldUninstallDataCopy(installed, pushed)) {
      runCommands([shell(device, 'pm', 'uninstall', '-k', pkg)], root, { dryRun, exec });
    } else {
      console.log(
        `${device.id}: the /data copy (versionCode ${installed ?? 'unknown'}${pushed === undefined ? ', the APK is not read: no aapt under ANDROID_HOME/build-tools' : `, APK ${pushed}`}) stays; with the same signature it becomes an updated system app.`,
      );
    }
  }

  writeSystem(stages, root, exec);
  waitForBoot(device, root, exec, opts.sleep);
  runCommands([...stages.afterReboot, ...stages.provision], root, { dryRun, exec });

  const flags = readOutput(stages.verify, root, exec);
  if (!hasSystemFlag(flags)) {
    throw new Error(`${device.id}: ${pkg} is installed but not as a system app (no SYSTEM flag).`);
  }

  console.log(
    `${device.id}: installed as a system app. This is one-time: later updates are a plain \`hashsome android deploy\` (adb install -r keeps the system flag).`,
  );
}

/** `dumpsys package <pkg>` lines that tell a system app: its flags and where its code is. */
export const systemCheckCommand = (device: Device, pkg: string): Command =>
  shellLine(device, `dumpsys package ${pkg} | grep -E 'flags|codePath'`);

/** Whether `systemCheckCommand`'s output says a system app (the SYSTEM flag, or code in /system/priv-app). */
export const isSystemApp = (dumpsys: string): boolean =>
  hasSystemFlag(dumpsys) || /codePath=\/system\/priv-app\//.test(dumpsys);

/** Steps that copy the APK over the system copy: the provision's writes, without the reboot. */
export function systemRefreshStages(device: Device, apk: string, pkg: string) {
  const { image, root, rw, write, ro } = systemProvisionStages(device, apk, pkg);
  return { image, root, rw, write: write.filter((c) => c[3] !== 'mkdir'), ro };
}

/**
 * deploy on a Shelly display whose app is a system app: `adb install -r` with the same versionCode updates the
 * running app, but the next boot drops that /data update for the older /system/priv-app copy, so the copy is
 * refreshed too. No reboot: the running app is already the new code. A user build cannot write /system, so
 * there the update will not survive a reboot, and that is said.
 */
function refreshSystemCopy(
  device: Device,
  apk: string,
  pkg: string,
  opts: { dryRun: boolean; root: string; exec: Exec | undefined; sleep: Sleep },
): void {
  if (device.profile === 'generic') {
    return;
  }

  const { dryRun, root } = opts;
  const stages = systemRefreshStages(device, apk, pkg);
  if (dryRun) {
    console.log('# if the app is a system app (flags have SYSTEM) on a rootable image:');
    runCommands([systemCheckCommand(device, pkg), ...stages.image.slice(1)], root, { dryRun });
    printRoot(stages.root, root);
    runCommands([stages.rw, ...stages.write, stages.ro], root, { dryRun });
    console.log('# (system copy refreshed; no reboot)');
    return;
  }

  const exec = opts.exec ?? spawnExec;
  if (!isSystemApp(readOutput(systemCheckCommand(device, pkg), root, exec))) {
    return;
  }

  if (!imageIsRootable(stages.image, root, exec)) {
    console.warn(
      `${device.id}: ${pkg} is a system app but this image has no adb root, so the system copy cannot be refreshed: this update will not survive a reboot (the older system copy comes back). Raise the versionCode and redo the install, or keep the normal install.`,
    );

    return;
  }

  becomeRoot(device, stages.root, root, exec, opts.sleep);
  writeSystem(stages, root, exec);
  console.log(`${device.id}: system copy refreshed; no reboot.`);
}

/** `hashsome android`: build, deploy and provision the native app. */
export async function androidCommand(
  args: string[],
  root: string = resolve(process.cwd()),
  exec?: Exec,
  sleep: Sleep = sleepSync,
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
    flags: ['dry-run', 'home', 'system'],
  });

  if (positional.length > 0) {
    throw new Error(`Unexpected argument "${positional[0]}"\n\n${USAGE}`);
  }

  if (flags.has('home') && sub !== 'provision') {
    throw new Error('--home only applies to provision.');
  }

  if (flags.has('system') && sub !== 'provision' && sub !== 'uninstall') {
    throw new Error('--system only applies to provision and uninstall.');
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
    const system = flags.has('system');
    try {
      if (sub === 'provision' && system) {
        provisionSystem(device, apk, pkg, flags.has('home') || device.home === true, {
          dryRun,
          root,
          exec,
          sleep,
        });

        continue;
      }

      if (sub === 'uninstall' && system) {
        uninstallSystem(device, pkg, { dryRun, root, exec, sleep });
        if (device.home === true) {
          console.log(`${device.id}: ${HOME_NOTE}`);
        }

        continue;
      }

      const plan =
        sub === 'uninstall'
          ? uninstallCommands(device, pkg)
          : sub === 'deploy'
            ? deployCommands(device, apk, pkg)
            : provisionCommands(device, apk, pkg, flags.has('home') || device.home === true);

      runCommands(plan, root, { dryRun, exec });
      if (sub === 'deploy') {
        refreshSystemCopy(device, apk, pkg, { dryRun, root, exec, sleep });
      }

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
