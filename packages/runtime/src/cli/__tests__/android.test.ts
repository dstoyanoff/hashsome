import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  androidCommand,
  buildCommands,
  deployCommands,
  parseFlags,
  provisionCommands,
  readGradleValue,
  runCommands,
  shellQuote,
  validateDevices,
  uninstallCommands,
  type Device,
  type Exec,
} from '../android.ts';

const device = (extra: Partial<Device> = {}): Device => ({
  id: 'bathroom',
  host: '10.1.1.50',
  profile: 'generic',
  ...extra,
});

const APK = '/p/app-release.apk';
const PKG = 'dev.hashsome.home';
const line = (command: string[]) => command.join(' ');

describe('parseFlags', () => {
  test('words, --flag value, --flag=value and bare flags', () => {
    expect(
      parseFlags(['a', '--device', 'x', '--dir=y z', '--dry-run', 'b'], {
        values: ['device', 'dir'],
        flags: ['dry-run'],
      }),
    ).toEqual({
      positional: ['a', 'b'],
      values: { device: 'x', dir: 'y z' },
      flags: new Set(['dry-run']),
    });
  });

  test('rejects an unknown flag and a missing value', () => {
    expect(() => parseFlags(['--nope'], {})).toThrow('Unknown option "--nope"');
    expect(() => parseFlags(['--device'], { values: ['device'] })).toThrow('needs a value');
    expect(() =>
      parseFlags(['--device', '--dry-run'], { values: ['device'], flags: ['dry-run'] }),
    ).toThrow('needs a value');
  });
});

describe('readGradleValue', () => {
  test('reads a quoted assignment', () => {
    const text =
      'android {\n    namespace = "dev.hashsome.home"\n    defaultConfig {\n        applicationId = "dev.hashsome.home"\n    }\n}';

    expect(readGradleValue(text, 'applicationId')).toBe('dev.hashsome.home');
    expect(readGradleValue(text, 'namespace')).toBe('dev.hashsome.home');
    expect(readGradleValue(text, 'versionName')).toBeUndefined();
  });
});

describe('validateDevices', () => {
  test('accepts a full and a minimal device', () => {
    const devices = [
      {
        id: 'a',
        host: '1.2.3.4',
        profile: 'shelly-jenna',
        dashboard: 'bath',
        server: 'http://s',
        mqtt: 'mqtt://m',
        home: true,
      },
      { id: 'b', host: 'tablet.local', profile: 'generic' },
    ];

    expect(validateDevices(devices)).toEqual(devices);
  });

  test.each([
    [{}, 'must be an array'],
    [[1], 'entry 1 must be an object'],
    [[{ host: 'h', profile: 'generic' }], '"id" is required'],
    [[{ id: 'a', profile: 'generic' }], 'a": "host" is required'],
    [
      [{ id: 'a', host: 'h', profile: 'tv' }],
      '"profile" must be one of shelly-stargate, shelly-jenna, generic',
    ],
    [
      [{ id: 'a', host: 'h', profile: 'generic', server: 3 }],
      '"server" must be a non-empty string',
    ],
    [[{ id: 'a', host: 'h', profile: 'generic', home: 'yes' }], '"home" must be true or false'],
    [[{ id: 'a', host: 'h', profile: 'generic', hots: 'x' }], 'unknown field "hots"'],
    [
      [
        { id: 'a', host: 'h', profile: 'generic' },
        { id: 'a', host: 'i', profile: 'generic' },
      ],
      'appears twice',
    ],
  ])('rejects %j', (json, message) => {
    expect(() => validateDevices(json)).toThrow(message);
  });
});

describe('shellQuote', () => {
  test('quotes what a device shell would split or expand', () => {
    expect(shellQuote('http://10.1.1.2:3000/a')).toBe('http://10.1.1.2:3000/a');
    expect(shellQuote('http://h/?a=1&b=2')).toBe("'http://h/?a=1&b=2'");
    expect(shellQuote("it's")).toBe(`'it'\\''s'`);
  });
});

describe('buildCommands', () => {
  test('is the gradle assemble', () => {
    expect(buildCommands().map(line)).toEqual(['./gradlew assembleRelease']);
  });
});

