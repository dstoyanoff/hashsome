import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { appHead } from '../root.tsx';

const html = (...args: Parameters<typeof appHead>) =>
  renderToStaticMarkup(<head>{appHead(...args)}</head>);

test('without an app there is nothing to make the document one', () => {
  expect(html(undefined)).toBe('<head></head>');
});

test('an app is standalone once on the home screen, with its name, icon and manifest', () => {
  const markup = html({ name: 'Home', icon: '/icon-180.png' });
  expect(markup).toContain('<meta name="apple-mobile-web-app-capable" content="yes"/>');
  expect(markup).toContain('<meta name="mobile-web-app-capable" content="yes"/>');
  expect(markup).toContain('<meta name="apple-mobile-web-app-title" content="Home"/>');
  expect(markup).toContain('<link rel="apple-touch-icon" href="/icon-180.png"/>');
  expect(markup).toContain('<link rel="manifest" href="/manifest.webmanifest"/>');
});

test('the short name is what goes under the icon, and the manifest can be elsewhere or left out', () => {
  expect(html({ name: 'Home sweet home', shortName: 'Home' })).toContain('content="Home"');
  expect(html({ name: 'Home', manifest: '/app.json' })).toContain(
    '<link rel="manifest" href="/app.json"/>',
  );
  expect(html({ name: 'Home', manifest: false })).not.toContain('rel="manifest"');
  expect(html({ name: 'Home' })).not.toContain('apple-touch-icon');
});

test('the status bar and theme colour follow the theme', () => {
  expect(html({ name: 'Home' }, 'dark')).toContain('content="black"');
  expect(html({ name: 'Home' }, 'dark')).toContain('name="theme-color" content="#101114"');

  expect(html({ name: 'Home' }, 'light')).toContain(
    'apple-mobile-web-app-status-bar-style" content="default"',
  );

  expect(html({ name: 'Home' }, 'light')).toContain('name="theme-color" content="#f4f1eb"');
});
