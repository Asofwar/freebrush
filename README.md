# FreeBrush

An open-source Android app for Oclean smart toothbrushes that works fully offline.
It talks to the brush directly over Bluetooth Low Energy. You need no account and no cloud, and the
app has no internet permission at all.

> **Status: early alpha.** Syncing works on a real **Oclean X Pro Digital** (`OCLEANY3PB`,
> firmware 1.0.2.5). Other models that share the same Bluetooth protocol may work, but nobody has
> tested them yet. See [Supported brushes](#supported-brushes).

[По-русски](#по-русски)

## Why

The official app makes you create an account before it does anything. It shows a server-driven
splash banner and sends your brushing data and usage statistics to the vendor's servers. All the
useful data (score, duration, per-zone time, pressure) is measured by the brush itself, so none of
that is needed. FreeBrush reads the data straight from the brush and keeps it on your phone.

## Features

| | FreeBrush | Official app |
|---|---|---|
| Brushing history: score, duration, zones, pressure¹ | ✅ on the phone | ✅ in the cloud |
| Brush-head counters (days, sessions, wear) | ✅ | ✅ |
| Works without an account | ✅ | ❌ |
| Internet access | none (no `INTERNET` permission) | required |
| Ads / splash banners | none | server-driven banner |
| Streaks, two-week chart, daily goal | ✅ | partly |
| 3D jaw model you can rotate and tap for per-zone stats | ✅ | flat diagram |
| Export to CSV | ✅ | ❌ |
| Brush settings: zone reminder, raise-to-wake, day/evening mode, side-motion warning, display language, brush-head reset and limit | ✅ | ✅ |
| Settings the official app hides on this brush: pressure alert, brush pause, wake-up mode | ✅ | ❌ |
| Brushing programmes: the brush's built-in modes (incl. the hidden day/evening ones), Oclean's catalogue | ✅ | ✅ |
| Custom programmes | ✅ up to 25, up to 9 steps, 32 cleaning levels + whitening, massage, sensitive, 5–255 s steps | 3 programmes, 6 steps, 4 levels per mode |
| Firmware updates | ❌ never: firmware comes from the vendor's servers | ✅ |
| Family accounts, "expert" reports, AI chat | ❌ | ✅ |

¹ Only on brushes that log pressure. The X Pro Digital sends a fixed placeholder instead, so FreeBrush hides it.

The brush keeps its last 32 sessions and overwrites the oldest ones. FreeBrush does not delete sessions from the
brush, so sync at least every couple of weeks to keep the full history.

## Supported brushes

| Model | Model ID | Status |
|---|---|---|
| Oclean X Pro Digital | `OCLEANY3PB` | ✅ sync confirmed (firmware 1.0.2.5): score, duration, zones, battery, brush-head counters |
| Oclean X Pro Elite | `OCLEANY3P`, `OCLEANY3X` | same protocol, untested |
| Oclean X / X Pro / X Pro 20 / X Ultra | `OCLEANY3M`, `OCLEANY3`, `OCLEANX20`, `OCLEANV1a` | same protocol family, untested |

If you own another Oclean model, open a [device report](../../issues/new?template=device-report.yml)
with the sync log from the **Brush** tab.

## Install

Grab the APK from [Releases](../../releases) once the first one is published, or build it yourself.

Requires Android 12 or newer and Bluetooth LE.

**Close the official Oclean app before syncing.** The brush accepts only one connection at a time.
The official app also appears to clear the brush's session buffer when it syncs, so sessions that
it picks up first will not reach FreeBrush.

## Build

JDK 17+ and the Android SDK (compile SDK 37) are needed.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The APK lands in `build/outputs/apk/debug/`.

## Privacy

- The only permissions are `BLUETOOTH_SCAN` (flagged `neverForLocation`) and `BLUETOOTH_CONNECT`.
- Brushing history is stored in the app's private storage (`sessions.json`) and leaves the phone
  only when you export it yourself.
- No analytics, no crash reporting, no third-party SDKs besides AndroidX and Jetpack Compose.

## How it works

The brush exposes a vendor GATT service. FreeBrush connects, reads the Device Information and Battery
services, subscribes to the brush's notification characteristics and asks for stored sessions. It then
decodes the session records and merges them into the local history, de-duplicating by session start time.
The protocol notes this app relies on come from the community projects listed below, checked for
interoperability against the behaviour of the official app.

## Credits

- [ha-oclean-integration](https://github.com/deniskie/ha-oclean-integration) by Denis Kiesel (MIT):
  the Home Assistant integration whose protocol reference made this app possible.
  See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- [esphome-oclean](https://github.com/dzikus/esphome-oclean) (GPL-3.0): ESPHome component for
  Oclean brushes.

## Disclaimer

FreeBrush is an independent project. It is not affiliated with, endorsed by or connected to Oclean or
its parent company. "Oclean" is a trademark of its respective owner and is used here only to say
which devices the app works with. Use the app at your own risk. It never writes firmware to the brush.

## License

[GPL-3.0-or-later](LICENSE).

---

## По-русски

FreeBrush — открытое Android-приложение для умных щёток Oclean. Оно работает без интернета, аккаунта
и облака: забирает данные со щётки по Bluetooth и хранит их только на телефоне. Разрешения на интернет
у приложения нет.

**Статус: ранняя альфа.** Синхронизация проверена на реальной **Oclean X Pro Digital** (`OCLEANY3PB`,
прошивка 1.0.2.5). Другие модели с тем же протоколом могут работать, но не проверены.

Щётка хранит последние 32 чистки и затирает самые старые. FreeBrush ничего со щётки не удаляет, поэтому
синхронизируйся хотя бы раз в пару недель, чтобы история была полной.

**Что умеет:**
- История чисток: оценка, длительность, время по зонам, нажим.
- Серия дней подряд, график за две недели, цель — две чистки в день.
- Экспорт истории в CSV.

**В планах:** настройки щётки (оповещение о нажиме, язык, счётчик насадки), режимы и свои программы.

**Чего не будет:** обновления прошивки (файлы лежат на серверах Oclean), семейные аккаунты, «экспертные»
отчёты, AI-чат.

**Перед синхронизацией закрой официальное приложение Oclean.** Щётка держит только одно соединение,
а официальное приложение, судя по всему, стирает с неё историю чисток при своей синхронизации.

Проект не связан с компанией Oclean. «Oclean» — товарный знак его владельца. Лицензия — GPL-3.0-or-later.
