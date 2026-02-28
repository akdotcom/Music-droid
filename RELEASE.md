# Building a Release Version

To build a signed release version of Music Droid on your MacBook Air, follow these steps:

## Prerequisites

1.  **Java 21**: Ensure you have Java 21 installed. You can check with `java -version`.
2.  **Android SDK**: Ensure you have the Android SDK installed and `ANDROID_HOME` environment variable set.
3.  **Spotify Developer Dashboard**: Ensure your Release SHA1 fingerprint is registered in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).

## Step 1: Generate a Signing Key

If you don't already have a keystore, you can generate one using `keytool`:

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

Follow the prompts to set passwords and organization details.

## Step 2: Set Environment Variables

To keep your credentials secure, the build script uses environment variables. You can set them in your terminal or add them to your `~/.zshrc` (or `~/.bash_profile`):

```bash
export KEYSTORE_PATH="/path/to/your/my-release-key.jks"
export KEYSTORE_PASSWORD="your_keystore_password"
export KEY_ALIAS="my-key-alias"
export KEY_PASSWORD="your_key_password"
```

## Step 3: Build the APK

Run the following command from the root of the project:

```bash
./gradlew assembleRelease
```

The signed APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

## Step 4: Get Release SHA1 for Spotify

Spotify authentication requires the SHA1 fingerprint of your signing key. You can retrieve it using:

```bash
keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS"
```

Look for the `SHA1` value in the output and add it to your app's configuration in the Spotify Developer Dashboard.

## Building on GitHub Actions

You can also build the signed release APK automatically using GitHub Actions. This ensures a stable SHA1 fingerprint without needing to build locally every time.

### 1. Prepare your Keystore

First, encode your keystore file to Base64:

```bash
openssl base64 -in my-release-key.jks -out my-release-key.jks.base64
```

### 2. Add GitHub Secrets

Go to your repository settings: **Settings > Secrets and variables > Actions** and add the following **Repository secrets**:

*   `RELEASE_KEYSTORE_BASE64`: The content of the `my-release-key.jks.base64` file.
*   `RELEASE_KEYSTORE_PASSWORD`: Your keystore password.
*   `RELEASE_KEY_ALIAS`: Your key alias.
*   `RELEASE_KEY_PASSWORD`: Your key password.

### 3. Automated Builds

The GitHub Actions workflow (`.github/workflows/android.yml`) is configured to:
1.  Decode the keystore from the secret.
2.  Build the release APK using the provided secrets.
3.  Upload the signed APK as an artifact named `app-release`.

Every time you push to `main` or a branch matching `build-apk*`, a new signed release will be built.
