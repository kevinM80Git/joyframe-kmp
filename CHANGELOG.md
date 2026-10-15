# Changelog

## Unreleased — TV pacing, render diagnostics, night skies, vertex colours, instancing, per-pad rumble, phone controllers (with HTTPS tilt)

- Android `GameView`/`SplitGameView` draw with 4x MSAA, like iOS, macOS and the browser already do,
  falling back to no antialiasing on a GPU that offers no multisample config. Before, every edge on
  Android was aliased, and badly so when `GameViewOptions` scales the buffer up to a large screen.

Phone controllers, second pass (RIFT RUSH Phase 14: "tough to control", "add gyro"):

- `TouchSteer`: left/right steering for touch screens, made to be used without looking. The whole
  area is the control: the finger lands anywhere and that spot is centre, `reach` (72 dp) sideways is
  full lock, and past full lock the centre follows the finger so easing back steers back at once.
  X only (a two-axis stick couples the axes and has to be found by eye).
- `PhonePadController`'s DRIVE layout: `TouchSteer` over the whole left half (or a tilt wheel, tap to
  re-centre), BRAKE and BOOST filling the right half edge to edge, small AUTO GAS / TILT / RESET /
  PAUSE toggles on top. `PhonePadUi.onPress`: the client buzzes the phone as a thumb lands on a control.
  The page remembers tilt or touch (not on iOS, where tilt needs a tap for permission).
- `PhonePads(secure = true)`: HTTPS and `wss:`, because browsers give motion sensors only to secure
  pages. A self-signed certificate (ECDSA P-256, the device's LAN address as an IP subjectAltName,
  serverAuth, 800 days: what iOS and Chrome ask of a server certificate) is built in DER by hand and
  kept in app-private storage (Android `filesDir`, desktop `~/.joyframe`), replaced only when it
  expires or the address changes, so each phone accepts the "not private" warning once. The same
  files are served over plain HTTP on the next port; the page goes there by itself when the browser
  will not open a WebSocket to a self-signed certificate (Safari), touch steering only.
- `DeviceTilt`: a short low-pass on the steer (about 50 ms at 60 readings a second) for shaky hands;
  in browsers `available` is false on insecure pages, where no motion events arrive.
- Server output is buffered (one frame or response per TLS record and packet).
- Android logs `4x MSAA` when the multisample config is granted (and the warning when it is not).

Phones as controllers (`io.github.rehaancubess.joyframe.input.phone`):

- `PhonePads` (desktop and Android): a small HTTP + WebSocket server on the local network. A phone
  that opens its `url` gets a controller page in the browser (nothing to install) and appears in
  `pollAll()` as one more `ConnectedGamepad` (`phone-…`, the same id when it reconnects). `show()`
  sends a phone its player's label, colour, layout (MENU / DRIVE) and status; `rumble()` vibrates it
  (Android browsers). Presses shorter than a frame are latched for one poll. Not supported on iOS or
  in browsers (`supported` is false). Android apps need `android.permission.INTERNET`.
- `PhonePadController` + `PhonePadUi`: the controller in Compose, built from Joyframe's own
  `TouchStick` (and `DeviceTilt` for tilt steering). The new `phonepad` module compiles it to
  Kotlin/Wasm (~3.7 MB gzipped); an app bundles that build and serves it with
  `bundledPhonePadFiles()`. Browsers without WebAssembly GC get the light HTML page (`PhonePadPage`,
  also at `/lite`).
- `PhonePadFiles` (what the server hands out, with `.gz` / `.gzip` pre-compressed variants and ETags),
  `PhonePadProtocol` (the text messages), `QrCode` (byte mode, level M, versions 1–10) to show the
  address on a TV.
- New `jvmSharedMain` source set: code shared by desktop and Android.

Fire TV performance pass (measured on a Fire TV Stick 4K 2nd gen, PowerVR GE9215, fill-bound):

- Lean scene program: every material that is not water, vortex or foam is drawn by a lean variant
  (no water/vortex paths; shadow sampling compiled in only when a light casts shadows). Its fragment
  stage runs at mediump on GLES; positions stay highp in the vertex stage, which passes a camera vector
  scaled by 1/1024 so its squared length fits half precision. Chosen per batch automatically; output
  identical on desktop GL. Measured: GPU time per frame 35 → 27 ms on the stick.
- Material uniforms are only sent when they change between batches; per-pane frame uniforms are set
  once per program. Render-thread CPU per 4-pane frame 15 → 10 ms.
- Models resolve to meshes, local matrices and materials once per asset set (no String-keyed lookups
  per part per pane); static instances keep their final part matrices (`SceneInstance.gpuPartMatrices`,
  renderer scratch); `interpolatedTransform()` returns the transform itself when there is nothing to
  interpolate (no allocation).
