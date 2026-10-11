# Web2APK Builder

Android app that turns a website (URL) into an installable APK, entirely on the phone, with no PC needed. For personal use (not for Google Play).

## Status

**v0.7**: URL mode works end to end. Enter a name and URL, optionally pick an icon (otherwise it is fetched from the site), tap **Build APK**, then **Install**.

## How it works

1. The builder bundles a prebuilt WebView "template" APK in its assets.
2. On build, it repacks the template:
    - writes the site name and URL into `assets/config.json`;
    - edits the compiled binary `AndroidManifest.xml` (package name and app label);
    - replaces the launcher icon (cropped to a square, 192×192 PNG).
3. The APK is signed on the device (apksig, AndroidKeyStore RSA-2048 key, v2 + v3 schemes).
4. The signed APK is saved to Downloads and can be installed right from the app.

Each URL gets its own stable package name (`com.web2apk.app.a<hash of URL>`), so rebuilding the same URL updates the app, while a different URL installs as a separate app.

### Icon

Priority: icon picked manually, then icon found on the site, then the default icon.
The site icon is looked up in this order: `manifest.json` icons, `apple-touch-icon`, `<link rel="icon">` (PNG), then `/apple-touch-icon.png` and `/favicon.ico`. SVG icons are skipped.

## Features of the generated apps

- WebView with JavaScript, DOM storage and geolocation
- Runtime permissions for location, camera and microphone
- File chooser for uploads
- Links within the same site stay in the app; other links open in the browser
- HTTPS only
- Back button navigates the WebView history

## Stack

- Kotlin, Jetpack Compose (builder app)
- Java, plain Activity + WebView (template, no dependencies)
- `com.android.tools.build:apksig` for signing
- minSdk 26

## Project layout

- `app/` — the Builder app
    - `apk/` — `ApkBuilder`, `AxmlEditor`, `ApkSign`, `IconMaker`, `IconFetcher`, `Installer`
    - `importer/` — ZIP import
    - `assets/template.apk` — prebuilt unsigned template
- `template/` — source of the WebView template app

### Rebuilding the template

After any change in `template/`:

```
.\gradlew.bat :template:assembleRelease
```

Copy `template/build/outputs/apk/release/template-release-unsigned.apk` to `app/src/main/assets/template.apk`.

## Roadmap

| Version | Content | Status |
|---------|---------|--------|
| v0.1 | Project skeleton | done |
| v0.2 | ZIP import | done |
| v0.3 | WebView template, on-device build and signing (URL mode) | done |
| v0.4 | Permissions, in-app install | done |
| v0.5 | App name and unique package per app | done |
| v0.6 | Custom launcher icon | done |
| v0.7 | Icon fetched from the site | done |
| v0.8 | ZIP mode (offline sites via WebViewAssetLoader) | planned |
| v0.9 | Signing key backup / restore | planned |
| v1.0 | Release | planned |

## Known limitations

- The signing key lives in AndroidKeyStore. If the Builder is uninstalled or the phone is changed, apps built earlier can no longer be updated in place (key backup is planned).
- OAuth and external login flows are not handled yet.
- On Android 8–9 the APK is saved to the app's own Downloads folder instead of the public one.