import type { EntityRef } from '@hashsome/core';
import { useState } from 'react';
import { useEntityLogbook } from '../use-entity-logbook.ts';
import { HistorySection } from './history-section.tsx';

/** A drawer's History section filled from the backend: folded away, and it asks for the entity's
 * recent activity only when the person opens it, with placeholder rows until the answer comes. */
export function LogbookHistory({ entity }: { entity: EntityRef }) {
  const [asked, setAsked] = useState(false);
  const { entries, loading } = useEntityLogbook(asked ? entity : undefined);
  return <HistorySection entries={entries} loading={loading} onOpen={() => setAsked(true)} />;
}
