import { mockMediaPlayer } from '@hashsome/core';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaSpeakers, offeredSpeakers } from '../media-speakers.tsx';

type Ref = `ha:${string}`;
const ref = (id: string) => `ha:${id}` as Ref;

const player = (name: string, others: string[], extra: Record<string, unknown> = {}) =>
  mockMediaPlayer({
    name,
    volume: 0.3,
    groupable: others.map(ref),
    capabilities: { group: true },
    ...extra,
  });

/** Four rooms: the porch is playing, the kitchen is idle, the patio plays something else, the garage is off. */
const rooms = (extra: Record<string, Record<string, unknown>> = {}) => ({
  porch: player('Porch', ['kitchen', 'patio', 'garage'], {
    playback: 'playing',
    media: { title: 'Dreams' },
    ...extra.porch,
  }),
  kitchen: player('Kitchen', ['porch', 'patio', 'garage'], extra.kitchen),
  patio: player('Patio', ['porch', 'kitchen'], {
    playback: 'playing',
    media: { title: 'Blue Monday' },
    ...extra.patio,
  }),
  garage: player('Garage', ['porch', 'kitchen'], {
    availability: 'unavailable',
    playback: 'off',
    ...extra.garage,
  }),
});

const grouped = { leader: ref('porch'), members: [ref('kitchen')] };

test('the speakers offered are the ones it can be grouped with, narrowed to the allowlist where there is one', () => {
  const groupable = [ref('a'), ref('b'), ref('c')];
  expect(offeredSpeakers(groupable, undefined)).toEqual(groupable);
  expect(offeredSpeakers(groupable, [ref('c'), ref('a')])).toEqual([ref('a'), ref('c')]);
  // Allowed but not groupable with it, or nothing to group with at all: not offered.
  expect(offeredSpeakers(groupable, [ref('z')])).toEqual([]);
  expect(offeredSpeakers(undefined, [ref('a')])).toEqual([]);
});

