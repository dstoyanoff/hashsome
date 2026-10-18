import {
  LocalClient,
  mockAction,
  mockPerson,
  mockSensor,
  mockWeather,
  MockIntegration,
} from '@hashsome/core';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, test, vi } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Clock, DateChip, TopBar, WeatherChip } from '../top-bar.tsx';

const entities = {
  movie_night: mockAction({ name: 'Movie Night' }),
  outdoor_temperature: mockSensor({ value: '12.3', unit: '°C', measurement: 'temperature' }),
  dan: mockPerson({ name: 'Dan' }),
};

test('renders title, fires a scene, and shows weather and presence', () => {
  const { ha } = renderWithMock(
    <TopBar
      title="Downstairs"
      scenes={['ha:movie_night']}
      weather="ha:outdoor_temperature"
      people={['ha:dan']}
    />,
    entities,
  );

  expect(screen.getByRole('heading', { name: 'Downstairs', level: 1 })).toBeTruthy();
  expect(screen.getByText('12°')).toBeTruthy();
  expect(screen.getByText('D')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Movie Night' }));
  expect(ha.calls).toHaveLength(1);
  expect(ha.calls[0]).toMatchObject({ entityId: 'movie_night', command: 'trigger' });
});

test('a press is answered at once for a fixed time, whether or not the scene has answered, and cannot be repeated meanwhile', async () => {
  const { ha } = renderWithMock(
    <TopBar title="Downstairs" scenes={['ha:movie_night']} />,
    entities,
  );

  // A scene that never answers.
  const spy = vi.spyOn(ha, 'command').mockImplementation(() => new Promise(() => {}));
  const scene = screen.getByRole('button', { name: 'Movie Night' }) as HTMLButtonElement;
  expect(screen.queryByRole('status')).toBeNull();
  fireEvent.click(scene);
  expect(spy).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('status', { name: 'Done' })).toBeTruthy();
  expect(scene.disabled).toBe(true);
  fireEvent.click(scene);
  expect(spy).toHaveBeenCalledTimes(1);
  // It plays for its own time and is gone, with the scene still not answered.
  await waitFor(() => expect(screen.queryByRole('status')).toBeNull(), { timeout: 3000 });
  expect(scene.disabled).toBe(false);
  fireEvent.click(scene);
  expect(spy).toHaveBeenCalledTimes(2);
});