describe('deployCommands', () => {
  test('connect, install, broadcast with only the extras set, start', () => {
    const commands = deployCommands(
      device({
        dashboard: 'bathroom-small',
        server: 'http://10.1.1.2:3000',
        mqtt: 'mqtt://10.1.1.2',
      }),
      APK,
      PKG,
    ).map(line);

    expect(commands).toEqual([
      'adb connect 10.1.1.50:5555',
      `adb -s 10.1.1.50:5555 install -r -d ${APK}`,
      `adb -s 10.1.1.50:5555 shell am broadcast --include-stopped-packages -a dev.hashsome.PROVISION -p ${PKG} --es server http://10.1.1.2:3000 --es dashboard bathroom-small --es device bathroom --es profile generic --es mqtt mqtt://10.1.1.2`,
      `adb -s 10.1.1.50:5555 shell am start -n ${PKG}/dev.hashsome.ui.DashboardActivity`,
    ]);
  });

  test('leaves out the extras that are not set, and keeps a host:port', () => {
    const commands = deployCommands(device({ host: '10.1.1.9:5556' }), APK, PKG).map(line);
    expect(commands[0]).toBe('adb connect 10.1.1.9:5556');
    expect(commands[2]).toBe(
      `adb -s 10.1.1.9:5556 shell am broadcast --include-stopped-packages -a dev.hashsome.PROVISION -p ${PKG} --es device bathroom --es profile generic`,
    );
  });

  test('quotes a value the device shell would split', () => {
    const broadcast = line(deployCommands(device({ server: 'http://h/?a=1&b=2' }), APK, PKG)[2]!);
    expect(broadcast).toContain("--es server 'http://h/?a=1&b=2'");
  });
});

describe('provisionCommands', () => {
  const shellLines = (d: Device, home?: boolean) =>
    provisionCommands(d, APK, PKG, home).map((c) =>
      line(c).replace('adb -s 10.1.1.50:5555 shell ', ''),
    );

  test('generic: permissions, whitelist and admin before the start; no Shelly handling', () => {
    expect(shellLines(device())).toEqual([
      'adb connect 10.1.1.50:5555',
      `adb -s 10.1.1.50:5555 install -r -d ${APK}`,
      `appops set ${PKG} WRITE_SETTINGS allow`,
      `appops set ${PKG} SYSTEM_ALERT_WINDOW allow`,
      `appops set ${PKG} GET_USAGE_STATS allow`,
      `dumpsys deviceidle whitelist +${PKG}`,
      `dpm set-active-admin ${PKG}/dev.hashsome.kiosk.HashsomeDeviceAdmin`,
      `am broadcast --include-stopped-packages -a dev.hashsome.PROVISION -p ${PKG} --es device bathroom --es profile generic`,
      `am start -n ${PKG}/dev.hashsome.ui.DashboardActivity`,
    ]);
  });

  test('shelly-stargate gets no device admin', () => {
    const lines = shellLines(device({ profile: 'shelly-stargate' }));
    expect(lines.some((l) => l.startsWith('dpm'))).toBe(false);
    expect(lines.some((l) => l.includes('cloud.shelly.stargate'))).toBe(false);
  });

  test('shelly-jenna: admin, and the Shelly host denied the overlay and stopped before the app starts', () => {
    const lines = shellLines(device({ profile: 'shelly-jenna' }));
    expect(lines).toContain(`dpm set-active-admin ${PKG}/dev.hashsome.kiosk.HashsomeDeviceAdmin`);
    const deny = lines.indexOf('appops set cloud.shelly.stargate SYSTEM_ALERT_WINDOW deny');
    expect(lines[deny + 1]).toBe('am force-stop cloud.shelly.stargate');
    expect(deny).toBeLessThan(lines.findIndex((l) => l.startsWith('am start')));
    expect(lines.filter((l) => l.includes('force-stop'))).toHaveLength(1);
  });

  test('--home (or home: true) enables the alias and sets it as the home activity, last', () => {
    for (const lines of [shellLines(device(), true), shellLines(device({ home: true }))]) {
      expect(lines.slice(-2)).toEqual([
        `pm enable ${PKG}/dev.hashsome.kiosk.HomeAlias`,
        `cmd package set-home-activity ${PKG}/dev.hashsome.kiosk.HomeAlias`,
      ]);
    }

    expect(shellLines(device()).some((l) => l.includes('HomeAlias'))).toBe(false);
  });
});

