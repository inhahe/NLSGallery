# NLSGallery

Natural Language Search Gallery. Its package id stays `com.lithic.gallery` (the name it had
before): Android would take another id for another app, and updating would lose the models and
descriptions in its folder.

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
- `gradlew :app:testDebugUnitTest`: category rules, caption text, model-failure blame, and
  (Robolectric, Room in memory) the search index kept up to date against one built afresh,
  descriptions and embeddings kept together, copied model files checked, places named from
  locations (and forgotten with the permission).
- On a phone: `tools\push-test-data.bat` (`emulator` for the emulator), `push-models.bat debug`,
  then run `OnDeviceModelTest` / `OnDeviceBenchmark` / `DescribeFailureTest` /
  `MediaDecodingDeviceTest` / `MigrationTest` (the last three also on the emulator, whose debug
  app has the models) with `adb shell am instrument -w -e class
  com.lithic.gallery.<Test> com.lithic.gallery.debug.test/androidx.test.runner.AndroidJUnitRunner`.
  Not `gradlew connectedAndroidTest`: it may uninstall the app afterwards, which deletes the
  1.2 GB of models in its folder.
- On a Snapdragon phone: `OnDeviceModelTest#gpuReadsAsEasyOcrDoes` (the 42 images' text on the
  GPU against EasyOCR's) and `GpuProbe` (CRAFT's speed and memory on the GPU, shape by shape and
  sustained; logs only). Time the GPU on a warm phone: within a minute of load it reaches thermal
  status 1 and runs at about half its cool speed, which is what describing a library gets.
- Emulator end to end: the `gallery_test` AVD (16 GB data), `tools/seed-emulator.sh --models`
  (geotagged pictures included: "in paris", "hawaii"), `tools/emu-search.sh "query" out.png`
  (refuses unless the gallery is in front, so keystrokes never land in another app).
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
- On the GPU a CRAFT session holds ~1.8 KB per pixel (float16) for as long as it is open, and
  float32 twice that: open sessions add up, and the phone kills the process without an
  exception. Two cached float32 sessions did.
- QNN's GPU backend needs `libOpenCL.so` declared in the manifest (`uses-native-library`), or it
  fails with `QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED`.
- Room schema changes need a migration (`GalleryDatabase`), not a reinstall: the models live
  in the app's folder and a clear-data loses them. Add each one to `MigrationTest`, and commit
  the schema KSP writes to `app/schemas`.