test('alone: this player is the stream, every speaker it can be grouped with is offered, and there is nothing to reset', () => {
  renderWithMock(<MediaSpeakers entity="ha:porch" />, rooms());
  expect(screen.getByText('This stream')).toBeTruthy();
  expect(screen.getByText('Add to this stream')).toBeTruthy();
  for (const name of ['Kitchen', 'Patio', 'Garage']) {
    expect(screen.getByRole('button', { name: `Add ${name} to this stream` })).toBeTruthy();
  }

  expect(screen.queryByRole('button', { name: /Back to just/ })).toBeNull();
  // A speaker that is off is dimmed and cannot be added.
  expect(screen.getByText('Unavailable')).toBeTruthy();
  expect(
    (screen.getByRole('button', { name: 'Add Garage to this stream' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});

test('an allowlist is matched with what the player can be grouped with, and speakers in the stream are always shown', () => {
  renderWithMock(
    <MediaSpeakers entity="ha:porch" speakers={[ref('kitchen'), ref('nobody'), ref('office')]} />,
    rooms(),
  );

  expect(screen.getByRole('button', { name: 'Add Kitchen to this stream' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Add Patio to this stream' })).toBeNull();
  expect(screen.queryByRole('button', { name: /Add Garage/ })).toBeNull();
  // Not groupable with it, so left out whatever the allowlist says: nothing named for them is there.
  expect(screen.getAllByRole('button', { name: /^Add / })).toHaveLength(1);
});

test('adding a speaker asks the stream’s leader to add it, and it joins the stream when it has', async () => {
  const { ha } = renderWithMock(<MediaSpeakers entity="ha:porch" />, rooms());
  fireEvent.click(screen.getByRole('button', { name: 'Add Kitchen to this stream' }));
  expect(ha.calls.at(-1)).toMatchObject({
    entityId: 'porch',
    command: 'setGroupMembers',
    args: { add: [ref('kitchen')] },
  });

  // It is in the stream now, with its own volume (and the level beside it) and a button to take it out. With just
  // one other player there is nothing to take out all at once.
  expect(
    await screen.findByRole('button', { name: 'Take Kitchen out of this stream' }),
  ).toBeTruthy();

  expect(screen.getByRole('slider', { name: 'Kitchen volume' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Add Kitchen to this stream' })).toBeNull();
});

test('a speaker that is playing something else asks first, and nothing happens until it is confirmed', async () => {
  const { ha } = renderWithMock(<MediaSpeakers entity="ha:porch" />, rooms());
  fireEvent.click(screen.getByRole('button', { name: 'Add Patio to this stream' }));
  const dialog = await screen.findByRole('dialog', { name: 'Play this on Patio?' });
  expect(dialog.textContent).toContain('Blue Monday');
  expect(ha.calls).toHaveLength(0);
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(ha.calls).toHaveLength(0);

  fireEvent.click(screen.getByRole('button', { name: 'Add Patio to this stream' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Go ahead' }));
  await waitFor(() =>
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'setGroupMembers',
      args: { add: [ref('patio')] },
    }),
  );
});

test('taking a speaker out asks the leader to remove it', async () => {
  const { ha } = renderWithMock(
    <MediaSpeakers entity="ha:porch" />,
    rooms({ porch: { group: grouped }, kitchen: { group: grouped, playback: 'playing' } }),
  );

  fireEvent.click(screen.getByRole('button', { name: 'Take Kitchen out of this stream' }));
  expect(ha.calls.at(-1)).toMatchObject({
    entityId: 'porch',
    command: 'setGroupMembers',
    args: { remove: [ref('kitchen')] },
  });

  await waitFor(() =>
    expect(screen.queryByRole('button', { name: /Take Kitchen out/ })).toBeNull(),
  );

  expect(screen.getByRole('button', { name: 'Add Kitchen to this stream' })).toBeTruthy();
});

test('a leader with one other speaker leaves in one press, handing the stream to it', async () => {
  const { ha } = renderWithMock(
    <MediaSpeakers entity="ha:porch" />,
    rooms({ porch: { group: grouped }, kitchen: { group: grouped } }),
  );

  // No "Remove all", and no one to pick: there is only the kitchen to lead.
  expect(screen.queryByRole('button', { name: /Take everyone else out/ })).toBeNull();
  expect(screen.queryByRole('button', { name: /^Hand this stream to/ })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /^Leave this stream; Kitchen carries on/ }));
  expect(ha.calls.at(-1)).toMatchObject({ entityId: 'kitchen', command: 'takeOverGroup' });

  // The porch is out and the kitchen is alone with what was playing.
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: /^Leave this stream/ })).toBeNull(),
  );
});

test('a leader with several others picks which one leads, and leaves', async () => {
  const both = { leader: ref('porch'), members: [ref('kitchen'), ref('patio')] };
  const { ha } = renderWithMock(
    <MediaSpeakers entity="ha:porch" />,
    rooms({ porch: { group: both }, kitchen: { group: both }, patio: { group: both } }),
  );

  expect(screen.queryByRole('button', { name: /Take everyone else out/ })).toBeNull();
  // Leaving is not one press: which of the two leads is for the leader to say.
  expect(screen.queryByRole('button', { name: /^Leave this stream/ })).toBeNull();
  // Each one can still be taken out.
  expect(screen.getByRole('button', { name: 'Take Kitchen out of this stream' })).toBeTruthy();

  fireEvent.click(
    screen.getByRole('button', { name: 'Hand this stream to Patio; Porch leaves it' }),
  );

  expect(ha.calls.at(-1)).toMatchObject({ entityId: 'patio', command: 'takeOverGroup' });
});

test('a follower can leave, or take the stream over by removing the player that leads it', async () => {
  const leave = renderWithMock(
    <MediaSpeakers entity="ha:kitchen" />,
    rooms({ porch: { group: grouped }, kitchen: { group: grouped } }),
  );

  // The stream is the porch's: it is shown, leading, and the follower can take it over by removing it.
  expect(screen.getByText('Leading')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Take Kitchen out of this stream' }));
  expect(leave.ha.calls.at(-1)).toMatchObject({ entityId: 'kitchen', command: 'leaveGroup' });
  await waitFor(() => expect(screen.queryByText('Leading')).toBeNull());
  leave.unmount();

  const takeOver = renderWithMock(
    <MediaSpeakers entity="ha:kitchen" />,
    rooms({ porch: { group: grouped }, kitchen: { group: grouped } }),
  );

  fireEvent.click(screen.getByRole('button', { name: /Take Porch out of this stream/ }));
  expect(takeOver.ha.calls.at(-1)).toMatchObject({
    entityId: 'kitchen',
    command: 'takeOverGroup',
  });

  // Porch is out, and the kitchen leads what is left.
  await waitFor(() => expect(screen.queryByText('Leading')).toBeNull());
  expect(screen.queryByRole('button', { name: /Take Porch out/ })).toBeNull();
});

test('joining a stream playing elsewhere asks its leader to add this player, after asking if this one is playing', async () => {
  const idle = renderWithMock(<MediaSpeakers entity="ha:kitchen" />, rooms());
  fireEvent.click(screen.getByRole('button', { name: 'Join Patio’s stream'.replace('’', "'") }));
  expect(idle.ha.calls.at(-1)).toMatchObject({
    entityId: 'patio',
    command: 'setGroupMembers',
    args: { add: [ref('kitchen')] },
  });

  idle.unmount();
  const busy = renderWithMock(<MediaSpeakers entity="ha:porch" />, rooms());

  fireEvent.click(screen.getByRole('button', { name: "Join Patio's stream" }));
  const dialog = await screen.findByRole('dialog', { name: "Join Patio's stream?" });
  expect(dialog.textContent).toContain('stop what it plays');
  expect(busy.ha.calls).toHaveLength(0);
  fireEvent.click(screen.getByRole('button', { name: 'Go ahead' }));
  await waitFor(() =>
    expect(busy.ha.calls.at(-1)).toMatchObject({
      entityId: 'patio',
      command: 'setGroupMembers',
      args: { add: [ref('porch')] },
    }),
  );
});

test('a speaker in a stream elsewhere is joined through its leader', async () => {
  const other = { leader: ref('patio'), members: [ref('garage')] };
  const { ha } = renderWithMock(
    <MediaSpeakers entity="ha:kitchen" />,
    rooms({
      patio: { group: other },
      garage: { group: other, availability: 'ready', playback: 'playing' },
    }),
  );

  fireEvent.click(screen.getByRole('button', { name: "Join Garage's stream" }));
  expect(ha.calls.at(-1)).toMatchObject({ entityId: 'patio', command: 'setGroupMembers' });
});

test('each speaker in the stream has its own volume', async () => {
  const { ha } = renderWithMock(
    <MediaSpeakers entity="ha:porch" />,
    rooms({ porch: { group: grouped }, kitchen: { group: grouped, volume: 0.6 } }),
  );

  const slider = screen.getByRole('slider', { name: 'Kitchen volume' });
  expect(slider.getAttribute('aria-valuenow')).toBe('60');
  Object.defineProperty(slider, 'getBoundingClientRect', {
    value: () => ({ left: 0, width: 200, top: 0, height: 10, right: 200, bottom: 10, x: 0, y: 0 }),
  });

  fireEvent.pointerDown(slider, { clientX: 40, pointerId: 1 });
  fireEvent.pointerUp(slider, { clientX: 40, pointerId: 1 });
  await act(async () => {});
  expect(ha.calls.at(-1)).toMatchObject({
    entityId: 'kitchen',
    command: 'setVolume',
    args: { volume: 0.2 },
  });
});

test('a player that cannot be grouped, and one that is not there yet, say so', () => {
  const { unmount } = renderWithMock(<MediaSpeakers entity="ha:solo" />, {
    solo: mockMediaPlayer({ name: 'Solo' }),
  });

  expect(screen.getByText(/cannot be grouped/)).toBeTruthy();
  unmount();
  renderWithMock(<MediaSpeakers entity="ha:nobody" />, {});
  expect(document.querySelector('[aria-busy="true"]')).not.toBeNull();
});
