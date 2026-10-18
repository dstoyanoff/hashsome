import type { EntityRef } from '@hashsome/core';

/** The speakers to offer: the ones the player can be grouped with, narrowed to an allowlist when there is one. */
export function offeredSpeakers(
  groupable: readonly EntityRef[] | undefined,
  allowed: readonly EntityRef[] | undefined,
): EntityRef[] {
  const can = groupable ?? [];
  return allowed ? can.filter((ref) => allowed.includes(ref)) : [...can];
}
