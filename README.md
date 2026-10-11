# Web2APK Builder

Android app that turns a website into an installable APK, entirely on the device.
The long-term goal is to build APKs from a website URL or a ZIP archive with no PC involved.

> Personal project. Not intended for Google Play distribution.

## Status

Early development. Current version: **v0.3** (URL mode works: build, sign and install on the phone).

## How it works

1. A prebuilt WebView template APK is bundled inside the app.
2. The builder injects the site name and URL into the template (`assets/config.json`).
3. The APK is repacked (with proper ZIP alignment) and signed on the device.
4. The result is saved to Downloads and can be installed.

## Stack

Kotlin · Jetpack Compose · Material 3 · Min SDK 26 · package `com.web2apk.builder`
Template module: Java, plain WebView, package `com.web2apk.template`
Signing: [apksig](https://developer.android.com/tools/apksigner) with a key kept in the Android Keystore

## Roadmap

| Version | Goal |
|---------|------|
| v0.1 | Project skeleton |
| v0.2 | ZIP import |
| v0.3 | WebView template, on-device build and signing (URL mode) |
| v0.4 | Permissions, in-app install |
| v0.5 | App name and icon |
| v0.6 | Unique package name per app |
| v0.7 | ZIP mode (local files) |
| v1.0 | Release |

## Principle

An APK generated from a site must keep everything that works on the site.