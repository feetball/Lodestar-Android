---
title: Lodestar privacy policy (Android)
---

# Privacy policy — Lodestar for Android

*Effective October 18, 2026*

Lodestar is designed to work entirely on your phone. **It does not collect any data about you.** There are
no accounts, no analytics, no advertising and no tracking, and the developer never receives your location or
anything else from the app.

## What the app uses, and why

- **Location.** Your position (precise or approximate, as you allow) is used on your phone to work out where
  stars and planets appear in your sky and to turn the compass heading into true north. It is not stored on any
  server. You can instead choose observing sites on a map.
- **Camera.** If you turn on the camera view, the live picture is shown behind the sky overlay. The picture is
  processed on your phone only and is never recorded, stored or sent anywhere.
- **Motion sensors.** The gyroscope, accelerometer and compass are read on your phone to tell where your camera
  points.
- **Saved sites and settings** are stored only on your phone. If Android backup is turned on for your Google
  account, your settings and saved sites are included in that backup, like those of any app; nothing else from
  Lodestar is backed up.

## Network use

Lodestar's catalogs and calculations are all on the device. By default the only network requests are:

- **Map tiles** from [OpenFreeMap](https://openfreemap.org) when you open the site map. The tile server sees
  your IP address and the map area being shown.
- **Your phone's geocoding service** (on most phones provided by Google) receives the coordinates of an observing
  site you add or edit, to find the country and time zone of that site.

These services handle the requests under their own privacy policies
([OpenFreeMap](https://openfreemap.org/privacy/), [Google](https://policies.google.com/privacy)).

GoTo mount control talks to your telescope mount over your local Wi-Fi network only; nothing about it leaves
that network.

## Optional online data (off by default)

Settings › Online Data has four switches. **All of them are off** until you turn them on, and Lodestar works fully
without them. Each switch makes ordinary HTTPS requests to one provider to download data to your phone:

| Switch | What is downloaded | Provider | What the provider receives |
| --- | --- | --- | --- |
| Satellite passes | Orbit data of the ISS and other bright satellites (about 75 KB, at most every 12 to 24 hours) | [CelesTrak](https://celestrak.org) | Your IP address |
| Starlink streak warnings | Orbit data of the Starlink satellites (about 4.7 MB, once a day, only on an unmetered network such as Wi-Fi) | [CelesTrak](https://celestrak.org) | Your IP address |
| Comets | The comet orbit file (about 164 KB, at most once a day) | [Minor Planet Center](https://minorplanetcenter.net) | Your IP address |
| Cloud forecast | The hourly cloud-cover forecast (about 64 KB, no more often than the provider allows) | [MET Norway](https://api.met.no) | Your IP address and your location **rounded to 0.1° (about 10 km)** |

Every request also carries the app's name and version (for example `Lodestar/1.0`) and the address of the support
page, as the providers require. Nothing else about you or your phone is sent: no account, advertising identifier,
device identifier or exact location. Lodestar does not keep a server of its own and never sees these requests.

- The downloads happen when you open the app, when you switch a feed on and when you press **Update now**; nothing
  runs in the background.
- The downloaded data is stored only on your phone, outside Android's backup. The switches themselves are not backed
  up either.
- Turning a switch off deletes the data it downloaded.
- A failed or unreadable download never replaces the data you already have.

The providers keep their own server logs under their own policies: see the websites of
[CelesTrak](https://celestrak.org) and the [Minor Planet Center](https://minorplanetcenter.net), and
[MET Norway's privacy statement](https://www.met.no/en/About-us/privacy). Orbital data: CelesTrak, T. S. Kelso.
Orbital elements: IAU Minor Planet Center. Weather forecast from MET Norway (data licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).

## Children

Lodestar does not collect personal information from anyone, including children.

## Changes and contact

If this policy changes, the new version will be posted on this page with a new effective date. Questions are
welcome on the project's issue tracker: <https://github.com/feetball/Lodestar-Android/issues>.
