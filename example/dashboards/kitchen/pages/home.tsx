import {
  ActionButton,
  Grid,
  LightTile,
  MediaPlayerColumn,
  RoomHeader,
  SceneButton,
  SensorReadout,
} from '@hashsome/ui';
import { HomeTopBar } from '../../../shared/top-bar.tsx';
import styles from './home.module.css';

export const meta = () => [{ title: 'Kitchen' }];

export default function Home() {
  return (
    <>
      <HomeTopBar title="Kitchen" />
      {/* The rooms on the left; the player gets a column of its own on the right. */}
      <div className={styles.body}>
        <div className={styles.rooms}>
          <RoomHeader
            title="Kitchen"
            icon="lu:utensils-crossed"
            readouts={
              <>
                <SensorReadout entity="ha:sensor.kitchen_temperature" />
                <SensorReadout entity="ha:sensor.kitchen_humidity" />
              </>
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
              name="Under-Cabinet"
              energy={{
                power: 'ha:sensor.kitchen_led_power',
                energy: 'ha:sensor.kitchen_led_energy',
              }}
            />
            <LightTile
              entity="ha:light.kitchen_island"
              name="Island Pendant"
              energy={{
                power: 'ha:sensor.kitchen_island_power',
                energy: 'ha:sensor.kitchen_island_energy',
              }}
            />
          </Grid>

          <RoomHeader title="Appliances" />
          <Grid columns={3}>
            <ActionButton
              label="Coffee Maker"
              icon="lu:coffee"
              entity="ha:switch.kitchen_coffee_maker"
            />
            <ActionButton label="Exhaust Fan" icon="tb:car-fan" entity="ha:fan.kitchen_exhaust" />
            <SceneButton entity="ha:scene.cooking_time" name="Cooking Time" />
          </Grid>
        </div>
        <div className={styles.player}>
          <MediaPlayerColumn entity="ma:kitchen" />
        </div>
      </div>
    </>
  );
}