- The shadow map (2048² depth, 16 MB) is allocated the first time a frame casts shadows; an
  unsupported shadow framebuffer now means no shadow maps instead of the software renderer.
- The sky pass skips its sun terms when `skySun` is 0.
- Android `LoopVoices`: all voices are mixed in software (`LoopMixer`, common code) into one stereo
  `AudioTrack` on an audio-priority thread, instead of a SoundPool stream per voice. Measured with 18
  looping voices: SoundPool 22% of a core → mixer ~6%; audioserver 31% → ~6–9%.
- `RenderStats.prepareMillis` (CPU time preparing a frame), `gpuWaitMillis` (with
  `RenderDiagnostics.syncGpu`: how long `glFinish` waited, a GPU-time proxy where timer queries are
  missing) and `liteDrawCalls`. `RenderDiagnostics.skip` / `fullShaderOnly`: diagnostics switches to
  attribute GPU time (sky, opaque, blended, static blended) and A/B the lean program.
- `GlApi.finish()` on every GL binding.


- `PlatformGamepad.rumble(controllerId, durationMs, intensity)`: rumble one controller by its
  `ConnectedGamepad.id`, so each local player feels their own events. Android (per input device
  vibrator) and browsers (`vibrationActuator` per pad); iOS and desktop are no-ops for now.
- `PcmSound.toWav()`: the sound as a 16-bit mono WAV, to save generated audio or inspect it.

- Instanced batching (GL: Android, desktop, WebGL2): every draw is now instanced. Parts that share a
  mesh range, material and render state are grouped into one `glDrawElementsInstanced`. Per-instance
  model and normal matrices and a tint travel in one per-pane instance buffer (orphaned, uploaded once).
  Opaque draws are grouped by state, alpha draws keep far-to-near order (contiguous runs batch), and
  additive draws are grouped after them. `RenderStats.instancesBatched` reports how many part
  instances went through batches. Metal still issues one draw per instance, with the same results.
- `SceneInstance.tint`: multiplies base colour and glow of parts whose material is
  `GpuMaterial.tintable` (default true; set false for parts that keep their colour). Instances that
  differ only in tint and transform share a material and batch together.
- `GpuMaterial.emissiveMaskFromVertexAlpha`: on opaque materials, each vertex's alpha masks the glow,
  so lit surfaces and light strips can live in one mesh and one draw.
- `SceneEnvironment.saturation` / `contrast`: the colour grade, previously hard-coded (defaults unchanged).
- `SceneEnvironment.emissiveFogResistance` (0..1): neon punches through fog instead of fading with it.
- Night sky: `skyStars` (a twinkling star field), a moon or planet disc (`moonDirection`, `moonRadius`,
  `moonColor`, `moonGlow`) and `skyline` / `skylineColor` / `skylineLights` (a two-layer city
  silhouette with lit windows around the horizon, drawn by the sky pass, so it costs no draws and the
  far plane never cuts it). Each is skipped entirely at 0. GL and Metal.

- Vertex colours: `IndexedMesh.colors` (optional, one `PackedColor` per vertex) multiplies the
  material's base colour and albedo texture. `GpuMaterial.emissiveFromVertexColor` and
  `emissiveFromAlbedo` tint emission per vertex / per texel, so one material can carry many
  differently coloured neon strips or a glyph atlas, with no extra draw calls. Meshes without colours
  render exactly as before (white). GL and Metal; the vertex layout grows from 9 to 12 floats.

- `FrameRateLimiter`: runs a loop at a steady fraction of the display rate (30 Hz on a 60 Hz TV,
  an even 25 Hz on 50 Hz panels) from vsync timestamps, with jitter tolerance and stall recovery.
- `RenderDiagnostics.latest` / `RenderStats`: the GL backend reports, per presented frame and summed
  over panes, render-thread submit time, draw calls (scene and shadow), triangles, and instances
  drawn vs frustum-culled. Metal does not report yet.
- `SceneEnvironment.skyZenith/skyHorizon/skyCloud/skySun/skyClouds`: the sky gradient, sun disc and
  cloud cover are configurable (defaults unchanged). `skyClouds = 0` skips the per-pixel cloud noise
  entirely, which saves fill rate on weak GPUs. GL and Metal.
- `LoopVoices`: a fixed set of looping voices with live volume, pitch (0.5–2) and pan, for engines and
  tyre noise. A worker applies changes at most 30 times a second and skips inaudible ones. Android
  SoundPool (`setRate`), macOS OpenAL (`AL_PITCH`), iOS `AVAudioPlayer` (`enableRate`), Web Audio
  (`playbackRate`); Windows/Linux Java Sound applies pitch only where the mixer exposes SAMPLE_RATE.
