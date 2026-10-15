import { mockAction, mockSwitch } from '@hashsome/core';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import type { EntityHandle } from '../../entity-handle.ts';
import { ActionButton } from '../action-button.tsx';
import { SceneButton } from '../scene-button.tsx';

test('scene button triggers the scene, labelled with its name', () => {
  const { ha } = renderWithMock(<SceneButton entity="ha:tv" />, {
    tv: mockAction({ name: 'tv time' }),
  });

  fireEvent.click(screen.getByRole('button', { name: 'tv time' }));
  expect(ha.calls).toEqual([{ entityId: 'tv', command: 'trigger' }]);
});

test('a scene button given a color fills its icon badge with it, and a plain one does not', () => {
  const { container } = renderWithMock(
    <>
      <SceneButton entity="ha:tv" icon="lu:tv" color="#8B5CF6" />
      <SceneButton entity="ha:cook" icon="lu:chef-hat" />
    </>,
    { tv: mockAction({ name: 'tv time' }), cook: mockAction({ name: 'cooking' }) },
  );

  const badges = [...container.querySelectorAll('svg')].map((icon) => icon.parentElement!);
  expect(getComputedStyle(badges[0]!).backgroundColor).toBe('rgb(139, 92, 246)');
  expect(getComputedStyle(badges[1]!).backgroundColor).not.toBe('rgb(139, 92, 246)');
});

test('an action button runs an action entity and shows feedback', async () => {
  const { ha, container } = renderWithMock(<ActionButton entity="ha:shower" label="shower" />, {
    shower: mockAction(),
  });

  fireEvent.click(screen.getByRole('button', { name: 'shower' }));
  expect(ha.calls).toEqual([{ entityId: 'shower', command: 'trigger' }]);
  await waitFor(() => expect(container.querySelector('[data-feedback="done"]')).not.toBeNull());
});

test('a switch is flipped, and the label defaults to its name', () => {
  const { ha } = renderWithMock(<ActionButton entity="ha:coffee" />, {
    coffee: mockSwitch({ name: 'Coffee Maker' }),
  });

  fireEvent.click(screen.getByRole('button', { name: 'Coffee Maker' }));
  expect(ha.calls).toEqual([{ entityId: 'coffee', command: 'toggle' }]);
});

test('an onPress replaces the entity, and failures are flagged with the error', async () => {
  const onPress = vi.fn<() => Promise<unknown>>().mockRejectedValue(new Error('no luck'));
  const { container } = renderWithMock(<ActionButton label="x" onPress={onPress} />);
  fireEvent.click(screen.getByRole('button', { name: 'x' }));
  expect(onPress).toHaveBeenCalled();
  await waitFor(() => expect(container.querySelector('[data-feedback="error"]')).not.toBeNull());
  expect(screen.getByRole('button', { name: 'x' }).title).toBe('no luck');
});

test('an entity of the wrong kind or a missing one is disabled and says why', () => {
  renderWithMock(
    <>
      <ActionButton entity="ha:gone" label="gone" />
      <ActionButton entity="ha:lamp" label="lamp" />
    </>,
    {
      lamp: {
        kind: 'light',
        name: 'L',
        availability: 'ready',
        on: false,
        capabilities: { brightness: false, colorTemperature: false, color: false },
      },
    },
  );

  expect(screen.getByRole('button', { name: 'gone' }).textContent).toContain('Not found');
  expect(screen.getByRole('button', { name: 'lamp' }).textContent).toContain('Unsupported');
});

test('a handle drives the button with no backend at all', () => {
  const command = vi.fn<() => Promise<void>>().mockResolvedValue(undefined);
  const handle = {
    status: 'ready',
    entity: { ...mockAction({ name: 'Custom' }), ref: 'x:y' },
    command,
  } as unknown as EntityHandle<'action'>;

  renderWithMock(<ActionButton entity={handle} />);
  fireEvent.click(screen.getByRole('button', { name: 'Custom' }));
  expect(command).toHaveBeenCalledWith('trigger');
});
