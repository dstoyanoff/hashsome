import type { Integration } from '@hashsome/core';
import express, { type Express } from 'express';
import { createServer } from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { VERSION_FILE, VERSION_PATH } from '../build-id.ts';
import type { ResolvedConfig } from '../config.ts';
import { serveAsset } from './assets.ts';
import { homeAssistantCompat } from './ha-compat.ts';
import { Proxy } from './proxy.ts';
import { startIntegrations } from './integrations.ts';
import { VARIANTS } from './precompress.ts';
import { attachWebSocket } from './websocket.ts';

/** The id the build left in the client directory; none for a client built without one. */
function readVersion(clientDir: string): string | undefined {
  try {
    const { id } = JSON.parse(readFileSync(join(clientDir, VERSION_FILE), 'utf8')) as {
      id?: unknown;
    };

    return typeof id === 'string' && id !== '' ? id : undefined;
  } catch {
    return undefined;
  }
}

export function createApp(
  clientDir: string,
  integrations: Integration[] = [],
  options: { homeAssistantCompat?: boolean } = {},
): Express {
  const app = express();
  app.disable('x-powered-by');
  if (options.homeAssistantCompat) {
    app.use(homeAssistantCompat());
  }

  app.get('/healthz', (_req, res) => {
    res.json({ status: 'ok' });
  });

  // Which build this is, for a page that has been open a while to compare with its own and reload.
  // Never cached: a stale answer is exactly what it is there to avoid.
  const version = readVersion(clientDir);
  app.get(VERSION_PATH, (_req, res) => {
    res.setHeader('cache-control', 'no-store');
    if (version) {
      res.json({ id: version });
    } else {
      res.status(404).end();
    }
  });

  app.use((req, res, next) => {
    serveAsset(integrations, req, res).then((served) => (served ? undefined : next()), next);
  });

  // The build's own files (`/assets`, named by what is in them) never change under a name: a browser keeps them for good, and a
  // reload of a display that has them downloads nothing and reuses the code it has already compiled. They are sent compressed from the
  // copies `hashsome build` made.
  app.use('/assets', (req, res, next) => {
    res.setHeader('cache-control', 'public, max-age=31536000, immutable');
    res.setHeader('vary', 'Accept-Encoding');
    const accepted = String(req.headers['accept-encoding'] ?? '')
      .split(',')
      .map((token) => token.trim().split(';')[0]);

    for (const { encoding, extension } of VARIANTS) {
      const copy = join(clientDir, 'assets', `${req.path}${extension}`);
      if (accepted.includes(encoding) && existsSync(copy)) {
        res.setHeader('content-encoding', encoding);
        res.type(extname(req.path));
        res.sendFile(copy, { cacheControl: false }, (error) => (error ? next(error) : undefined));
        return;
      }
    }

    next();
  });

  // Everything else may change under its name (the page, the version, the icons): the page is asked for again each time, which
  // costs a "not modified" when it has not.
  app.use(
    express.static(clientDir, {
      index: false,
      maxAge: '1h',
      setHeaders: (res, path) => {
        if (path.endsWith('index.html')) {
          res.setHeader('cache-control', 'no-cache');
        }
      },
    }),
  );

  // SPA fallback: client-side routing handles /{id}/...
  app.use((req, res) => {
    // A path that names a file (it has an extension) and was not found is not a page. Answering it with the app's HTML
    // as a 200 makes whoever asked take that for the file: iOS probes /apple-touch-icon.png and
    // /apple-touch-icon-precomposed.png before it reads the page's own icon link, and gave up on icons altogether.
    if (extname(req.path) !== '') {
      res.status(404).end();
      return;
    }

    res.sendFile(join(clientDir, 'index.html'));
  });

  return app;
}

/** Where `hashsome build` puts the client, under a project. A packaged release keeps it elsewhere. */
export const BUILT_CLIENT = join('build', 'client');

export function startServer(config: ResolvedConfig, clientDir = join(config.root, BUILT_CLIENT)) {
  const server = createServer(
    createApp(clientDir, config.integrations, {
      homeAssistantCompat: config.homeAssistantCompat,
    }),
  );

  const proxy = new Proxy(config.integrations, { log: console.log });
  attachWebSocket(server, proxy);
  const stop = startIntegrations(config.integrations, { log: console.log });
  server.listen(config.port, config.host, () => {
    console.log(`hashsome listening on http://${config.host}:${config.port}`);
  });

  const shutdown = () => {
    stop();
    server.close(() => process.exit(0));
    server.closeAllConnections();
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  return server;
}
