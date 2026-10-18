import { useLayoutEffect, useRef, useState } from 'react';
import { useDensity } from '../theme/use-density.ts';
import { gridMetrics, offGrid } from '../theme/grid.ts';
import styles from './grid-overlay.module.css';

interface Measured {
  /** Where the grid starts, in the page's scrolling area: its edge, less what centering left over. */
  top: number;
  width: number;
  height: number;

  /** The page's padding, which is the first modules of the grid: where the cards start. */
  content: { left: number; top: number; right: number; bottom: number };

  /** What the page centered the grid by, as `top bottom` px. */
  centered: string;

  /** What the device is: the visible viewport, the whole screen and the pixel ratio, for sizing a page for it. */
  device: string;
  cards: { x: number; y: number; width: number; height: number; off: boolean }[];
}

const SAME = (a: Measured | null, b: Measured) =>
  a !== null && JSON.stringify(a) === JSON.stringify(b);

/** The viewport the page has now (it is smaller than the screen while the browser shows bars), the
 * screen, both in CSS px, and the pixel ratio: multiply by it for the panel's own pixels. */
function describeDevice(): string {
  const ratio = Math.round(window.devicePixelRatio * 100) / 100;
  return `viewport ${window.innerWidth}×${window.innerHeight} · screen ${window.screen.width}×${window.screen.height} @${ratio}`;
}

/** Draws the grid over the page it sits in (the `Page`, when the debug menu has it on): a faint
 * line at every module, a stronger one at every tile pitch, and a box round every card (anything
 * marked `data-grid-card`) with its height in modules, green when its top and height are on the grid
 * and red when they are not. For laying out a dashboard for one device: see where things fall, and
 * what to change. Never takes a touch. */
export function GridOverlay() {
  const metrics = gridMetrics(useDensity().space);
  const { module, tilePitch } = metrics;
  const ref = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<Measured | null>(null);

  useLayoutEffect(() => {
    const host = ref.current?.parentElement;
    if (!host) {
      return;
    }

    let frame = 0;
    const measure = () => {
      const style = getComputedStyle(host);
      const centered = host.dataset['centered'] ?? '';
      const [slack = 0, slackBelow = 0] = centered.split(' ').map(Number);
      // The grid spans the whole page, its padding included: it starts at the page's edge, less the
      // sliver centering left over, so the padding is its first modules and the cards start after it.
      const top = slack;
      const box = host.getBoundingClientRect();
      const next: Measured = {
        top,
        width: host.scrollWidth,
        height: host.scrollHeight - slack - slackBelow,
        content: {
          left: parseFloat(style.paddingLeft),
          top: parseFloat(style.paddingTop) - slack,
          right: parseFloat(style.paddingRight),
          bottom: parseFloat(style.paddingBottom) - slackBelow,
        },
        centered,
        device: describeDevice(),
        cards: [...host.querySelectorAll('[data-grid-card]')].map((card) => {
          const rect = card.getBoundingClientRect();
          const x = rect.left - box.left + host.scrollLeft;
          const y = rect.top - box.top + host.scrollTop - top;

          return {
            x: Math.round(x * 10) / 10,
            y: Math.round(y * 10) / 10,
            width: Math.round(rect.width * 10) / 10,
            height: Math.round(rect.height * 10) / 10,
            off: offGrid(y, rect.height, module),
          };
        }),
      };

      setView((current) => (SAME(current, next) ? current : next));
    };

    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(measure);
    };

    schedule();
    window.addEventListener('resize', schedule);
    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(schedule);
    resize?.observe(host);
    // What the page is made of changing (a card appearing, a size changing): not this overlay's own.
    const mutations = new MutationObserver((records) => {
      if (records.some((record) => !ref.current?.contains(record.target))) {
        schedule();
      }
    });

    mutations.observe(host, { subtree: true, childList: true, attributes: true });
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('resize', schedule);
      resize?.disconnect();
      mutations.disconnect();
    };
  }, [module]);

  // A line every module, unless that is too fine to read, then every gap (3 modules).
  const minor = module >= 3.5 ? module : module * 3;
  const off = view?.cards.filter((card) => card.off).length ?? 0;
  const [top = 0, bottom = 0] = (view?.centered ?? '').split(' ').map(Number);

  return (
    <div ref={ref} className={styles.overlay} data-grid-overlay aria-hidden="true">
      {view ? (
        <>
          <div
            className={styles.grid}
            style={{
              top: view.top,
              width: view.width,
              height: view.height,
              backgroundImage: [
                `linear-gradient(to bottom, rgba(0, 150, 255, 0.5) 1px, transparent 1px)`,
                `linear-gradient(to right, rgba(0, 150, 255, 0.5) 1px, transparent 1px)`,
                `linear-gradient(to bottom, rgba(0, 150, 255, 0.12) 1px, transparent 1px)`,
                `linear-gradient(to right, rgba(0, 150, 255, 0.12) 1px, transparent 1px)`,
              ].join(', '),
              backgroundSize: [
                `100% ${tilePitch}px`,
                `${tilePitch}px 100%`,
                `100% ${minor}px`,
                `${minor}px 100%`,
              ].join(', '),
            }}
          >
            <div
              className={styles.content}
              style={{
                left: view.content.left,
                top: view.content.top,
                right: view.content.right,
                bottom: view.content.bottom,
              }}
            />
            {view.cards.map((card) => (
              <div
                key={`${card.x}:${card.y}:${card.width}:${card.height}`}
                className={styles.card}
                data-off={card.off}
                style={{ left: card.x, top: card.y, width: card.width, height: card.height }}
              >
                {card.height >= 14 ? `${Math.round((card.height / module) * 10) / 10}u` : null}
              </div>
            ))}
          </div>
          <div className={styles.legend}>
            {`grid · 1u = ${Math.round(module * 100) / 100}px · gap 3u · tile ${Math.round(metrics.tile / module)}u · ${off === 0 ? 'all on grid' : `${off} off grid`}${top + bottom > 0 ? ` · centered +${top}/${bottom}px` : ''}${view.device ? ` · ${view.device}` : ''}`}
          </div>
        </>
      ) : null}
    </div>
  );
}
