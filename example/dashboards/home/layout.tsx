import { NavRail, type NavItem, AnimatedOutlet } from '@hashsome/ui';
import { HomeTopBar } from '../../shared/top-bar.tsx';

const items: NavItem[] = [
  { to: '', label: 'Home', icon: 'lu:house' },
  // Routed but not built yet — see pages/coming-soon.tsx.
  { to: 'lights', label: 'Lights', icon: 'lu:lightbulb' },
  { to: 'climate', label: 'Climate', icon: 'lu:thermometer' },
  { to: 'media', label: 'Media', icon: 'lu:music' },
  { to: 'weather', label: 'Weather', icon: 'lu:cloud-sun' },
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
