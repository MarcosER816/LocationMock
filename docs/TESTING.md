# MockRoute device test checklist

Phone: POCO F7 Ultra · HyperOS 3 · Android 16. Build under test: Settings → About (version, build number).
Mark each line **PASS** or **FAIL** and note what you saw. Names are the app's English labels (Spanish in brackets
where they differ a lot).

| Build tested | Date | Tester |
|---|---|---|
| | | |

## 1. Install and setup
| # | Check | Expected | Result |
|---|---|---|---|
| 1.1 | Install the APK over the previous version | Updates in place, data kept | |
| 1.2 | First launch on a clean install | 3 intro screens, then the setup wizard | |
| 1.3 | Setup wizard (Settings → Check setup) | Steps 1, 2, 3, 4 and 6 show a green ✓ | |
| 1.4 | Developer options → mock location app = Nothing, then **Be here** | Clear dialog with a button to Developer options; no crash | |

## 2. Map and look
| # | Check | Expected | Result |
|---|---|---|---|
| 2.1 | Open the app | Dark map, your real location as a blue dot | |
| 2.2 | Open any bottom panel (Route, Area…) | The map still fills at least half of the screen; the panel scrolls | |
| 2.3 | Buttons and chips | Smooth squares, one colour per mode (Route blue, Trace cyan, Draw amber, Area purple, Trip green) | |
| 2.4 | Settings → Language → Español | Main labels switch to Spanish | |
| 2.5 | Settings → Units → Imperial | Distances and speeds in the Speed & time screen in ft / mi / mph | |
| 2.6 | Settings → Map style → Light, then Dark | Map changes style both ways | |
| 2.7 | Airplane mode | "Offline" notice; already-seen map areas and saved routes still show | |

## 3. Point and routes
| # | Check | Expected | Result |
|---|---|---|---|
| 3.1 | Long-press → **Be here** | Google Maps shows you at that point | |
| 3.2 | **Route**: set B, Walk | Line follows streets; coloured green → yellow → red | |
| 3.3 | **Start** | Google Maps follows the route; slow in green parts, faster in red | |
| 3.4 | Gradient vs speed | Tap the line in several places: speeds match the colours | |
| 3.5 | **Trace** 5 points, Undo twice | Continuous street line; Undo removes the last legs | |
| 3.6 | Stop | Real location back in Google Maps within ~30 s | |

## 4. Traffic (needs a TomTom key)
| # | Check | Expected | Result |
|---|---|---|---|
| 4.1 | Car route across the city, depart tomorrow 08:00 | Longer duration, red congestion markers | |
| 4.2 | Same route, depart 03:00 | Shorter duration, few or no markers | |
| 4.3 | Back to 08:00 | "0 request(s), N from cache" | |

## 5. Areas
| # | Check | Expected | Result |
|---|---|---|---|
| 5.1 | **Area → Building**: draw, close, start/end points | Points sit on the walls | |
| 5.2 | Start it and watch in Google Maps | Stays inside the shape, pauses sometimes, leaves at the end point | |
| 5.3 | **Area → Streets**: draw a neighbourhood | Grey streets appear; "Overpass today" +1 | |
| 5.4 | Start it | Moves only along streets inside the shape | |
| 5.5 | Close and reopen the app | Streets come from the cache (counter unchanged) | |

## 6. Trip (timeline)
| # | Check | Expected | Result |
|---|---|---|---|
| 6.1 | ⏱ → + Route, + Building, + Route, + Streets | Points named A, B, C, D, E; shared points can't be moved | |
| 6.2 | Editing a step | Other steps shown in grey (real street routes, grey heat for areas) | |
| 6.3 | **Start trip** without preparing first | It prepares, then starts by itself | |
| 6.4 | Watch the joins between steps | No jump at B, C, D; one continuous run | |
| 6.5 | Notification | Shows "2/4 · …", progress, **Next step**, Pause, Stop | |
| 6.6 | Save as…, Load, Export JSON, Export GPX, Import | All work | |

## 7. GPS noise
| # | Check | Expected | Result |
|---|---|---|---|
| 7.1 | Realistic + Debug overlay, walking route | Dot jitters a few metres around the black line; blue accuracy circle changes size | |
| 7.2 | Building area | Clearly larger error and circle | |
| 7.3 | **Be here** for 2 minutes | Dot keeps wandering gently | |
| 7.4 | Preset Off | Perfect line again | |
| 7.5 | **Wi-Fi scanning comparison:** play a route with Wi-Fi + Bluetooth scanning and Google Location Accuracy ON, then OFF | With them ON the position may flick back to the real one; OFF should be stable. Note the difference | |

## 8. Reliability
| # | Check | Expected | Result |
|---|---|---|---|
| 8.1 | Settings → Background test → **Run 10-minute test**, screen off | "PASS … No throttling" | |
| 8.2 | 2-hour trip with the screen off | Still running at the end; notification progress correct | |
| 8.3 | During a trip: Developer options → mock location app = Nothing | Simulation stops; notification explains how to re-select | |
| 8.4 | Re-select MockRoute, open the app | "Resume trip" offer; it continues from where it stopped | |
| 8.5 | Force stop the app during a trip (App info → Force stop), reopen | "Resume trip" offer; real location works meanwhile | |
| 8.6 | Swipe the app away from Recents during a simulation | Simulation stops; real location back | |
| 8.7 | Quick Settings tile "MockRoute" | Idle: opens the app and starts the last trip. Running: stops | |
| 8.8 | Long-press the app icon → "Last trip" | Starts the last trip | |
| 8.9 | After a HyperOS update | Autostart, battery "No restrictions" and Recents lock still set (Settings → Xiaomi / HyperOS helper) | |

## 9. Not automated
There are no Compose UI tests in CI (no emulator step is feasible in this setup): sections 1–8 are the manual
replacement. `:core` logic is covered by unit tests that run on every push.
