# Music-droid
A simple Android music player using the Spotify iFrame embed (via an in-app WebView) and NFC. Think "Yoto Spotify Player."

No 3d printer, no custom hardware, no soldering, and no flashing of operating systems needed. You'll just need to be patient copy/pasting text around and mucking with phone settings.

![Music Droid Demo](musicdroid_demo.gif)

## How it works
Music Droid hosts the [Spotify iFrame API](https://developer.spotify.com/documentation/embeds) inside an embedded WebView. When you tap an NFC tag containing a Spotify URI, the app loads that album/playlist/track into the embed and starts playback automatically. Autoplay (which browsers normally block) is enabled because the app owns the WebView and turns off the user-gesture requirement.

Because playback runs through the web embed, you **don't** need the Spotify app installed and you **don't** need a Spotify Developer key. You just log in to Spotify once inside the app.

`musicdroid://` URIs that point at an MP3 or PLS/PLU stream are still played locally via ExoPlayer.

## Requirements
- An Android device with an NFC reader and running Android 7.0+ (I'm using a carrier-locked 2025 Moto G that cost me $35).
- Wifi.
- A Spotify Premium account (without Premium, the embed only plays ~30-second previews). Cannot be a Kids account.
- Some writeable NFC tags.

## Dedicated device set-up
1. If you're using a dedicated device, no need to log-in with your Google account. Skip all log-in prompts upon set-up and continue in guest mode.
2. In the device settings, disable the lock screen and enable tap to wake (i.e. double tap the screen wakes the device up and shows the home screen / currently running app).

## Download Music Droid
You can download the latest APK from the [**Actions** tab](https://github.com/akdotcom/Music-droid/actions?query=branch%3Amain) of this repository. Select the latest successful "Android CI" run and look for **app-release** in the Artifacts section.

Install the app on your target phone. If you haven't already, you'll need to enable the "Install unknown apps" setting for the phone.

## Logging in to Spotify
1. Launch Music Droid and tap the gear/settings icon.
2. Tap **Log in to Spotify** and sign in with your Spotify Premium account inside the WebView.
3. When you're done logging in, press the device **back** button to return to the player. Your session is remembered, so full tracks will play.
4. Assuming you're using a dedicated device for Music Droid, you'll want to [pin](https://support.google.com/android/answer/9455138?hl=en) the app once you're logged in.

If you ever need to switch accounts, use **Log out** in Settings.

## Making music
1. Buy some NFC tags and download an NFC writing app, e.g. [NFC tools](https://www.wakdev.com/en/apps/nfc-tools-android.html).
2. Go to a Spotify album, grab the "Share Link" URL. It'll be of the format: `https://open.spotify.com/album/STRING_OF_LETTERS?si=-STUFF_YOU_DONT_CARE_ABOUT`.
3. Enter the URI `spotify:album:STRING_OF_LETTERS` as a "Custom URI" in your NFC app and then write it to the NFC tag.
4. Hold the NFC tag to the back of your phone while the screen is unlocked. Initially it'll ask which app should handle the URI intent: choose Music Droid `Always`.
5. You're good to go!

> Tip: the Settings screen has a "URI Generator" that turns a pasted Spotify share link (or MP3/PLS link) into the right `spotify:` / `musicdroid:` URI to write to your tag.
