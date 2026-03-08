# Music-droid
A simple Android music player using the Spotify App Remote SDK and NFC. Think "Yoto Spotify Player."

![Music Droid Demo](musicdroid_demo.gif)

## Requirements
- An Android device running Android 7.0+ (I'm using a carrier-locked 2025 Moto G that cost $35)
- A Spotify Preumium account (can *not* be a Kids account)
- Some writeable NFC tags

## Dedicated device set-up
1. If you're using a dedicated device, no need to log-in with your Google account. Skip all log-in prompts upon set-up and continue in guest mode.
2. Since you won't be able to use Google Play, install Spotify via [Aurora Store](https://auroraoss.com/aurora-store) (scroll down and pick the `Release` option)
3. Log-in to Spotify
4. In the device settings, disable the lock screen and enable tap to wake (i.e. double tap the screen wakes the device up and shows the home screen / currently running app)

## Download Music Droid
You can download the latest APK from the **Actions** tab of this repository. Select the latest successful "Android CI" run and look for **app-release** in the Artifacts section.

Install the app on your target phone. If you haven't already, you'll need to enable the "Install unknown apps" setting for the phone. Launch the app and click on the wrench logo to get to Settings.

## Registering for a Shopify developer key
In order to use Music Droid, you'll need to
1. Create your own "app" in the [Shopify Developer Dashbaord](https://developer.spotify.com/dashboard).
2. Add `com.akdotcom.musicdroid://callback` as your Spotify app's `Redirect URIs`.
3. Add `com.akdotcom.musicdroid` under `Android packages`'s `Package name` along with the Music Droid Settings' SHA1 string under `Package SHA1 fingerprint`.
4. Under `Which API/SDKs are you planning to use?` make sure you select `Android`.
5. Save the app. Near the top of the app page in the Spotify Dashboard, you should see a header for `User Management`, click into that tab.
7. Add the name and email address of the account you used to log-in to Spotify on this device.
8. Once this is done, go back to your Spotify App profile and copy the `Client ID` and paste it into the relevant text box in Music Droid Settings.

## Configuring your device
1. Make sure you've got your Spotify Client ID, SHA1, and Users all squared away (see above).
3. Your first attempt to play music should trigger an Auth flow. If it doesn't (or there are problems) then go into Settings and try the `Reconnect` or `Force Auth` options.
4. Assuming you're using a dedicated device for Music Droid, you'll want to [pin](https://support.google.com/android/answer/9455138?hl=en) the app once you've done authenticating.

  ## Making music
1. Buy some NFC tags and download an NFC writing app, e.g. [NFC tools](https://www.wakdev.com/en/apps/nfc-tools-android.html).
2. Go to a Spotify album, grab the "Share Link" URL. It'll be of the format: `https://open.spotify.com/album/STRING_OF_LETTERS?si=-STUFF_YOU_DONT_CARE_ABOUT`.
3. Enter the URI `spotify:album:STRING_OF_LETTERS` as a "Custom URI" in your NFC app and then write it to the NFC tag
4. Hold the NFC tag to the back of your phone while the screen is unlocked. Initially it'll ask which app should handle the URI intent: Spotify or Music Droid. Choose Music Droid `Always`.
5. You're good to go!
