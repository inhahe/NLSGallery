# Lithic Gallery

- Architecture and features: `design.md`. Read it before a change whose effects leave the file
  you are editing.
- User-facing document: `README.md`. Known bugs and limits: `known-issues.md`.
- Model tooling, reference output and test data: `tools/README.md`.

## The thumbsup2 contract

The AI pipeline reproduces thumbsup2 (`..\thumbsup2`): same models, same preprocessing, same
decoding, same search ranking. `:core`'s tests compare against thumbsup2's own output and are
exact (captions, OCR text and boxes, search scores, image operations to the bit). A change to
anything in `core/.../ai`, `image`, `text` or `search` must keep them passing; a deliberate
departure goes in `design.md` under "Deliberate differences from thumbsup2", with the reason.

## Building and testing

- JDK 21 at `%USERPROFILE%\.jdks\jdk-21.0.12.1+1` (set `JAVA_HOME`), Gradle wrapper.
- `gradlew :core:test`: the pipeline against thumbsup2. Needs `tools/testdata` and
  `tools/weights` (see `tools/README.md`); tests whose files are absent are skipped, not failed.
  The model tests take a few minutes; `CraftTilingTest` longer.
- `gradlew :app:testDebugUnitTest`: category rules, XMP parsing.
- On a phone: `tools\push-test-data.bat`, `push-models.bat debug`, then run
  `OnDeviceModelTest` / `OnDeviceBenchmark` with `adb shell am instrument -w -e class
  com.lithic.gallery.<Test> com.lithic.gallery.debug.test/androidx.test.runner.AndroidJUnitRunner`.
  Not `gradlew connectedAndroidTest`: it may uninstall the app afterwards, which deletes the
  1.2 GB of models in its folder.
- Emulator end to end: the `gallery_test` AVD (16 GB data), `tools/seed-emulator.sh --models`,
  `tools/emu-search.sh "query" out.png` (refuses unless the gallery is in front, so keystrokes
  never land in another app).
- One APK per ABI: phones take `app-arm64-v8a-*.apk`.
- Release: `build-release.bat` (signed with the key `keystore.properties` names, kept in
  `~/.android`, outside git), `install-on-phone.bat` (builds, installs over USB with `-r`).
  Release builds are minified: after touching reflection-sensitive code (ONNX Runtime, Room,
  serialization, WorkManager), run the release APK on the emulator, not only the debug one.

## Version

`appVersion` in `gradle.properties` (major.minor.patch) with `appVersionCode` =
major×10000 + minor×100 + patch beside it; the build refuses them if they disagree. Bump it
whenever the app is rebuilt to be installed for use or released: patch for fixes, minor for
features, major for overhauls. Debug builds installed to test (on the emulator, or a phone
during development) do not count: they carry `-debug` and replace each other. 0.1.0 is the
first build handed over.

## Traps met

- adb-created folders in `Android/data/<app>` belong to the shell user and the app cannot read
  files copied into them: the app makes its `models` folder, the copy scripts open it first.
- CRAFT needs ~1.3 KB per input pixel: never run it whole on a large canvas (see tiling in
  `design.md`).
- Room schema changes need a migration (`GalleryDatabase`), not a reinstall: the models live
  in the app's folder and a clear-data loses them.
