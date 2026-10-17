import { lazy, Suspense, type ComponentType } from 'react';

/** A component of a module that is fetched when it is first drawn, not with the page: the bodies of drawers and overlays, which most
 * pages never open. Nothing shows in its place while the module comes, and what is drawn beside it is not held back. */
export function lazyExport<P extends object>(
  load: () => Promise<Record<string, unknown>>,
  name: string,
): ComponentType<P> {
  const Loaded = lazy(async () => ({ default: (await load())[name] as ComponentType<P> }));
  return function Lazy(props: P) {
    return (
      <Suspense fallback={null}>
        <Loaded {...props} />
      </Suspense>
    );
  };
}
