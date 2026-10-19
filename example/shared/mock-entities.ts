import {
  mockAction,
  mockClimate,
  mockLight,
  mockPerson,
  mockSensor,
  mockSwitch,
  mockWeather,
} from '@hashsome/core';

// The entities the example's mock Home Assistant has, one shared list for every dashboard: two can
// legitimately reference the same real device (e.g. dashboards/home's kitchen section and
// dashboards/kitchen both use `light.kitchen_ceiling`) — reuse the existing key rather than
// re-declaring it, and check for a key collision before adding a new entity here, since a
// duplicate key silently overrides the earlier one with no error from lint/typecheck/tests.
// Kept in its own file so the live demo (`demo/`) can run the same devices in the browser.
/** The power and energy sensors a smart plug or relay reports for a device (a Shelly, say): what it
 * draws now, and its lifetime total. The mock backend invents their history. */
function powerMonitor(id: string, name: string, watts: number, lifetimeKwh: number) {
  return {
    [`sensor.${id}_power`]: mockSensor({
      name: `${name} power`,
      value: String(watts),
      unit: 'W',
      measurement: 'power',
    }),
    [`sensor.${id}_energy`]: mockSensor({
      name: `${name} energy`,
      value: String(lifetimeKwh),
      unit: 'kWh',
      measurement: 'energy',
    }),
  };
}

