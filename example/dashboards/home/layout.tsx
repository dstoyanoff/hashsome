import { CloudSun, House, Lightbulb, Music, Thermometer } from 'lucide-react';
import { NavRail, type NavItem, AnimatedOutlet } from '@hashsome/ui';
import { HomeTopBar } from '../../shared/top-bar.tsx';

const items: NavItem[] = [
  { to: '', label: 'Home', icon: <House /> },
  // Routed but not built yet — see pages/coming-soon.tsx.
  { to: 'lights', label: 'Lights', icon: <Lightbulb /> },
  { to: 'climate', label: 'Climate', icon: <Thermometer /> },
  { to: 'media', label: 'Media', icon: <Music /> },
  { to: 'weather', label: 'Weather', icon: <CloudSun /> },
];

/** The home dashboard's own layout: a nav rail, and the shared top bar above whichever page is
 * showing. */
export const meta = () => [{ title: 'Home' }];

export default function Layout() {
  return (
    <>
      <NavRail items={items} base="/home" />
      <HomeTopBar title="Home" scenes={['ha:scene.movie_night', 'ha:scene.cooking_time']} />
      <AnimatedOutlet items={items} base="/home" />
    </>
  );
}
