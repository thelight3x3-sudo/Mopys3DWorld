# Mopys 3D World - GitHub Actions Cloud Build

This project is made for cloud building on GitHub Actions, not Termux.

## What changed in FIX18

- Uses normal Android Gradle project structure.
- Uses GitHub Actions to build the APK on Ubuntu cloud runner.
- Keeps the 3D world, new player model, attack cooldown, trees, lakes, mountains, day/night, rays, shadows, and controls.
- Removes the expensive ray-marched lighting from FIX17 because it lagged on phones.
- Uses optimized directional sun/moon lighting + projected character shadow instead.

## Beginner phone workflow

1. Create a GitHub account.
2. Create a new empty repository named `Mopys3DWorld`.
3. Upload all files from this folder to the repository.
4. Open the repository on GitHub.
5. Tap the `Actions` tab.
6. Choose `Build Android APK`.
7. Tap `Run workflow`.
8. Wait until the build finishes with a green check.
9. Open the finished workflow run.
10. Scroll to `Artifacts` and download `Mopys3DWorld-debug-apk`.
11. Extract the downloaded artifact zip. Inside is `app-debug.apk`.
12. Install `app-debug.apk` on your Android phone.

## Termux upload commands

Replace `YOUR_GITHUB_USERNAME` with your GitHub name.
Create an empty repository on GitHub first.

```bash
pkg update
pkg install git unzip
cd /sdcard/Download
unzip -o Mopys3DWorld_GitHubActions_FIX18.zip
cd Mopys3DWorld_GitHubActions_FIX18
git init
git branch -M main
git add .
git commit -m "Mopys 3D World cloud build"
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/Mopys3DWorld.git
git push -u origin main
```

GitHub no longer accepts account passwords for git push. Use a GitHub Personal Access Token as the password when Termux asks.

## Local PC build command

If you later use a PC with Android Studio/Gradle, build with:

```bash
gradle :app:assembleDebug
```

The APK will be here:

```text
app/build/outputs/apk/debug/app-debug.apk
```