export const mockEntities = {
  ...powerMonitor('living_room_lamp', 'Living room lamp', 38, 84.2),
  ...powerMonitor('living_room_wall', 'Living room wall', 55, 131.7),
  ...powerMonitor('living_room_accent', 'Living room accent', 12, 22.5),
  ...powerMonitor('living_room_heater', 'Living room heater', 900, 612.4),
  ...powerMonitor('kitchen_ceiling', 'Kitchen ceiling', 42, 96.8),
  ...powerMonitor('kitchen_led', 'Kitchen LED', 14, 31.1),
  ...powerMonitor('kitchen_island', 'Kitchen island', 28, 40.3),
  ...powerMonitor('porch_ambient', 'Porch ambient', 18, 47.9),
  ...powerMonitor('master_bedroom_lamp', 'Master bedroom lamp', 9, 12.4),
  ...powerMonitor('master_bedroom_heater', 'Master bedroom heater', 650, 388.2),
  ...powerMonitor('bathroom_led', 'Bathroom LED', 10, 18.6),
  ...powerMonitor('office_heater', 'Office heater', 700, 241.5),

  // dashboards/hello
  'light.lamp': mockLight({ name: 'Lamp', on: false, brightness: 0.5 }),
  'climate.heater': mockClimate({
    name: 'Heater',
    mode: 'heat',
    targetTemperature: 17,
    currentTemperature: 16.4,
  }),
  'sensor.temperature': mockSensor({
    name: 'Temperature',
    value: '18.04',
    unit: '°C',
    measurement: 'temperature',
  }),
  // global layout config (the top bar's weather, with its forecast) — shared across every dashboard
  'weather.home': mockWeather({
    name: 'Home',
    condition: 'partlycloudy',
    temperature: 12.3,
    humidity: 71,
  }),
  'person.dan': mockPerson({
    name: 'Dan',
    location: 'home',
    pictureUrl: 'https://i.pravatar.cc/100?img=12',
  }),
  'person.alex': mockPerson({
    name: 'Alex',
    location: 'away',
    pictureUrl: 'https://i.pravatar.cc/100?img=47',
  }),
  // dashboards/home
  'sensor.living_room_temperature': mockSensor({
    name: 'Living room temperature',
    value: '18.0',
    unit: '°C',
    measurement: 'temperature',
  }),
  'sensor.living_room_humidity': mockSensor({
    name: 'Living room humidity',
    value: '64.41',
    unit: '%',
    measurement: 'humidity',
  }),
  // A battery that has run down, for a sensor's low-battery state.
  'sensor.living_room_temperature_battery': mockSensor({
    name: 'Living room temperature battery',
    value: '12',
    unit: '%',
    measurement: 'battery',
  }),
  'light.living_room_lamp': mockLight({ name: 'Living room lamp', on: true }),
  'light.living_room_wall': mockLight({
    name: 'Living room wall',
    on: true,
    brightness: 0.78,
  }),
  'light.living_room_accent': mockLight({
    name: 'Living room accent',
    on: false,
    brightness: 1,
    color: { mode: 'color', hue: 280, saturation: 80 },
    capabilities: { brightness: true, color: true },
  }),
  'climate.living_room': mockClimate({
    name: 'Living room',
    mode: 'heat',
    targetTemperature: 17,
    currentTemperature: 16.4,
    capabilities: { step: 0.5, range: { min: 5, max: 30 } },
  }),
  'scene.movie_night': mockAction({ name: 'Movie night' }),
  'vacuum.robot': mockAction({ name: 'Robot vacuum' }),

  'sensor.kitchen_temperature': mockSensor({
    name: 'Kitchen temperature',
    value: '19.6',
    unit: '°C',
    measurement: 'temperature',
  }),
  'light.kitchen_ceiling': mockLight({ name: 'Kitchen ceiling', on: false }),
  'light.kitchen_led': mockLight({ name: 'Kitchen LED', on: false, brightness: 0 }),
  'scene.cooking_time': mockAction({ name: 'Cooking time' }),

  'sensor.porch_temperature': mockSensor({
    name: 'Porch temperature',
    value: '15.9',
    unit: '°C',
    measurement: 'temperature',
  }),
  'sensor.porch_humidity': mockSensor({
    name: 'Porch humidity',
    value: '74',
    unit: '%',
    measurement: 'humidity',
  }),
  // Demonstrates the unavailable state on a real dashboard layout.
  'light.porch_lamp': mockLight({ name: 'Porch lamp', availability: 'unavailable' }),
  'light.porch_ambient': mockLight({
    name: 'Porch ambient',
    on: true,
    brightness: 0.7,
    color: { mode: 'color', hue: 30, saturation: 60 },
    capabilities: { brightness: true, color: true },
  }),
  'lock.front_door': mockSwitch({ name: 'Front door', on: true }),
  // dashboards/second-floor
  'sensor.master_bedroom_temperature': mockSensor({
    name: 'Master bedroom temperature',
    value: '',
    availability: 'unknown',
    measurement: 'temperature',
  }),
  'sensor.master_bedroom_humidity': mockSensor({
    name: 'Master bedroom humidity',
    value: '',
    availability: 'unknown',
    measurement: 'humidity',
  }),
  'light.master_bedroom_lamp': mockLight({
    name: 'Master bedroom lamp',
    on: false,
    brightness: 1,
    color: { mode: 'color', hue: 220, saturation: 40 },
    capabilities: { brightness: true, color: true },
  }),
  'climate.master_bedroom': mockClimate({
    name: 'Master bedroom',
    mode: 'heat',
    targetTemperature: 19.5,
    currentTemperature: 19.1,
  }),

  'sensor.bathroom_temperature': mockSensor({
    name: 'Bathroom temperature',
    value: '22.3',
    unit: '°C',
    measurement: 'temperature',
  }),
  'sensor.bathroom_humidity': mockSensor({
    name: 'Bathroom humidity',
    value: '58',
    unit: '%',
    measurement: 'humidity',
  }),
  'light.bathroom_led': mockLight({ name: 'Bathroom LED', on: true, brightness: 0.6 }),
  'script.shower': mockAction({ name: 'Shower' }),
  'script.septic_additive': mockAction({ name: 'Septic additive' }),

  'sensor.office_temperature': mockSensor({
    name: 'Office temperature',
    value: '18.8',
    unit: '°C',
    measurement: 'temperature',
  }),
  'sensor.office_humidity': mockSensor({
    name: 'Office humidity',
    value: '59.08',
    unit: '%',
    measurement: 'humidity',
  }),
  'climate.office': mockClimate({
    name: 'Office',
    mode: 'off',
    targetTemperature: 21.5,
    currentTemperature: 18.8,
  }),
  'scene.focus_mode': mockAction({ name: 'Focus mode' }),
  // dashboards/kitchen — reuses `light.kitchen_ceiling`, `light.kitchen_led`,
  // `sensor.kitchen_temperature` and `scene.cooking_time` already registered above
  // for dashboards/home's kitchen section (same physical entities); only the
  // additional devices this dedicated dashboard adds are listed here.
  'light.kitchen_island': mockLight({
    name: 'Kitchen island',
    on: false,
    brightness: 1,
    color: { mode: 'color', hue: 40, saturation: 60 },
    capabilities: { brightness: true, color: true },
  }),
  'sensor.kitchen_humidity': mockSensor({
    name: 'Kitchen humidity',
    value: '48',
    unit: '%',
    measurement: 'humidity',
  }),
  'switch.kitchen_coffee_maker': mockSwitch({ name: 'Coffee maker' }),
  'fan.kitchen_exhaust': mockSwitch({ name: 'Exhaust fan' }),
};