describe('uninstallCommands', () => {
  test('connect, the app removes its own admin, a pause, then pm uninstall', () => {
    const plan = uninstallCommands(device(), PKG).map(line);
    expect(plan).toEqual([
      'adb connect 10.1.1.50:5555',
      `adb -s 10.1.1.50:5555 shell am broadcast --include-stopped-packages -a dev.hashsome.REMOVE_ADMIN -p ${PKG}`,
      'adb -s 10.1.1.50:5555 shell sleep 2',
      `adb -s 10.1.1.50:5555 shell pm uninstall ${PKG}`,
    ]);
  });
});

describe('runCommands', () => {
  afterEach(() => vi.restoreAllMocks());

  test('a dry run prints and runs nothing', () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const exec = vi.fn<Exec>();
    runCommands(
      [
        ['adb', 'devices'],
        ['echo', 'a b'],
      ],
      '/',
      { dryRun: true, exec },
    );

    expect(exec).not.toHaveBeenCalled();
    expect(log.mock.calls.map((c) => c[0])).toEqual(['$ adb devices', "$ echo 'a b'"]);
  });

  test('echoes a device shell command single-quoted, so it can be pasted', () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    runCommands(deployCommands(device(), APK, PKG).slice(3), '/', { dryRun: true });
    expect(log.mock.calls[0]![0]).toBe(
      `$ adb -s 10.1.1.50:5555 shell 'am start -n ${PKG}/dev.hashsome.ui.DashboardActivity'`,
    );
  });

  test('stops at the first failure and names the command', () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const exec = vi
      .fn<Exec>()
      .mockReturnValueOnce({ status: 0 })
      .mockReturnValueOnce({ status: 1 });

    expect(() =>
      runCommands([['one'], ['two', 'x'], ['three']], '/', { dryRun: false, exec }),
    ).toThrow('Failed: $ two x');

    expect(exec).toHaveBeenCalledTimes(2);
  });

  test('a program that cannot start is a failure', () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const exec = vi
      .fn<Exec>()
      .mockReturnValue({ status: null, error: 'adb is not installed or not on the PATH' });

    expect(() => runCommands([['adb']], '/', { dryRun: false, exec })).toThrow('not installed');
  });
});

