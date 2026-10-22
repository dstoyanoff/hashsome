import { describe, expect, test } from 'vitest';
import { parseArgs } from '../../cli/package.ts';
import {
  chartDeployment,
  chartValues,
  composeFile,
  dockerfile,
  envExample,
  helmReadme,
  importScript,
  releaseReadme,
  safeName,
  type ReleaseInfo,
} from '../render.ts';

const info: ReleaseInfo = {
  name: 'home',
  tag: '20261005-1200',
  platform: 'linux/amd64',
  port: 3000,
  image: true,
};

describe('names', () => {
  test.each([
    ['home', 'home'],
    ['@me/My Home_2', 'my-home-2'],
    ['  --odd--  ', 'odd'],
    ['', 'hashsome'],
  ])('%j becomes %j', (raw, expected) => {
    expect(safeName(raw)).toBe(expected);
  });
});

describe('the image', () => {
  test('is the bundle on a slim Node, run as a user that is not root, with a health check', () => {
    const file = dockerfile(info);
    expect(file).toContain('FROM node:24.22.1-slim@sha256:55fb5f5a527ea2c5c4a95f963e48ef90aa38c7b346054fdc8ee9957a2b55d205');
    expect(file).toContain('COPY server.mjs');
    expect(file).toContain('USER node');
    expect(file).toContain('/healthz');
    // Nothing is installed or compiled while building it: only files are copied in.
    expect(file).not.toMatch(/^RUN /m);
  });

  test('holds no secret: the tokens are not in anything the release writes', () => {
    const all = [
      dockerfile(info),
      composeFile(info),
      chartValues(info),
      chartDeployment(),
      helmReadme(info),
    ].join('\n');

    expect(all).not.toMatch(/TOKEN=[^\s.]+/);
  });
});

describe('compose', () => {
  test('runs the release’s image, with the environment from a file and not the image', () => {
    const file = composeFile(info);
    expect(file).toContain('image: home:20261005-1200');
    expect(file).toContain('env_file: .env');
    expect(file).toContain('3000:3000');
  });

  test('shows the project’s own .env.example, or says what it would hold', () => {
    expect(envExample({ ...info, envExample: 'HA_URL=\nHA_TOKEN=\n' })).toBe(
      'HA_URL=\nHA_TOKEN=\n',
    );

    expect(envExample(info)).toContain('hashsome.config.ts');
  });
});

describe('helm', () => {
  test('uses the imported image and never pulls, with one replica that replaces the old pod', () => {
    expect(chartValues(info)).toContain('pullPolicy: Never');
    expect(chartValues(info)).toContain('tag: "20261005-1200"');
    expect(chartDeployment()).toContain('type: Recreate');
    expect(chartDeployment()).toContain('replicas: 1');
  });

  test('takes the tokens from a Secret you create, and does not make one', () => {
    expect(chartDeployment()).toContain('secretRef');
    expect(chartDeployment()).toContain('required');
    expect(chartValues(info)).toContain('existingSecret: ""');
  });

  test('has probes, and a certificate authority as an option', () => {
    const deployment = chartDeployment();
    expect(deployment).toContain('readinessProbe');
    expect(deployment).toContain('livenessProbe');
    expect(deployment).toContain('NODE_EXTRA_CA_CERTS');
  });

  test('imports the image into k3s from this release', () => {
    expect(importScript(info)).toContain('k3s ctr -n k8s.io images import ../image.tar');
    expect(importScript(info).startsWith('#!/bin/sh')).toBe(true);
  });
});

describe('the release readme', () => {
  test('lists only what was asked for', () => {
    const readme = releaseReadme(info, ['helm']);
    expect(readme).toContain('helm/');
    expect(readme).not.toContain('compose/');
    expect(readme).toContain('image.tar');
    expect(releaseReadme({ ...info, image: false }, ['plain'])).not.toContain('image.tar');
    // Only the image: it says so once, as the thing you asked for.
    const only = releaseReadme(info, ['image']);
    expect(only).toContain('just the container image');
    expect(only).not.toContain('for compose and helm');
  });
});

describe('package arguments', () => {
  test('targets are words, options are flags', () => {
    expect(parseArgs(['helm', '--platform', 'linux/amd64', '--tag=x', '--no-image'])).toMatchObject(
      {
        targets: ['helm'],
        platform: 'linux/amd64',
        tag: 'x',
        image: false,
      },
    );

    expect(parseArgs(['plain', 'compose']).targets).toEqual(['plain', 'compose']);
    expect(parseArgs(['image']).targets).toEqual(['image']);
    expect(parseArgs([])).toMatchObject({ targets: [], image: true, out: 'release' });
  });

  test.each([
    [['nope'], /Unknown target/],
    [['--wat'], /Unknown option/],
    [['--port'], /needs a value/],
    [['--port', 'abc'], /port number/],
  ])('%j is refused', (args, message) => {
    expect(() => parseArgs(args)).toThrow(message);
  });
});
