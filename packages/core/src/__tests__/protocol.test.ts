import { describe, expect, test } from 'vitest';
import {
  assetUrl,
  encodeMessage,
  parseClientMessage,
  parseServerMessage,
  sizedAssetUrl,
  type ClientMessage,
} from '../protocol.ts';

describe('protocol', () => {
  test('round-trips valid client messages', () => {
    const command: ClientMessage = {
      type: 'command',
      id: 1,
      ref: 'ha:light.a',
      command: 'setBrightness',
      args: { brightness: 0.1 },
    };

    expect(parseClientMessage(encodeMessage(command))).toEqual(command);
    const query: ClientMessage = {
      type: 'query',
      id: 3,
      ref: 'ma:kitchen',
      query: 'browse',
      args: { path: 'shelf:albums' },
    };

    expect(parseClientMessage(encodeMessage(query))).toEqual(query);
    const history: ClientMessage = {
      type: 'query',
      id: 4,
      ref: 'ha:sensor.power',
      query: 'history',
      args: { range: '1d' },
    };

    expect(parseClientMessage(encodeMessage(history))).toEqual(history);
    const raw: ClientMessage = { type: 'raw', id: 2, integration: 'ha', request: { x: 1 } };
    expect(parseClientMessage(encodeMessage(raw))).toEqual(raw);
    expect(parseClientMessage('{"type":"subscribe","ref":"ha:light.a"}')).toEqual({
      type: 'subscribe',
      ref: 'ha:light.a',
    });
  });

  test.each([
    'not json',
    '[]',
    '{"type":"subscribe","ref":"nocolon"}',
    '{"type":"command","id":"x","ref":"ha:a","command":"b"}',
    '{"type":"command","id":1,"ref":"nocolon","command":"b"}',
    '{"type":"command","id":1,"ref":"ha:a","command":"b","args":[1]}',
    '{"type":"raw","id":1,"integration":"ha"}',
    '{"type":"query","id":1,"ref":"ma:a","query":"nope"}',
    '{"type":"query","id":"x","ref":"ma:a","query":"browse"}',
    '{"type":"query","id":1,"ref":"ma:a","query":"browse","args":[1]}',
    '{"type":"call","id":1,"integration":"ha","domain":"a","service":"b"}',
    '{"type":"unknown"}',
  ])('rejects malformed client frame %s', (raw) => {
    expect(parseClientMessage(raw)).toBeUndefined();
  });

  test('parses server messages', () => {
    expect(parseServerMessage('{"type":"entity","ref":"ha:a.b","entity":null}')).toBeDefined();
    expect(parseServerMessage('{"type":"result","id":1,"ok":false,"error":"x"}')).toBeDefined();
    expect(
      parseServerMessage('{"type":"status","integration":"ha","status":"connected"}'),
    ).toBeDefined();

    expect(parseServerMessage('{"type":"entity","ref":"bad"}')).toBeUndefined();
  });
});

describe('sizedAssetUrl', () => {
  const url = assetUrl('ma', '/imageproxy/abc?size=512&fmt=jpg');

  test('asks for the size, and asks again for another without piling them up', () => {
    expect(sizedAssetUrl(url, 'small')).toBe(`${url}&size=small`);
    expect(sizedAssetUrl(sizedAssetUrl(url, 'small'), 'medium')).toBe(`${url}&size=medium`);
  });

  test('leaves an address that is not the runtime’s alone', () => {
    expect(sizedAssetUrl('https://i.scdn.co/image/xyz', 'small')).toBe(
      'https://i.scdn.co/image/xyz',
    );
  });
});
