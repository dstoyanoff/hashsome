import {
  LocalClient,
  mockAction,
  mockClimate,
  mockLibrary,
  mockLight,
  mockMediaPlayer,
  mockPerson,
  mockSensor,
  mockWeather,
  MockIntegration,
  type BrowseQuery,
  type BrowseResult,
  type EntityInput,
} from '@hashsome/core';

/** Album art for the gallery's playing demo, served by the docs dev server from `docs/public/`. */
// Under the base path the site is served from (`/hash/` on GitHub Pages), not the domain's root.
const DEMO_ARTWORK = `${import.meta.env.BASE_URL}artwork.jpg`;

const power = (watts: string): EntityInput =>
  mockSensor({ name: 'Power', value: watts, unit: 'W', measurement: 'power' });

const energy = (kwh: string): EntityInput =>
  mockSensor({ name: 'Energy', value: kwh, unit: 'kWh', measurement: 'energy' });

/** The gallery's backend: the mock, with one player whose shelves never finish loading, to show the
 * loading state of the library. */
class GalleryIntegration extends MockIntegration {
  override browse(entityId: string, query: BrowseQuery): Promise<BrowseResult> {
    return entityId === 'media_player.loading' && query.path !== undefined
      ? new Promise(() => {})
      : super.browse(entityId, query);
  }
}

