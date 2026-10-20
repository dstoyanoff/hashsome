import { mkdirSync, readFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  androidCommand,
  hasRoomFor,
  hasSystemFlag,
  isDataInstall,
  isRootable,
  isOciTarget,
  ociApi,
  ociRelease,
  parseAaptBadging,
  parseAaptVersionCode,
  releaseFeed,
  parseFreeKb,
  parseVersionCode,
  shouldUninstallDataCopy,
  isSystemApp,
  systemProvisionStages,
  systemRemoveStages,
  buildCommands,
  deployCommands,
  HOME_ALIAS,
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
        `pm enable ${PKG}/dev.hashsome.kiosk.HomeActivityAlias`,
        `cmd package set-home-activity ${PKG}/dev.hashsome.kiosk.HomeActivityAlias`,
      ]);
    }

    expect(shellLines(device()).some((l) => l.includes('HomeActivityAlias'))).toBe(false);
  });
});

describe('uninstallCommands', () => {
  test('connect, the app removes its own admin, a pause, then pm uninstall, retried once after a further pause', () => {
    const plan = uninstallCommands(device(), PKG).map(line);
    expect(plan).toEqual([
      'adb connect 10.1.1.50:5555',
      `adb -s 10.1.1.50:5555 shell am broadcast --include-stopped-packages -a dev.hashsome.REMOVE_ADMIN -p ${PKG}`,
      'adb -s 10.1.1.50:5555 shell sleep 2',
      `adb -s 10.1.1.50:5555 shell sh -c 'pm uninstall ${PKG} || (sleep 2 && pm uninstall ${PKG})'`,
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

describe('system install', () => {
  const stargate = device({ profile: 'shelly-stargate', host: '10.1.1.60' });
  const jenna = device({ profile: 'shelly-jenna', host: '10.1.1.61' });
  const all = (d: Device) => {
    const s = systemProvisionStages(d, APK, PKG);
    return [
      ...s.image,
      ...s.root,
      ...s.inspect,
      s.rw,
      ...s.write,
      s.ro,
      s.reboot,
      ...s.afterReboot,
      ...s.provision,
      s.verify,
    ].map(line);
  };

  test.each([stargate, jenna])('the steps for $profile, in order', (d) => {
    const lines = all(d);
    const s = d.host + ':5555';
    const at = (needle: string) => lines.findIndex((l) => l.includes(needle));
    expect(lines).toContain(`adb -s ${s} root`);
    expect(lines).toContain(`adb -s ${s} wait-for-device`);
    expect(
      lines.some((l) => l.includes('mount -o rw,remount /system || mount -o rw,remount /')),
    ).toBe(true);

    expect(lines).toContain(`adb -s ${s} shell mkdir -p /system/priv-app/Hashsome`);
    expect(lines).toContain(`adb -s ${s} push ${APK} /system/priv-app/Hashsome/Hashsome.apk`);
    expect(lines).toContain(`adb -s ${s} shell chmod 755 /system/priv-app/Hashsome`);
    expect(lines).toContain(`adb -s ${s} shell chmod 644 /system/priv-app/Hashsome/Hashsome.apk`);
    expect(lines).toContain(
      `adb -s ${s} shell chown root:root /system/priv-app/Hashsome /system/priv-app/Hashsome/Hashsome.apk`,
    );

    expect(lines.some((l) => l.includes('restorecon -R /system/priv-app/Hashsome'))).toBe(true);
    // ro again before the reboot, the permissions only after it, and no plain install
    expect(at('ro,remount')).toBeLessThan(at(' reboot'));
    expect(at(' reboot')).toBeLessThan(at('appops set dev.hashsome.home WRITE_SETTINGS'));
    expect(at('PROVISION')).toBeGreaterThan(at(' reboot'));
    expect(lines.some((l) => l.includes(' install '))).toBe(false);
    expect(lines.at(-1)).toContain('dumpsys package dev.hashsome.home | grep flags');
  });

  test('the Jenna still gets its admin and the stock app put out of the way, the Stargate no admin', () => {
    expect(all(jenna).some((l) => l.includes('dpm set-active-admin'))).toBe(true);
    expect(all(stargate).some((l) => l.includes('dpm set-active-admin'))).toBe(false);
  });

  test('a tablet keeps the normal install', () => {
    expect(() => systemProvisionStages(device(), APK, PKG)).toThrow(
      '--system is for Shelly displays; a tablet keeps the normal install',
    );

    expect(() => systemRemoveStages(device())).toThrow('a tablet keeps the normal install');
  });

  test('uninstall --system checks root first, removes the copy last, and tolerates a system app', () => {
    const lines = uninstallCommands(stargate, PKG, true).map(line);
    expect(lines.find((l) => l.includes('pm uninstall'))).toContain('|| true');
    expect(uninstallCommands(stargate, PKG).map(line).join('\n')).not.toContain('|| true');
    const stages = systemRemoveStages(stargate);
    expect(line(stages.write[0]!)).toContain('rm -rf /system/priv-app/Hashsome');
    expect(line(stages.reboot)).toContain(' reboot');
  });

  test('the pre-checks read the image, the free space and the versions', () => {
    expect(isRootable('userdebug\n', '0')).toBe(true);
    expect(isRootable('user', '1\r\n')).toBe(true);
    expect(isRootable('user', '0')).toBe(false);
    expect(isRootable('', '')).toBe(false);

    const df =
      'Filesystem           1K-blocks    Used Available Use% Mounted on\n/dev/block/mmcblk0p5    1032088  831420    200668  81% /system\n';

    expect(parseFreeKb(df)).toBe(200668);
    expect(parseFreeKb('df: /system: No such file')).toBeUndefined();
    expect(hasRoomFor(10 * 1024 * 1024, 200668)).toBe(true);
    expect(hasRoomFor(10 * 1024 * 1024, 11 * 1024 - 1)).toBe(false);
    expect(hasRoomFor(10 * 1024 * 1024, 10 * 1024 + 1024)).toBe(true);

    expect(parseVersionCode('    versionCode=7 minSdk=24 targetSdk=34')).toBe(7);
    expect(parseVersionCode('Unable to find package')).toBeUndefined();
    expect(parseAaptVersionCode("package: name='x' versionCode='12' versionName='0.4'")).toBe(12);
    expect(isDataInstall('package:/data/app/dev.hashsome.home-1/base.apk')).toBe(true);
    expect(isDataInstall('package:/system/priv-app/Hashsome/Hashsome.apk')).toBe(false);
    expect(isDataInstall('')).toBe(false);
    expect(hasSystemFlag('    flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]')).toBe(true);
    expect(hasSystemFlag('    flags=[ HAS_CODE ]')).toBe(false);
    expect(shouldUninstallDataCopy(3, 4)).toBe(true);
    expect(shouldUninstallDataCopy(4, 4)).toBe(false);
    expect(shouldUninstallDataCopy(5, 4)).toBe(false);
    expect(shouldUninstallDataCopy(3, undefined)).toBe(false);
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
    expect(provisioned).toContain('HomeActivityAlias');
  });

  test('provision --system prints the whole plan in a dry run', async () => {
    const root = project([
      { id: 'x1', host: '1.1.1.1', profile: 'shelly-stargate' },
      { id: 'x2i', host: '2.2.2.2', profile: 'shelly-jenna' },
    ]);

    const log = output();
    await androidCommand(['provision', '--system', '--dry-run'], root);
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    expect(text).toContain('# x1 (1.1.1.1:5555, shelly-stargate)');
    expect(text).toContain('# x2i (2.2.2.2:5555, shelly-jenna)');
    expect(text).toContain('$ adb -s 1.1.1.1:5555 root');
    expect(text).toContain('/system/priv-app/Hashsome/Hashsome.apk');
    expect(text).toContain('dumpsys package dev.hashsome.home | grep flags');
  });

  test('deploy --dry-run on a Shelly shows the system copy refresh', async () => {
    const root = project([{ id: 'x1', host: '1.1.1.1', profile: 'shelly-stargate' }]);
    const log = output();
    await androidCommand(['deploy', '--dry-run'], root);
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    expect(text).toContain('/system/priv-app/Hashsome/Hashsome.apk');
    expect(text).toContain('(system copy refreshed; no reboot)');
    expect(text).not.toContain('1.1.1.1:5555 reboot');
  });

  test('provision --system refuses a generic device, and says so', async () => {
    const root = project([{ id: 'tab', host: '1.1.1.1', profile: 'generic' }]);
    output();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    await expect(androidCommand(['provision', '--system', '--dry-run'], root)).rejects.toThrow(
      'provision failed for 1 of 1 device(s): tab',
    );

    expect(error.mock.calls.join('\n')).toContain(
      '--system is for Shelly displays; a tablet keeps the normal install',
    );
  });

  test('provision --system refuses a user build before changing anything', async () => {
    const root = project([{ id: 'x2i', host: '2.2.2.2', profile: 'shelly-jenna' }]);
    mkdirSync(join(root, 'android/app/build/outputs/apk/release'), { recursive: true });
    writeFileSync(join(root, 'android/app/build/outputs/apk/release/app-release.apk'), 'apk');
    output();
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const seen: string[] = [];
    const exec: Exec = (command) => {
      seen.push(line(command));
      const last = command.at(-1) ?? '';
      return {
        status: 0,
        output:
          last === 'ro.build.type' ? 'user\n' : last === 'ro.debuggable' ? '0\n' : 'connected',
      };
    };

    await expect(androidCommand(['provision', '--system'], root, exec)).rejects.toThrow(
      'failed for 1',
    );

    expect(error.mock.calls.join('\n')).toContain('no adb root');
    expect(error.mock.calls.join('\n')).toContain('user build');
    expect(
      seen.some((l) => l.includes(' root') || l.includes('remount') || l.includes('push')),
    ).toBe(false);
  });

  describe('provision --system against a mock device', () => {
    const setup = (
      devices: unknown = [{ id: 'x1', host: '1.1.1.1', profile: 'shelly-stargate' }],
    ) => {
      const root = project(devices);
      mkdirSync(join(root, 'android/app/build/outputs/apk/release'), { recursive: true });
      writeFileSync(join(root, 'android/app/build/outputs/apk/release/app-release.apk'), 'apk');
      output();
      vi.spyOn(console, 'error').mockImplementation(() => {});
      return root;
    };

    const mock = (
      opts: { buildType?: string; debuggable?: string; failOn?: string; flags?: string } = {},
    ) => {
      const seen: string[] = [];
      const exec: Exec = (command) => {
        const text = line(command);
        seen.push(text);
        if (opts.failOn && text.includes(opts.failOn)) {
          return { status: 1, output: '' };
        }

        const out = text.includes('ro.build.type')
          ? `${opts.buildType ?? 'userdebug'}\n`
          : text.includes('ro.debuggable')
            ? `${opts.debuggable ?? '1'}\n`
            : text.includes('df -k')
              ? 'Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/x 1000000 800000 200000 80% /system\n'
              : text.includes('sys.boot_completed')
                ? '1\n'
                : text.includes('grep flags')
                  ? `${opts.flags ?? 'flags=[ SYSTEM HAS_CODE ]'}\n`
                  : '';

        return { status: 0, output: out };
      };

      return { seen, exec };
    };

    const sleeps: number[] = [];
    const sleep = (ms: number) => void sleeps.push(ms);

    test('happy path: image check and root first, writes, ro, reboot, then the permissions and the flag check', async () => {
      const root = setup();
      const { seen, exec } = mock();
      await androidCommand(['provision', '--system'], root, exec, sleep);
      const at = (needle: string) => seen.findIndex((l) => l.includes(needle));
      expect(at('ro.build.type')).toBeLessThan(at('1.1.1.1:5555 root'));
      expect(at('1.1.1.1:5555 root')).toBeLessThan(at('rw,remount'));
      expect(at('rw,remount')).toBeLessThan(at(' push '));
      expect(at(' push ')).toBeLessThan(at('ro,remount'));
      expect(at('ro,remount')).toBeLessThan(at(' reboot'));
      expect(at(' reboot')).toBeLessThan(at('appops set'));
      expect(at('appops set')).toBeLessThan(at('grep flags'));
      expect(seen.some((l) => l.includes(' install '))).toBe(false);
      expect(sleeps).toContain(2000);
    });

    test('a push that fails still puts /system back to read-only, and does not reboot', async () => {
      const root = setup();
      const { seen, exec } = mock({ failOn: ' push ' });
      await expect(androidCommand(['provision', '--system'], root, exec, sleep)).rejects.toThrow(
        'failed for 1',
      );

      const after = seen.slice(seen.findIndex((l) => l.includes(' push ')));
      expect(after.some((l) => l.includes('ro,remount'))).toBe(true);
      expect(seen.some((l) => l.includes(' reboot'))).toBe(false);
    });

    test('a missing SYSTEM flag after the reboot fails the device', async () => {
      const root = setup();
      const { exec } = mock({ flags: 'flags=[ HAS_CODE ]' });
      await expect(androidCommand(['provision', '--system'], root, exec, sleep)).rejects.toThrow(
        'failed for 1',
      );
    });

    describe('deploy on a system app', () => {
      const run = async (opts: Parameters<typeof mock>[0], system: boolean) => {
        const root = setup();
        const m = mock(opts);
        const exec: Exec = (command) => {
          if (line(command).includes("grep -E 'flags|codePath'")) {
            m.seen.push(line(command));
            return {
              status: 0,
              output: system ? 'flags=[ SYSTEM HAS_CODE ]\n' : 'flags=[ HAS_CODE ]\n',
            };
          }

          return m.exec(command, root);
        };

        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        await androidCommand(['deploy'], root, exec, sleep);
        return { seen: m.seen, warn };
      };

      test('system and rootable: the system copy is refreshed, without a reboot', async () => {
        const { seen, warn } = await run({}, true);
        const at = (needle: string) => seen.findIndex((l) => l.includes(needle));
        expect(at(' install ')).toBeLessThan(at('rw,remount'));
        expect(at('1.1.1.1:5555 root')).toBeLessThan(at('rw,remount'));
        expect(at('rw,remount')).toBeLessThan(at(' push '));
        expect(seen[at(' push ')]).toContain('/system/priv-app/Hashsome/Hashsome.apk');
        expect(at(' push ')).toBeLessThan(at('chmod 644'));
        expect(at('chmod 644')).toBeLessThan(at('ro,remount'));
        expect(seen.some((l) => l.includes(' reboot'))).toBe(false);
        expect(warn).not.toHaveBeenCalled();
      });

      test('a failed install skips the refresh', async () => {
        const root = setup();
        const m = mock({ failOn: ' install ' });
        await expect(androidCommand(['deploy'], root, m.exec, sleep)).rejects.toThrow(
          'deploy failed',
        );

        expect(m.seen.some((l) => l.includes(' push ') || l.includes('remount'))).toBe(false);
      });

      test('isSystemApp', () => {
        expect(isSystemApp('privateFlags=[ PRIVILEGED ]')).toBe(false);
        expect(isSystemApp('flags=[ HAS_CODE UPDATED_SYSTEM_APP ]')).toBe(false);
        expect(isSystemApp('codePath=/system/priv-app/Hashsome')).toBe(true);
        expect(isSystemApp('flags=[ SYSTEM HAS_CODE ]')).toBe(true);
      });

      test('not a system app: nothing extra', async () => {
        const { seen, warn } = await run({}, false);
        expect(seen.some((l) => l.includes(' push ') || l.includes('remount'))).toBe(false);
        expect(seen.some((l) => l.includes(' root'))).toBe(false);
        expect(warn).not.toHaveBeenCalled();
      });

      test('system but not rootable: a warning only', async () => {
        const { seen, warn } = await run({ buildType: 'user', debuggable: '0' }, true);
        expect(seen.some((l) => l.includes(' push ') || l.includes('remount'))).toBe(false);
        expect(warn).toHaveBeenCalledOnce();
        expect(String(warn.mock.calls[0]![0])).toContain('will not survive a reboot');
      });
    });

    test('uninstall --system on a user build fails before anything is removed', async () => {
      const root = setup();
      const { seen, exec } = mock({ buildType: 'user', debuggable: '0' });
      await expect(androidCommand(['uninstall', '--system'], root, exec, sleep)).rejects.toThrow(
        'failed for 1',
      );

      expect(
        seen.some(
          (l) => l.includes('REMOVE_ADMIN') || l.includes('pm uninstall') || l.includes('rm -rf'),
        ),
      ).toBe(false);
    });

    test('uninstall --system: a failed delete still goes back to read-only', async () => {
      const root = setup();
      const { seen, exec } = mock({ failOn: 'rm -rf' });
      await expect(androidCommand(['uninstall', '--system'], root, exec, sleep)).rejects.toThrow(
        'failed for 1',
      );

      expect(seen.findIndex((l) => l.includes('REMOVE_ADMIN'))).toBeGreaterThan(
        seen.findIndex((l) => l.includes('1.1.1.1:5555 root')),
      );

      expect(
        seen
          .slice(seen.findIndex((l) => l.includes('rm -rf')))
          .some((l) => l.includes('ro,remount')),
      ).toBe(true);

      expect(seen.some((l) => l.includes(' reboot'))).toBe(false);
    });
  });

  test('--system only applies to provision and uninstall', async () => {
    const root = project([{ id: 'a', host: '1.1.1.1', profile: 'shelly-stargate' }]);
    await expect(androidCommand(['deploy', '--system', '--dry-run'], root)).rejects.toThrow(
      '--system only applies',
    );
  });

  test('uninstall needs no APK, honours --device, and reminds about the home app only for a home device', async () => {
    const root = project([
      { id: 'a', host: '1.1.1.1', profile: 'generic', home: true },
      { id: 'b', host: '2.2.2.2', profile: 'shelly-jenna' },
    ]);

    const log = output();
    await androidCommand(['uninstall', '--device', 'a'], root, () => ({ status: 0 }));
    const text = log.mock.calls.map((c) => c[0]).join('\n');
    expect(text).toContain('pm uninstall dev.hashsome.home ||');
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

describe('HOME_ALIAS', () => {
  test('is the activity-alias the kiosk manifest declares', () => {
    const manifest = readFileSync(
      join(import.meta.dirname, '../../../../../android/kiosk/src/main/AndroidManifest.xml'),
      'utf8',
    );

    expect(manifest).toContain(`android:name="${HOME_ALIAS}"`);
  });
});

describe('release feed', () => {
  test('reads package, code and name from aapt badging', () => {
    const text =
      "package: name='dev.hashsome.home' versionCode='1903' versionName='0.19.3' platformBuildVersionName='14'";

    expect(parseAaptBadging(text)).toEqual({
      packageName: 'dev.hashsome.home',
      versionCode: 1903,
      versionName: '0.19.3',
    });

    expect(parseAaptBadging('nothing')).toBeUndefined();
  });

  test('latest.json carries what the app checks, notes only when given', () => {
    const badge = { packageName: 'dev.hashsome.home', versionCode: 1903, versionName: '0.19.3' };
    expect(releaseFeed(badge, 'dev.hashsome.home-1903.apk', 'ab', 10, undefined)).toEqual({
      packageName: 'dev.hashsome.home',
      versionName: '0.19.3',
      versionCode: 1903,
      apk: 'dev.hashsome.home-1903.apk',
      sha256: 'ab',
      size: 10,
    });

    expect(releaseFeed(badge, 'a.apk', 'ab', 10, 'Fixes')).toHaveProperty('notes', 'Fixes');
  });

  test('a device may name its update feed', () => {
    const [device] = validateDevices([
      { id: 'a', host: '1.2.3.4', profile: 'generic', updates: 'http://h/local/x' },
    ]);

    expect(device?.updates).toBe('http://h/local/x');
    expect(() =>
      validateDevices([{ id: 'a', host: '1.2.3.4', profile: 'generic', updates: '' }]),
    ).toThrow(/updates/);
  });
});

describe('registry release', () => {
  const badge = { packageName: 'dev.hashsome.home', versionCode: 1903, versionName: '0.19.3' };

  test('a registry target is told from a folder, and gives its API root', () => {
    expect(isOciTarget('oci://10.4.5.1:5000/home-android')).toBe(true);
    expect(isOciTarget('android/dist')).toBe(false);
    expect(ociApi('oci://10.4.5.1:5000/hashsome/home/')).toBe(
      'http://10.4.5.1:5000/v2/hashsome/home',
    );

    expect(ociApi('ocis://registry.lan/home')).toBe('https://registry.lan/v2/home');
    expect(() => ociApi('oci://10.4.5.1:5000')).toThrow(/repository/);
  });

  test('the manifest has the APK as its layer, tagged with the version code', () => {
    const apk = new TextEncoder().encode('apk bytes');
    const { manifest, tag, config } = ociRelease(badge, apk, 'Fixes');
    expect(tag).toBe('1903');
    expect(manifest.layers).toHaveLength(1);
    expect(manifest.layers[0]).toMatchObject({
      mediaType: 'application/vnd.android.package-archive',
      size: apk.length,
    });

    expect(manifest.layers[0]!.digest).toMatch(/^sha256:[0-9a-f]{64}$/);
    expect(manifest.config.size).toBe(config.length);
    expect(JSON.parse(new TextDecoder().decode(config))).toEqual({ ...badge, notes: 'Fixes' });
  });
});