- Android: pads that report L2/R2 as digital buttons (no trigger axes) now drive `leftTrigger` /
  `rightTrigger` at full travel.
- Build: the foojay toolchain resolver lets Gradle fetch the JDK 17 toolchain when only a newer JDK
  is installed.

## 0.1.0-alpha04 — smooth by default, tilt steering (Maven Central, 28 September 2026)

- `GameViewOptions` on `GameView`/`SplitGameView`, with the source game's measured defaults:
  0.8 render scale on Android, 1920-pixel / 2.07 MP cap on iOS and browsers, 60 Hz on high-refresh
  Android panels, sustained performance mode. `GameViewOptions.Full` renders every native pixel.
- Android `GameView` now draws on a free-running GL thread from a frame mailbox instead of being
  pumped by composition (the change that took the game from 39 to 60 fps on a mid-range phone).
- iOS sizes the Metal drawable explicitly; browsers size the WebGL buffer from the options.
- `FramePacing`/`FramePacingReport`: fps, late-frame %, p95/p99 and worst interval. Lake Lab shows it.
- `DeviceTilt`/`TiltMath` and `ActionInput.tiltSteer`: roll-to-steer from gravity on Android, iOS and
  phone browsers; Lake Lab's "Tilt to steer".
- Audio: loop-volume changes under 0.005 are skipped and `MusicPlayer` fades step at 20 Hz.
- iOS ignores the Simulator's synthetic "Gamepad" stand-in, as the source game does.
- Docs: `why.md` (the measured problems behind the library), `comparison.md`, `vibe-coding.md`,
  and `AGENTS.md` / `CLAUDE.md` for AI coding assistants.

## 0.1.0-alpha03 — the game's feel, couch play and sound (local, not yet released)

- `ChaseCamera`: the source game's damped chase framing, speed-widened lens, cuts and shake.
- `FixedTimestep` for constant-rate physics with interpolated drawing.
- `Buoyancy.pose` (hull rides the drawn swell) and `Whirlpool` pull/swallow physics.
- Whirlpool funnel dishes (`Primitives.whirlpool`, `whirlpoolMaterial`) and lake holes (`WaterHole`).
- `Weather`: camera-following snow, rain and blowing sand, with matching fog.
- `SplitGameView` and `SplitLayout`: up to four panes in one GPU surface on every target.
- `PlatformGamepad.pollAll()`, `GamepadSeats`, full raw buttons/triggers/right stick.
- `KeyBindings` (two players on one keyboard) and `ActionFrame.drive` (steer + throttle incl. triggers).
- Stereo pan on `AudioPlayer.play`, `SpatialMix`/`GpuCamera.hear`, loop volume, and `MusicPlayer` with fades.
- `ModelTurntable`, `turntableCamera`, `modelBounds`, `Primitives.octahedron`.
- `OffscreenRenderer` (desktop macOS) for screenshots and GIFs.
- Lake Lab: Lake / Split screen / Hangar modes, chase toggle, whirlpools, weather, bell buoy,
  generated music, and `:sample:captureDemo`. GitHub Pages workflow for the browser build.
- Behaviour changes: D-pad now moves `ActionFrame.movement`; browser raw `leftStickX` no longer
  includes the D-pad; `AudioBackend.play` gained a pan argument (internal).

## 0.1.0-alpha02 — Lake Lab and consumer APIs (local, not yet released)

- Procedural lake/boat/tree showcase with live settings, code panel and diagnostics.
- Scene builders, mesh primitives, shared input actions, touch stick and GameSession.
- Uniform positive-up raw controller Y (behavior change on Android/browser/GLFW).
- Owned AudioPlayer, readiness/failure/closed states, PCM16 WAV input and cleanup.
- Android sample host, browser executable, iOS framework entry and independent Maven consumer.
- Additional API/lifecycle/audio/scene regression tests. See verification.md for actual runtime coverage.

## 0.1.0-alpha01 — initial extraction (not yet a Central release)

- Shared scene, material, mesh, GLB and water APIs; OpenGL/GLES/WebGL2 and Metal backends.
- Compose game viewport, including offscreen CGL compositing on macOS.
- First-controller arcade mapping and configurable deadzones/sensitivity.
- User-defined PCM sound bank, native bounded cue queues and optional loop.
- Standalone asset-free desktop playground, 18 tests, platform compile CI,
  source-disclosure guard and local/Central publishing configuration.

Experimental: see the platform guide for mapping/rumble limitations, audio
bank lifetime, browser context loss and viewport-overlay restrictions.
