# Changelog

## 0.17.0 (2026-10-16)

### Features

- **ui:** a task's date shows its year when it is not this year (#122) ([1a4e32e](https://github.com/dstoyanoff/hashsome/commit/1a4e32ec6af316b887660f2fccfadc777456b088))

## 0.16.1 (2026-10-15)

### Fixes

- **runtime:** a missing favicon is a 404, not an empty 204 (#121) ([8115083](https://github.com/dstoyanoff/hashsome/commit/81150839dd9e26e3aa5f750977c18ea79153c541))

## 0.16.0 (2026-10-15)

### Features

- **ui:** the stream's leader can leave it, handing it to another speaker (#119) ([3dc8576](https://github.com/dstoyanoff/hashsome/commit/3dc8576f5a0f4820671d7f67463b4cdc1bf49a50))

### Fixes

- **runtime:** a file that is not there is a 404, not the app's page (#120) ([d6001bb](https://github.com/dstoyanoff/hashsome/commit/d6001bb602954cd45d1159cbcf11d9dabb44eeca))

## 0.15.0 (2026-10-15)

### ⚠ Breaking changes

- **ui:** the library marks what is playing (#115) ([ea9d54a](https://github.com/dstoyanoff/hashsome/commit/ea9d54a5b4afc3618153ec70246590c8290413e2))
- **core:** a player's source says which library item it is, and the queue shows it instead of the player (#114) ([4e83417](https://github.com/dstoyanoff/hashsome/commit/4e83417765f704bac21f4ae8e895be4eef24af98))
- **ui:** NavRail and NavDock no longer have a theme toggle, the debug menu has one (#102) ([50b8fb2](https://github.com/dstoyanoff/hashsome/commit/50b8fb2154c7231c5c0448ba72bbb9d6e022b85f))

### Features

- **ui:** a scene or action button can have its own colour (#118) ([b58308b](https://github.com/dstoyanoff/hashsome/commit/b58308b017b0ae9d4b0d51b8341a43dc2cedd369))
- **ui:** a compact full player, with a speaker dropdown and the volume in the button row (#117) ([100b31b](https://github.com/dstoyanoff/hashsome/commit/100b31b9e0569f9986f5ebc7768b84447f5b2e89))
- **ui:** library search is for one kind at a time, starts on the open shelf and puts an exact match first (#116) ([7de556e](https://github.com/dstoyanoff/hashsome/commit/7de556e26f3134d5c412bcf6a6d700c941b79246))
- **ui:** the light drawer's Power, Brightness and Color sit on the drawer, not on cards (#113) ([456ecce](https://github.com/dstoyanoff/hashsome/commit/456ecce635792ba111615818e3fa72c9852b418d))
- **ui:** a drawer's History is folded away and fetched when opened (#112) ([7cc81b0](https://github.com/dstoyanoff/hashsome/commit/7cc81b0572278743d5107dc56a594fc8891ccdb4))
- **ui:** the drawer is a bottom sheet on a phone (#111) ([070f593](https://github.com/dstoyanoff/hashsome/commit/070f5937d75c38fc12fa69e361b2f59a3962bedf))
- **ui:** the navigation rail is 64px wide, like the bar (#109) ([90c7d72](https://github.com/dstoyanoff/hashsome/commit/90c7d7213a72257484563d3069ef5059849b50e9))
- **ui:** the floating dock clears the home indicator, and an installed app does not walk back through its pages (#108) ([f89e4ba](https://github.com/dstoyanoff/hashsome/commit/f89e4ba0985d5673ac9ba9aa488b66bdf24d2f65))
- **ui:** the page and the navigation bar leave room for a phone's home indicator (#107) ([d405097](https://github.com/dstoyanoff/hashsome/commit/d405097960c2f37577dea7f80b9a249dc627811f))
- **ui:** pictures are asked for as small, medium or large, and Music Assistant's go through the runtime (#106) ([3be3576](https://github.com/dstoyanoff/hashsome/commit/3be357617a9f8bd978dd3af87163ba725a5dcbc3))
- **runtime:** the server keeps the pictures it has fetched, and a browser keeps stable ones for good (#105) ([9450de3](https://github.com/dstoyanoff/hashsome/commit/9450de37ede293026e943301ac36ae4a821be50f))
- **runtime:** the document can be an app on a phone's home screen (#104) ([a342a0b](https://github.com/dstoyanoff/hashsome/commit/a342a0ba798de57b34da3de8a9942ae264bf6d44))
- **music-assistant:** the picture of what is playing is served through the runtime (#103) ([1afd266](https://github.com/dstoyanoff/hashsome/commit/1afd2664f9bc005551dece51068adb4be63e78be))

### Fixes

- **ui:** AnimatedOutlet lets a long page scroll to its end (#110) ([7fdd55a](https://github.com/dstoyanoff/hashsome/commit/7fdd55a1c504d4a31993171e851474607eddaa9f))

## 0.14.0 (2026-10-14)

### Features

- **ui:** NavBar, the left rail's design along the bottom of the screen (#101) ([5ca34fc](https://github.com/dstoyanoff/hashsome/commit/5ca34fc051e001f15d4446b56f214ac245effc18))
- **ui:** the player bar can be one row without the time or the shuffle button, and its title rolls (#100) ([a252437](https://github.com/dstoyanoff/hashsome/commit/a252437fa3390084e2b4ccf0cc4c9de6ba5f5bb6))

## 0.13.1 (2026-10-14)

### Fixes

- **ui:** the source line reads "from playlist" in lowercase, with the name set apart (#99) ([82f003c](https://github.com/dstoyanoff/hashsome/commit/82f003c938208b7e478c1c0fafdf1ca0f6703d4c))
- **ui:** the live power reading stays in the full-size drawer (#98) ([25006d5](https://github.com/dstoyanoff/hashsome/commit/25006d5a544ff757436fe3c06b41836cca23d081))

## 0.13.0 (2026-10-11)

### Features

- **ui:** the player says which playlist or album it plays from, and goes on with similar tracks (#97) ([48f8144](https://github.com/dstoyanoff/hashsome/commit/48f8144bfa40301def319fab167a8da91c3b8aa9))

## 0.12.0 (2026-10-10)

### Features

- **ui:** speakers sit behind a pill under the track info, with Add, Join and Remove buttons (#95) ([94db6d8](https://github.com/dstoyanoff/hashsome/commit/94db6d8139d7e6878b4f0016ec6aa338f6ca715e))
- **music-assistant:** players report the group they play in, can be grouped, and a follower can take the stream over (#96) ([06d52ab](https://github.com/dstoyanoff/hashsome/commit/06d52ab9ec2a1b44954c90b22b099709f062c5a1))
- **core:** media players can be grouped, and a follower can take the stream over (#93) ([0cddacd](https://github.com/dstoyanoff/hashsome/commit/0cddacd9ae062b2772cf910c819aa0b4770588d6))

## 0.11.0 (2026-10-10)

### Features

- **ui:** search results can be narrowed by type, and a result opens (#91) ([395b9a6](https://github.com/dstoyanoff/hashsome/commit/395b9a6f485a3de42334c791499fceb629476325))

### Fixes

- **ui:** the debug button is under a drawer and its scrim, not over them (#92) ([2792b00](https://github.com/dstoyanoff/hashsome/commit/2792b00e41b3b8f3f548a8f12b9f31cd6a93923a))

## 0.10.0 (2026-10-09)

### Features

- **ui:** a wall display left alone on a sub-page goes back to the dashboard's main page (#90) ([2a3ff62](https://github.com/dstoyanoff/hashsome/commit/2a3ff6204517d899d4e742fd80d96206d158a5ba))

## 0.9.0 (2026-10-09)

### Features

- **ui:** a recurring task as a tile, a confirm dialog, and a dot on a nav item (#88) ([6c8ac23](https://github.com/dstoyanoff/hashsome/commit/6c8ac23d0851b47de5b72d725d307eae836b4d31))
- **ui:** a scene's dot in the top bar answers a press, and the top bar uses theme colors (#87) ([2c3ac6c](https://github.com/dstoyanoff/hashsome/commit/2c3ac6c7a10dc1fba41bc2acecc06e5e2222c574))

### Fixes

- **ui:** the debug button sits centered under the navigation rail's icons (#89) ([73afe30](https://github.com/dstoyanoff/hashsome/commit/73afe30ee04692ba7db2d9c5178a68bbb1f8c395))

## 0.8.0 (2026-10-09)

### Features

- **ui:** the player shows when it is waiting, and keeps the old track on show until a skip has taken effect (#86) ([bb20e9f](https://github.com/dstoyanoff/hashsome/commit/bb20e9f32759615031ac1ba2ddbf93429f0ac60b))

## 0.7.0 (2026-10-08)

### Features

- **ui:** numbers that change move to their new value instead of jumping (#85) ([4cb788c](https://github.com/dstoyanoff/hashsome/commit/4cb788c231d9cdceccc160f4f161439a6f768781))

## 0.6.0 (2026-10-08)

### Features

- **ui:** the library's search can be an icon in the row of chips, and the selected chip's accent glides (#84) ([d565354](https://github.com/dstoyanoff/hashsome/commit/d5653542c4ad2e97ae77d4e0283d85e096e218ce))
- **ui:** the full player has Library and Queue tabs where the queue has no room beside it (#83) ([7192d90](https://github.com/dstoyanoff/hashsome/commit/7192d90db29a9da1bab040c193476d343cb9a7ce))
- **ui:** the artwork of a player card that is stretched grows, up to the width of its buttons (#82) ([6bed7c3](https://github.com/dstoyanoff/hashsome/commit/6bed7c34a979598a2957dddd366b7905fb079d8c))
- **ui:** Board, Cell and TopRow lay a page out by sizes on the module grid (#81) ([370430e](https://github.com/dstoyanoff/hashsome/commit/370430e26635a51cb5af3f838bccc1501ad9c251))
- **runtime:** hashsome start builds first, and --no-build serves what is already built (#80) ([0655640](https://github.com/dstoyanoff/hashsome/commit/065564031f2969efb2732820459a89e05e7ce272))
- **ui:** a debug menu with the grid, fullscreen and the theme, switched on by HASHSOME_DEBUG (#79) ([021bcff](https://github.com/dstoyanoff/hashsome/commit/021bcffb2f8dcc775d2449a090bbf9caf783f670))
- **ui:** the top row and tiles have explicit heights, and ?grid spans the whole page (#75) ([8c08481](https://github.com/dstoyanoff/hashsome/commit/8c084812df71fc00a96be51bc35baca6e606e4d3))
- **ui:** ?grid draws the module grid over a page, and the page centers its content on it (#74) ([3385a61](https://github.com/dstoyanoff/hashsome/commit/3385a61719368e99106809bcb65388e226ff4e37))
- **ui:** the player column can open its library and queue as overlays, and fits a short space (#73) ([f57ccc8](https://github.com/dstoyanoff/hashsome/commit/f57ccc80713b4bb0d9073880cb5edbb0cc5c4d38))

### Fixes

- **ui:** ?motion=reduced is for the visit, not kept in local storage (#77) ([11759dd](https://github.com/dstoyanoff/hashsome/commit/11759ddbaa163f78d3df36595fcef880ed1d6c53))
- **ui:** a theme that follows the sun no longer re-renders the whole page in a loop (#76) ([1466491](https://github.com/dstoyanoff/hashsome/commit/146649123705e987eb669ca051c720cee088b52e))

## 0.5.0 (2026-10-07)

### Features

- **ui:** WeatherChip can leave its forecast drawer out (#71) ([d106662](https://github.com/dstoyanoff/hashsome/commit/d106662bc1d3bea5217b96fa44b347cbfadcccf3))
- **ui:** the player bar can open the library and the queue as overlays of their own (#69) ([2d1df6f](https://github.com/dstoyanoff/hashsome/commit/2d1df6f0c996ea4d7e8ee45c1855c5874c592176))
- **ui:** the small player's artwork ring shows how far playback has gone (#68) ([d5baec6](https://github.com/dstoyanoff/hashsome/commit/d5baec6b27f9cba2a1680ad8cadccb1c28b82a2b))
- **ui:** the player bar can skip its drawer, or send the hold to a page (#66) ([efdd449](https://github.com/dstoyanoff/hashsome/commit/efdd449b2c3cd27a8271ba093244b55d726942ee))
- **ui:** a device can turn animations off with ?motion=reduced (#65) ([7ed5e76](https://github.com/dstoyanoff/hashsome/commit/7ed5e76bd49eee7a9c488b6d61e55b961327c952))
- **ui:** LightTile can leave its drawer out (#62) ([483e95a](https://github.com/dstoyanoff/hashsome/commit/483e95a09c8672d2fe32ee0042ae11b62991dc80))
- **ui:** the media player bar is two rows in a narrow space, or when asked (#58) ([3419b02](https://github.com/dstoyanoff/hashsome/commit/3419b028b887c349e3d2f55c7a7bf98a7679923e))
- **ui:** AnimatedOutlet gives a dashboard's pages a transition (#60) ([a8b5a16](https://github.com/dstoyanoff/hashsome/commit/a8b5a1666165b19e4f190284ba8e7c8b3261621a))
- **runtime:** homeAssistantCompat lets a wall display that only opens a Home Assistant accept a Hashsome server (#56) ([f191274](https://github.com/dstoyanoff/hashsome/commit/f191274c286e743678491334f5ccc69008035f08))
- **ui:** the top bar's date, weather and clock are components of their own (#55) ([3637302](https://github.com/dstoyanoff/hashsome/commit/36373028208c507d13e19f61f21903e78cf2d20f))
- **ui:** the theme can follow the time of day, by the clock or by the sun (#54) ([cf5d9db](https://github.com/dstoyanoff/hashsome/commit/cf5d9dbb67ff562f950154d1ca97fb5a2926281e))
- **ui:** a calmer orange is the dark theme's accent (#53) ([30e88bd](https://github.com/dstoyanoff/hashsome/commit/30e88bda74598e63fd856a323bb1d25998c50387))

### Fixes

- **ui:** a touch that never ends cannot freeze the playback position (#70) ([264a330](https://github.com/dstoyanoff/hashsome/commit/264a33010dea53820f63747b8d696a70f93ec1f9))
- **ui:** a card that mounts is drawn as it is, not animated in from nothing (#64) ([e5b0fc1](https://github.com/dstoyanoff/hashsome/commit/e5b0fc1b76f78dd3ed7e626be32fe919d7e62cb0))
- **ui:** the nav dock's active marker is a circle, like the pill around it (#59) ([65cd965](https://github.com/dstoyanoff/hashsome/commit/65cd9656a032ad83a3b262a561879ce759d04df1))
- **ui:** the nav dock's shadow is softer (#57) ([15d7d93](https://github.com/dstoyanoff/hashsome/commit/15d7d93d367b4333b4e2ff80e787f14b0e93e891))

### Performance

- **ui:** the hold line is a CSS transform animation, not a width animated from JavaScript (#63) ([ca93aec](https://github.com/dstoyanoff/hashsome/commit/ca93aec9997db0d51940c679a9f0a6363ff8e609))

## 0.4.0 (2026-10-06)

### Features

- **runtime:** a page left open reloads when a newer build is being served (#51) ([c60adad](https://github.com/dstoyanoff/hashsome/commit/c60adad39a1d1f7ca281bec2c7c6214f9c6f6019))

### Fixes

- **ui:** the weather pill's reading and its high and low are centred on their digits (#52) ([956a4b7](https://github.com/dstoyanoff/hashsome/commit/956a4b7e0cbfa4e00d701cb139c705f7b5236646))

## 0.3.0 (2026-10-06)

### Features

- **ui:** the weather's hours are one scrolling row, without a box each (#50) ([ac68b8b](https://github.com/dstoyanoff/hashsome/commit/ac68b8bbc7c822405eed0a8e885521895f2ea556))
- **ui:** the weather readings are outlined boxes with a smaller radius (#49) ([e0d6a3d](https://github.com/dstoyanoff/hashsome/commit/e0d6a3df5b5f33ee2e5f500769f93536323f6784))
- the queue beside the player, and play, play next and add to queue in the library (#47) ([9ccabe9](https://github.com/dstoyanoff/hashsome/commit/9ccabe94715c7379f56b37cab92f80587d0f3478))

### Fixes

- **ui:** chart grid lines are lighter, and the bottom one is solid (#48) ([dec2734](https://github.com/dstoyanoff/hashsome/commit/dec27342247c9662de7b35cca89cf9cc8851da45))

## 0.2.0 (2026-10-06)

### ⚠ Breaking changes

- **ui:** MediaPlayerPage becomes MediaPlayerFull, a widget a dashboard puts in its own page (#41) ([886280c](https://github.com/dstoyanoff/hashsome/commit/886280c3afa602b07f5ae6750b89f05223bf2c5c))

### Features

- recent activity (the logbook) in the light and climate drawers (#43) ([7136461](https://github.com/dstoyanoff/hashsome/commit/7136461cb9f14a220088c8a4c28f6a8f8122413b))
- weather forecast in the top bar's weather chip (#38) ([8ae932c](https://github.com/dstoyanoff/hashsome/commit/8ae932c6c234154520f0451ac74d957f9131d22e))
- **runtime:** hashsome upgrade moves a project's @hashsome packages to the latest release (#39) ([708d704](https://github.com/dstoyanoff/hashsome/commit/708d704cf2a61c07969e96d771183d367ae6e2c5))
- **core:** mock devices invent power and energy history (#36) ([58db72d](https://github.com/dstoyanoff/hashsome/commit/58db72d0a3326600cb4aa3a2e18716c8ed409a8d))

### Fixes

- the release script bumps from the last tag, not the root package version (#45) ([dd19afd](https://github.com/dstoyanoff/hashsome/commit/dd19afd051fdbffe30544fc9d736a4c44718f2b5))
- **ui:** dashboard switcher entries are as tall as its button, for touch screens (#44) ([9e90033](https://github.com/dstoyanoff/hashsome/commit/9e90033c0d6c5db747bea1a87503add6b92f2558))
- **ui:** library cards shrink to fit instead of being cut off (#37) ([9de9c5f](https://github.com/dstoyanoff/hashsome/commit/9de9c5f050714159630f00860c7e59b69f51d068))
