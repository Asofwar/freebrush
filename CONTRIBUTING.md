# Contributing

Thanks for helping. The most useful contributions right now are:

1. **Device reports.** If you own an Oclean brush, sync it with FreeBrush and open a
   [device report](../../issues/new?template=device-report.yml). Copy the sync log from the **Brush**
   tab into the report. It contains the model ID and the raw packets that are needed to support a new model.
2. **Protocol fixes.** Each change to packet decoding must come with a unit test that uses real
   packet hex from a device. Put it in `src/test/` next to the existing ones.

## Ground rules

- The app must keep working fully offline: do not add the `INTERNET` permission, analytics or third-party SDKs.
- Do not contribute code copied from the official Oclean app or its decompiled sources. Describe observed
  protocol behaviour in your own words and code.
- Write commands that change brush settings must be confirmed on a real device before they are enabled
  in the UI.

## Development

```bash
./gradlew assembleDebug testDebugUnitTest
```

Kotlin code follows the official Kotlin style. UI text is in Russian for now; English localisation is welcome.

By contributing you agree that your contribution is licensed under GPL-3.0-or-later.
