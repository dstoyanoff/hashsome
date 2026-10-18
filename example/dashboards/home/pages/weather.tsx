import { WeatherForecast } from '@hashsome/ui';
import styles from './weather.module.css';

// The page is the dashboard's own: a surface that fills the space and scrolls inside it, around the
// forecast. Change it as you like: a different background, two columns, other things beside it.
export default function Weather() {
  return (
    <div className={styles.page}>
      <WeatherForecast entity="ha:weather.home" expanded />
    </div>
  );
}