test('a scene can override its default icon', () => {
  const { ha } = renderWithMock(
    <TopBar title="Downstairs" scenes={[{ entity: 'ha:movie_night', icon: 'lu:tv' }]} />,
    entities,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Movie Night' }));
  expect(ha.calls).toHaveLength(1);
});

const twoDashboards = [
  { id: 'living-room', title: 'Living Room', icon: 'lu:sofa' },
  { id: 'bedroom', title: 'Bedroom', icon: 'lu:bed' },
] as const;

describe('the header pieces on their own', () => {
  test('the date, the weather and the clock can be used without a top bar', () => {
    renderWithMock(
      <>
        <DateChip />
        <WeatherChip entity="ha:outdoor_temperature" />
        <Clock format="24h" />
      </>,
      entities,
      { router: true },
    );

    expect(screen.queryByRole('heading')).toBeNull();
    expect(screen.getByText(/\w{3} · \w{3} \d{1,2}/)).toBeTruthy(); // Tue · Oct 6
    expect(screen.getByText('12°')).toBeTruthy();
    expect(screen.getByText(/^\d{2}:\d{2}$/)).toBeTruthy();
  });

  test('the clock follows the format it is given', () => {
    renderWithMock(<Clock format="12h" />, {}, { router: true });
    expect(screen.getByText(/^(AM|PM)$/i)).toBeTruthy();
  });
});

test('a title with 2+ dashboards opens a switcher and closes it on pick', () => {
  renderWithMock(
    <TopBar title="Living Room" dashboards={[...twoDashboards]} />,
    {},
    { router: true },
  );

  expect(screen.queryByRole('option', { name: 'Bedroom' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Living Room' }));
  expect(screen.getByRole('option', { name: 'Bedroom' })).toBeTruthy();
  fireEvent.click(screen.getByRole('option', { name: 'Bedroom' }));
  expect(screen.queryByRole('option', { name: 'Bedroom' })).toBeNull();
});

test('the dashboard being shown is marked, and cannot be picked again', () => {
  const { container } = renderWithMock(
    <TopBar title="Living Room" dashboards={[...twoDashboards]} />,
    {},
    { router: true },
  );

  fireEvent.click(screen.getByRole('button', { name: 'Living Room' }));
  const here = screen.getByRole('option', { name: 'Living Room' });
  expect(here.getAttribute('aria-selected')).toBe('true');
  expect((here as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByRole('option', { name: 'Bedroom' }).getAttribute('aria-selected')).toBe(
    'false',
  );

  // Pressing it does nothing: the list stays as it is.
  fireEvent.click(here);
  expect(screen.getByRole('option', { name: 'Bedroom' })).toBeTruthy();
  expect(container).toBeTruthy();
});

test('the closed switcher shows the current dashboard’s icon next to its title', () => {
  const { container } = renderWithMock(
    <MemoryRouter initialEntries={['/bedroom']}>
      <TopBar title="Bedroom" dashboards={[...twoDashboards]} />
    </MemoryRouter>,
  );

  const pill = screen.getByRole('button', { name: 'Bedroom' });
  expect(pill.querySelectorAll('svg')).toHaveLength(2); // its icon and the chevron
  expect(container).toBeTruthy();
});

test('which one is current follows the address', () => {
  renderWithMock(
    <MemoryRouter initialEntries={['/bedroom/lights']}>
      <TopBar title="Whatever" dashboards={[...twoDashboards]} />
    </MemoryRouter>,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Whatever' }));
  expect(screen.getByRole('option', { name: 'Bedroom' }).getAttribute('aria-selected')).toBe(
    'true',
  );
});

test('a press elsewhere, or Escape, closes the switcher', () => {
  renderWithMock(
    <TopBar title="Living Room" dashboards={[...twoDashboards]} />,
    {},
    { router: true },
  );

  const open = () => fireEvent.click(screen.getByRole('button', { name: 'Living Room' }));
  open();
  expect(screen.getByRole('listbox')).toBeTruthy();
  fireEvent.pointerDown(document.body);
  expect(screen.queryByRole('listbox')).toBeNull();

  open();
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('listbox')).toBeNull();

  // A press inside it is not "elsewhere".
  open();
  fireEvent.pointerDown(screen.getByRole('option', { name: 'Bedroom' }));
  expect(screen.getByRole('listbox')).toBeTruthy();
});

test('a title with 0-1 dashboards is plain, non-interactive text', () => {
  renderWithMock(<TopBar title="Living Room" />, {}, { router: true });
  expect(screen.getByRole('heading', { name: 'Living Room', level: 1 })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Living Room' })).toBeNull();
});

test('weather falls back to the status label when the sensor is unavailable', () => {
  renderWithMock(<TopBar title="Downstairs" weather="ha:outdoor_temperature" />, {
    outdoor_temperature: mockSensor({ value: '1', availability: 'unavailable' }),
  });

  expect(screen.getByText('Unavailable')).toBeTruthy();
});

test('a scene without an explicit color still renders (auto-assigned)', () => {
  const { ha } = renderWithMock(
    <TopBar title="Downstairs" scenes={[{ entity: 'ha:movie_night', color: '#123456' }]} />,
    entities,
  );

  const button = screen.getByRole('button', { name: 'Movie Night' });
  expect(computed(button, 'background')).toBe('#123456');
  fireEvent.click(button);
  expect(ha.calls).toHaveLength(1);
});

test('the status dot reports all systems operational and opens per-system detail', () => {
  const { client } = renderWithMock(<TopBar title="Downstairs" />, entities);
  client.connect();
  const dot = screen.getByRole('button', { name: 'All systems operational' });
  fireEvent.click(dot);
  expect(screen.getByRole('status').textContent).toContain('Runtime');
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('status')).toBeNull();
});

test('the status dot flags an integration that drops', () => {
  class Flaky extends MockIntegration {
    drop() {
      this.setStatus('error');
    }
  }
  const flaky = new Flaky({ id: 'ha', entities: {} });
  const client = new LocalClient([flaky]);
  render(
    <HashsomeProvider client={client}>
      <TopBar title="Downstairs" />
    </HashsomeProvider>,
  );

  expect(screen.getByRole('button', { name: 'All systems operational' })).toBeTruthy();
  act(() => flaky.drop());
  fireEvent.click(screen.getByRole('button', { name: '1 system(s) down' }));
  expect(screen.getByRole('status').textContent).toContain('Error');
});

test('a scene dot uses the color its app gave it, and the theme accent otherwise', () => {
  renderWithMock(
    <TopBar
      title="Downstairs"
      scenes={[{ entity: 'ha:movie_night', color: '#123456' }, 'ha:cooking']}
    />,
    { ...entities, cooking: mockAction({ name: 'Cooking' }) },
  );

  expect(computed(screen.getByRole('button', { name: 'Movie Night' }), 'background')).toBe(
    '#123456',
  );

  expect(computed(screen.getByRole('button', { name: 'Cooking' }), 'background')).toBe(
    '#B85C38', // the dark theme's accent
  );
});

test('presence avatars without a picture take their color from the `presenceColors` list', () => {
  renderWithMock(
    <TopBar title="Downstairs" people={['ha:dan']} presenceColors={['#123456']} />,
    entities,
  );

  expect(computed(screen.getByText('D').parentElement as HTMLElement, 'background')).toBe(
    '#123456',
  );
});

test('presence shows who is home normally, and dims anyone who is away', () => {
  renderWithMock(<TopBar title="Downstairs" people={['ha:dan', 'ha:alex']} />, {
    ...entities,
    alex: mockPerson({ name: 'Alex', location: 'away' }),
  });

  const dan = screen.getByRole('img', { name: 'Dan, home' });
  const alex = screen.getByRole('img', { name: 'Alex, away' });
  expect(dan.getAttribute('data-active')).toBe('true');
  expect(alex.getAttribute('data-active')).toBe('false');
  // Faded by an overlay on top of its own color, never by opacity (a see-through avatar would show
  // the one it overlaps) and never recolored.
  expect(getComputedStyle(alex).opacity).not.toBe('0.4');
  expect(getComputedStyle(alex).filter).not.toBe('grayscale(1)');
});

test('your own component sits in the bar, between the weather and the people', () => {
  renderWithMock(
    <TopBar
      title="Home"
      weather="ha:outdoor_temperature"
      people={['ha:dan']}
      extra={<span data-testid="mine">Guard</span>}
    />,
    {
      outdoor_temperature: mockSensor({ value: '16', unit: '°C', measurement: 'temperature' }),
      dan: mockPerson({ name: 'Dan', home: true }),
    },
  );

  const mine = screen.getByTestId('mine');
  const weather = screen.getByText('16°');
  const dan = screen.getByRole('img', { name: /Dan/ });
  expect(weather.compareDocumentPosition(mine) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(mine.compareDocumentPosition(dan) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

describe('weather', () => {
  const pill = (entity: ReturnType<typeof mockWeather>) => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, { sky: entity });
    return screen.getByText(/°$/).parentElement as HTMLElement;
  };

  test('a weather entity shows a rounded temperature and an icon for its condition', () => {
    const sunny = pill(mockWeather({ condition: 'sunny', temperature: 15.6 }));
    expect(sunny.textContent).toBe('16°');
    const sunIcon = sunny.querySelector('svg')?.innerHTML;
    cleanup();

    const rainy = pill(mockWeather({ condition: 'rainy', temperature: 9.2 }));
    expect(rainy.textContent).toBe('9°');
    expect(rainy.querySelector('svg')?.innerHTML).not.toBe(sunIcon);
  });

  test('a condition nobody knows still gets an icon, and no reading says what is wrong', () => {
    expect(
      pill(mockWeather({ condition: 'unknown', temperature: 3 })).querySelector('svg'),
    ).toBeTruthy();

    cleanup();

    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ availability: 'unavailable' }),
    });

    expect(screen.getByText('Unavailable')).toBeTruthy();
  });

  test('the weather reading is in the same type as the date beside it', () => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ condition: 'sunny', temperature: 15 }),
    });

    const date = screen.getByText(/·/);
    const reading = screen.getByText('15°');
    expect(getComputedStyle(reading).fontSize).toBe(getComputedStyle(date).fontSize);
    expect(getComputedStyle(reading).fontWeight).toBe(getComputedStyle(date).fontWeight);
  });

  test('with forecasts, today’s high and low sit under the reading', async () => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ condition: 'sunny', temperature: 12 }),
    });

    expect(await screen.findByText(/^\d+° \/ -?\d+°$/)).toBeTruthy();
  });

  test('tapping it opens the forecast: the next hours and the days ahead', async () => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ name: 'Home weather', condition: 'rainy', temperature: 12, humidity: 70 }),
    });

    fireEvent.click(screen.getByRole('button', { name: 'Home weather: forecast' }));
    expect(await screen.findByText(/^Next 24 hours/)).toBeTruthy();
    expect(await screen.findByText('10 days')).toBeTruthy();
    expect(screen.getByText('Today')).toBeTruthy();
    expect(screen.getByText('Tomorrow')).toBeTruthy();
    // The sky, the humidity and the UV, which the mock gives as 3.
    expect(screen.getByText('Rain · 70% humidity · UV 3 (moderate)')).toBeTruthy();
    // Wind now, and how it blows through the day: 225 degrees is a wind from the south-west.
    expect(screen.getByText(/Wind 14 km\/h from SW/)).toBeTruthy();
  });

  test('expanded, it adds the current readings and a card for each hour', async () => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ name: 'Home weather', condition: 'sunny', temperature: 12 }),
    });

    fireEvent.click(screen.getByRole('button', { name: 'Home weather: forecast' }));
    await screen.findByText(/^Next 24 hours/);
    // Narrow, those readings are not shown...
    expect(screen.queryByText('Feels like')).toBeNull();
    expect(screen.queryByText('Pressure')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    expect(await screen.findByText('Feels like')).toBeTruthy();
    expect(screen.getByText('Pressure')).toBeTruthy();
    expect(screen.getByText('Cloud cover')).toBeTruthy();
    expect(screen.getByText('1015 hPa')).toBeTruthy();
    // ...and each of the 24 hours is a card with its wind and humidity.
    expect((await screen.findAllByText(/^UV \d/)).length).toBeGreaterThan(0);
  });

  test('drawer={false}: the pill still shows the weather and the day’s range, but is not a button', async () => {
    renderWithMock(<WeatherChip entity="ha:sky" drawer={false} />, {
      sky: mockWeather({ name: 'Home weather', condition: 'rainy', temperature: 12 }),
    });

    expect(screen.getByText('12°')).toBeTruthy();
    // The range comes from the forecast and is still shown; only the drawer is gone.
    expect(await screen.findByText(/^\d+° \/ -?\d+°$/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Home weather: forecast' })).toBeNull();
  });

  test('a weather source with no forecasts stays a plain pill', () => {
    renderWithMock(<TopBar title="Home" weather="ha:sky" />, {
      sky: mockWeather({ condition: 'sunny', temperature: 12, forecasts: [] }),
    });

    expect(screen.getByText('12°')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /forecast/ })).toBeNull();
  });

  test('a plain sensor still works, with a fixed sun', () => {
    renderWithMock(<TopBar title="Home" weather="ha:out" />, {
      out: mockSensor({ value: '12.3', unit: '°C' }),
    });

    expect(screen.getByText('12°')).toBeTruthy();
  });
});

describe('dashboard switcher', () => {
  test('each entry is as tall as the button that opens the list, for a touch screen', () => {
    renderWithMock(
      <TopBar
        title="Home"
        dashboards={[
          { id: 'home', title: 'Home' },
          { id: 'kitchen', title: 'Kitchen' },
        ]}
      />,
      {},
      { router: true },
    );

    const trigger = screen.getByRole('button', { name: /Home/ });
    fireEvent.click(trigger);
    const entries = screen.getAllByRole('option');
    expect(entries).toHaveLength(2);
    for (const entry of entries) {
      expect(getComputedStyle(entry).height).toBe(getComputedStyle(trigger).height);
    }
  });
});
