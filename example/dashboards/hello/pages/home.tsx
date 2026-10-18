import { Sofa } from 'lucide-react';
import { ClimateTile, Grid, LightTile, RoomHeader, SensorReadout } from '@hashsome/ui';

// A small kiosk panel: deliberately no top bar and no navigation — just the room itself.
// It is left out of the shared switcher list (see shared/dashboards.ts) but still reachable by URL.
export const meta = () => [{ title: 'Hello' }];

export default function Home() {
  return (
    <>
      <RoomHeader
        title="Living Room"
        icon={<Sofa />}
        readouts={<SensorReadout entity="ha:sensor.temperature" />}
      />
      <Grid columns={2}>
        <LightTile entity="ha:light.lamp" name="Lamp" />
        <ClimateTile entity="ha:climate.heater" name="Heater" />
      </Grid>
    </>
  );
}
