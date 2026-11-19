# Nulls Observer for Android

A local-first Android companion for recording and reviewing Null's Royale matches.

## Current scope

- Explicit Android screen-capture consent before every recording session.
- Foreground recording service with a persistent Stop notification.
- H.264 MP4 recordings saved under **Movies/NullsObserver**.
- In-app list of saved recordings; tap a recording to open it in a video player.
- No account login, network upload, gameplay input automation, or anti-detection behavior.

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and on manual dispatch.

The artifact is named `nulls-observer-debug-apk`.

## Device requirements

- Android 10 (API 29) or newer.
- Screen recording permission granted by the user for each capture session.

## Important

This is an initial implementation. It has not yet been compiled or tested on a POCO F6. A successful GitHub Actions run and a real-device recording test are required before calling it install-ready.
