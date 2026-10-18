import { House, Lock, RobotVacuum, Sofa, UtensilsCrossed } from 'lucide-react';
import {
  ActionButton,
  ClimateTile,
  Grid,
  LightTile,
  MediaPlayerBar,
  RoomHeader,
  SceneButton,
  SensorReadout,
} from '@hashsome/ui';
import { demoHistory } from '../demo-history.ts';

const livingRoomTemperature = demoHistory(19, 2, 1.5);
const livingRoomHumidity = demoHistory(52, 9, 11);
const kitchenTemperature = demoHistory(20, 2.5, 1.2);

export default function Home() {
  return (
    <>
      {/* Talked to directly via Music Assistant, not through Home Assistant — same component,
          same hooks, no special-casing needed; see @hashsome/core's MusicAssistantIntegration. */}
      <MediaPlayerBar entity="ma:living_room" />

      <RoomHeader
        title="Living Room"
        icon={<Sofa />}
        readouts={
          <>
            <SensorReadout
              entity="ha:sensor.living_room_temperature"
              history={livingRoomTemperature}
            />
            <SensorReadout entity="ha:sensor.living_room_humidity" history={livingRoomHumidity} />
          </>
        }
      />
      <Grid columns={3}>
        <LightTile
          entity="ha:light.living_room_lamp"
          name="Lamp"
          energy={{
            power: 'ha:sensor.living_room_lamp_power',
            energy: 'ha:sensor.living_room_lamp_energy',
          }}
        />
        <LightTile
          entity="ha:light.living_room_wall"
          name="Wall Lights"
          energy={{
            power: 'ha:sensor.living_room_wall_power',
            energy: 'ha:sensor.living_room_wall_energy',
          }}
        />
        <LightTile
          entity="ha:light.living_room_accent"
          name="Accent"
          energy={{
            power: 'ha:sensor.living_room_accent_power',
            energy: 'ha:sensor.living_room_accent_energy',
          }}
        />
      </Grid>
      <ClimateTile
        entity="ha:climate.living_room"
        name="Heater"
        energy={{
          power: 'ha:sensor.living_room_heater_power',
          energy: 'ha:sensor.living_room_heater_energy',
        }}
      />
      <Grid columns={2}>
        <SceneButton entity="ha:scene.movie_night" name="Movie Night" />
        <ActionButton label="Vacuum" icon={<RobotVacuum />} entity="ha:vacuum.robot" />
      </Grid>

      <RoomHeader
        title="Kitchen"
        icon={<UtensilsCrossed />}
        readouts={
          <SensorReadout entity="ha:sensor.kitchen_temperature" history={kitchenTemperature} />
        }
      />
      <Grid columns={3}>
        <LightTile
          entity="ha:light.kitchen_ceiling"
          name="Ceiling"
          energy={{
            power: 'ha:sensor.kitchen_ceiling_power',
            energy: 'ha:sensor.kitchen_ceiling_energy',
          }}
        />
        <LightTile
          entity="ha:light.kitchen_led"
          name="LED Strip"
          energy={{ power: 'ha:sensor.kitchen_led_power', energy: 'ha:sensor.kitchen_led_energy' }}
        />
        <SceneButton entity="ha:scene.cooking_time" name="Cooking Time" />
      </Grid>

      <RoomHeader
        title="Porch"
        icon={<House />}
        readouts={
          <>
            <SensorReadout entity="ha:sensor.porch_temperature" />
            <SensorReadout entity="ha:sensor.porch_humidity" />
          </>
        }
      />
      <Grid columns={3}>
        <LightTile entity="ha:light.porch_lamp" name="Porch Lamp" />
        <LightTile
          entity="ha:light.porch_ambient"
          name="Ambient"
          energy={{
            power: 'ha:sensor.porch_ambient_power',
            energy: 'ha:sensor.porch_ambient_energy',
          }}
        />
        <ActionButton label="Front Door" icon={<Lock />} entity="ha:lock.front_door" />
      </Grid>
    </>
  );
}