/** Mock entities covering every state the components handle. Refs are `ha:<id>`. */
export function createGalleryIntegration() {
  return new GalleryIntegration({
    library: mockLibrary(),
    entities: {
      'light.plain_on': mockLight({ name: 'Lamp', on: true }),
      'light.plain_off': mockLight({ name: 'Stairs Lamp' }),
      'light.dimmable': mockLight({ name: 'Bathroom LED', on: true, brightness: 0.6 }),
      'light.color': mockLight({
        name: 'Night Lamp',
        on: true,
        brightness: 1,
        color: { mode: 'temperature', kelvin: 2700 },
        capabilities: {
          colorTemperature: true,
          color: true,
          kelvinRange: { min: 2000, max: 6500 },
        },
      }),
      'light.cct': mockLight({
        name: 'Desk Lamp',
        on: true,
        brightness: 0.78,
        color: { mode: 'temperature', kelvin: 4000 },
        capabilities: { colorTemperature: true, kelvinRange: { min: 2000, max: 6500 } },
      }),
      'light.unavailable': mockLight({ name: 'Porch Lamp', availability: 'unavailable' }),
      'climate.heater': mockClimate({
        name: 'Living Room Heater',
        mode: 'heat',
        action: 'heating',
        humidity: 48,
        targetTemperature: 17,
        currentTemperature: 16.4,
        capabilities: { modes: ['off', 'heat'], step: 0.5, range: { min: 5, max: 30 } },
      }),
      'climate.off': mockClimate({
        name: 'Office Thermostat',
        mode: 'off',
        action: 'off',
        humidity: 41,
        targetTemperature: 21.5,
        currentTemperature: 18.8,
        preset: 'home',
        capabilities: {
          modes: ['off', 'heat', 'cool', 'auto'],
          presets: ['home', 'away', 'sleep'],
        },
      }),
      'climate.unavailable': mockClimate({ name: 'Guest Heater', availability: 'unavailable' }),
      'sensor.heater_power': power('1180'),
      'sensor.heater_energy': energy('84.3'),
      'sensor.thermostat_power': power('0'),
      'sensor.thermostat_energy': energy('31.2'),
      'sensor.lamp_power': power('9'),
      'sensor.lamp_energy': energy('1.64'),
      'sensor.led_power': power('7'),
      'sensor.led_energy': energy('3.2'),
      'weather.home': mockWeather({
        name: 'Home',
        condition: 'partlycloudy',
        temperature: 12.3,
        humidity: 71,
      }),
      'sensor.temperature': mockSensor({ value: '18.04', unit: '°C', measurement: 'temperature' }),
      'sensor.humidity': mockSensor({ value: '64.41', unit: '%', measurement: 'humidity' }),
      'sensor.bedroom_humidity': mockSensor({
        name: 'Bedroom humidity',
        value: '22.3',
        unit: '%',
        measurement: 'humidity',
      }),
      'sensor.office_humidity': mockSensor({
        name: 'Office humidity',
        value: '45.8',
        unit: '%',
        measurement: 'humidity',
      }),
      'sensor.unavailable': mockSensor({
        value: '0',
        measurement: 'temperature',
        availability: 'unavailable',
      }),
      'sensor.unknown': mockSensor({
        value: '0',
        measurement: 'humidity',
        availability: 'unknown',
      }),
      'scene.tv_time': mockAction({ name: 'TV Time' }),
      'script.shower': mockAction({ name: 'Shower' }),
      'media_player.living_room': mockMediaPlayer({
        name: 'Living Room',
        playback: 'playing',
        source: { title: 'Daily Mix 2', kind: 'playlist' },
        media: {
          title: 'Blank Space',
          artist: 'More More',
          album: '1989',
          artworkUrl: DEMO_ARTWORK,
        },
        volume: 0.4,
        position: 64,
        duration: 231,
        positionUpdatedAt: new Date().toISOString(),
        shuffle: false,
        capabilities: { browse: true, search: true, seek: true, shuffle: true, queue: true },
      }),
      'media_player.loading': mockMediaPlayer({
        name: 'Slow library',
        capabilities: { browse: true, search: true },
      }),
      // Players that can be grouped, for the speakers picker: a stream led by the porch with the bedroom in it, one
      // playing something else, one on its own, and one that is off.
      'media_player.porch': mockMediaPlayer({
        name: 'Porch',
        playback: 'playing',
        media: { title: 'Dreams', artist: 'Fleetwood Mac', artworkUrl: DEMO_ARTWORK },
        volume: 0.35,
        group: { leader: 'ha:media_player.porch', members: ['ha:media_player.bedroom'] },
        groupable: [
          'ha:media_player.bedroom',
          'ha:media_player.patio',
          'ha:media_player.office',
          'ha:media_player.garage',
        ],
        capabilities: { group: true },
      }),
      'media_player.bedroom': mockMediaPlayer({
        name: 'Bedroom',
        playback: 'playing',
        media: { title: 'Dreams', artist: 'Fleetwood Mac', artworkUrl: DEMO_ARTWORK },
        volume: 0.2,
        group: { leader: 'ha:media_player.porch', members: ['ha:media_player.bedroom'] },
        groupable: ['ha:media_player.porch', 'ha:media_player.patio', 'ha:media_player.office'],
        capabilities: { group: true },
      }),
      'media_player.patio': mockMediaPlayer({
        name: 'Patio',
        playback: 'playing',
        media: { title: 'Blue Monday', artist: 'New Order' },
        volume: 0.5,
        groupable: ['ha:media_player.porch', 'ha:media_player.bedroom', 'ha:media_player.office'],
        capabilities: { group: true },
      }),
      'media_player.office': mockMediaPlayer({
        name: 'Office',
        volume: 0.15,
        groupable: [
          'ha:media_player.porch',
          'ha:media_player.bedroom',
          'ha:media_player.patio',
          'ha:media_player.garage',
        ],
        capabilities: { group: true },
      }),
      'media_player.garage': mockMediaPlayer({
        name: 'Garage',
        availability: 'unavailable',
        playback: 'off',
        capabilities: { group: true },
      }),
      'media_player.off': mockMediaPlayer({
        name: 'Kitchen Speaker',
        availability: 'unavailable',
        playback: 'off',
      }),
      'person.dan': mockPerson({ name: 'Dan' }),
      'person.alex': mockPerson({ name: 'Alex', location: 'not_home' }),
    },
  });
}

export function createGalleryClient() {
  return new LocalClient([createGalleryIntegration()]);
}