describe('androidCommand (dry run)', () => {
  const dirs: string[] = [];
  afterEach(() => {
    vi.restoreAllMocks();
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true });
    }
  });

  const project = (devices: unknown) => {
    const root = mkdtempSync(join(tmpdir(), 'android-'));
    dirs.push(root);
    mkdirSync(join(root, 'android/app'), { recursive: true });
    writeFileSync(
      join(root, 'android/app/build.gradle.kts'),
      'android {\n  defaultConfig {\n    applicationId = "dev.hashsome.home"\n  }\n}',
    );

    writeFileSync(join(root, 'devices.json'), JSON.stringify(devices));
    return root;
  };

  const output = () => vi.spyOn(console, 'log').mockImplementation(() => {});

  test('build prints the gradle command and the apk', async () => {
    const log = output();
    await androidCommand(['build', '--dry-run'], '/nowhere');
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    expect(text).toContain('$ ./gradlew assembleRelease');
    expect(text).toContain('android/app/build/outputs/apk/release/app-release.apk');
  });

  test('deploy plans every device, provision only the one asked for', async () => {
    const root = project([
      { id: 'a', host: '1.1.1.1', profile: 'generic' },
      { id: 'b', host: '2.2.2.2', profile: 'shelly-jenna' },
    ]);

    const log = output();
    await androidCommand(['deploy', '--dry-run'], root);
    const deployed = log.mock.calls.map((c) => c[0]).join('\n');
    expect(deployed).toContain('adb connect 1.1.1.1:5555');
    expect(deployed).toContain('adb connect 2.2.2.2:5555');

    log.mockClear();
    await androidCommand(['provision', '--device', 'b', '--home', '--dry-run'], root);
    const provisioned = log.mock.calls.map((c) => c[0]).join('\n');
    expect(provisioned).not.toContain('1.1.1.1');
    expect(provisioned).toContain('cloud.shelly.stargate');
    expect(provisioned).toContain('HomeAlias');
  });

  test('uninstall needs no APK, honours --device, and reminds about the home app only for a home device', async () => {
    const root = project([
      { id: 'a', host: '1.1.1.1', profile: 'generic', home: true },
      { id: 'b', host: '2.2.2.2', profile: 'shelly-jenna' },
    ]);

    const log = output();
    await androidCommand(['uninstall', '--device', 'a'], root, () => ({ status: 0 }));
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    expect(text).toContain('pm uninstall dev.hashsome.home');
    expect(text).toContain('choose the home app again');
    expect(text).not.toContain('2.2.2.2');

    log.mockClear();
    await androidCommand(['uninstall', '--device', 'b', '--dry-run'], root);
    expect(log.mock.calls.map((c) => c[0]).join('\n')).not.toContain('choose the home app');
  });

  test('build runs the wrapper inside android/, and needs it there', async () => {
    output();
    const root = project([]);
    const exec = vi.fn<Exec>().mockReturnValue({ status: 0 });
    await expect(androidCommand(['build'], root, exec)).rejects.toThrow(
      join(root, 'android', 'gradlew'),
    );

    writeFileSync(join(root, 'android/gradlew'), '');
    await androidCommand(['build'], root, exec);
    expect(exec).toHaveBeenCalledWith(['./gradlew', 'assembleRelease'], join(root, 'android'));
  });

  test('--device is rejected for build', async () => {
    output();
    await expect(androidCommand(['build', '--device', 'a', '--dry-run'], '/x')).rejects.toThrow(
      '--device does not apply to build',
    );
  });

  test('a failing device does not stop the others; the summary names it', async () => {
    output();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const root = project([
      { id: 'a', host: '1.1.1.1', profile: 'generic' },
      { id: 'b', host: '2.2.2.2', profile: 'generic' },
    ]);

    mkdirSync(join(root, 'android/app/build/outputs/apk/release'), { recursive: true });
    writeFileSync(join(root, 'android/app/build/outputs/apk/release/app-release.apk'), '');
    const exec = vi.fn<Exec>((command) => ({ status: command.includes('1.1.1.1:5555') ? 1 : 0 }));

    await expect(androidCommand(['deploy'], root, exec)).rejects.toThrow(
      'deploy failed for 1 of 2 device(s): a',
    );

    // a stops at its first command (the connect); b runs all four.
    const ran = exec.mock.calls.map((c) => c[0].join(' '));
    expect(ran.filter((c) => c.includes('1.1.1.1'))).toHaveLength(1);
    expect(ran.filter((c) => c.includes('2.2.2.2'))).toHaveLength(4);
    expect(error).toHaveBeenCalledWith(expect.stringContaining('Failed:'));
  });

  test('adb connect that prints "failed to connect" is a failure even at exit 0', () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const exec = vi
      .fn<Exec>()
      .mockReturnValue({ status: 0, output: 'failed to connect to 1.1.1.1:5555' });

    expect(() =>
      runCommands([['adb', 'connect', '1.1.1.1:5555'], ['next']], '/', { dryRun: false, exec }),
    ).toThrow('failed to connect');

    expect(exec).toHaveBeenCalledTimes(1);
  });

  test('says so for an unknown device, a bad devices.json, a missing id and --home elsewhere', async () => {
    output();
    const root = project([{ id: 'a', host: '1.1.1.1', profile: 'generic' }]);
    await expect(androidCommand(['deploy', '--device', 'zzz', '--dry-run'], root)).rejects.toThrow(
      'No device "zzz"',
    );

    await expect(androidCommand(['deploy', '--home', '--dry-run'], root)).rejects.toThrow(
      '--home only applies',
    );

    await expect(androidCommand(['nope'], root)).rejects.toThrow('Unknown command');
    await expect(androidCommand(['deploy', '--dry-run'], project([{ id: 'a' }]))).rejects.toThrow(
      '"host" is required',
    );
  });

  test('--help lists the five commands', async () => {
    const log = output();
    await androidCommand(['--help']);
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    for (const sub of ['build', 'deploy', 'provision', 'uninstall', 'icons']) {
      expect(text).toContain(`  ${sub} `);
    }
  });
});
