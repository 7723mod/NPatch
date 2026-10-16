# Neo LSPatch Framework

[![Documentation](https://img.shields.io/badge/Docs-npatch.nkbe.top-d85c80?logo=bookstack)](https://npatch.nkbe.top/en/) [![Download](https://img.shields.io/github/v/release/7723mod/NPatch?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/7723mod/NPatch/releases/latest) [![Total](https://shields.io/github/downloads/7723mod/NPatch/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/7723mod/NPatch/releases)

> Rootless Xposed framework powered by Vector core.

Official Website: [npatch.nkbe.top](https://npatch.nkbe.top/en/)

## Introduction

NPatch patches an Xposed loader and the [Vector](https://github.com/HSSkyBoy/Vector) core directly into the target APK, allowing Xposed modules to run seamlessly without Root privileges or system modifications. The patched APK carries its own loader and native core, with no extra extraction required at runtime.

### Patching Modes

- **Local Mode (`--manager`)**: Patches apps without embedding modules. Modules and scope are managed dynamically by NPatch Manager, so changing or updating modules requires no re-patching.
- **Integrated Mode (`--embed`)**: Embeds modules directly into the patched APK, running standalone without requiring NPatch Manager.

Recent hook-stack stability improvements:

- **LSPlt:** avoids a transient unmapped GOT/PLT region while installing hooks. This prevents dex2oat/zygote crashes on affected 32-bit Android 10 devices.
- **LSPlant:** initializes `CloseGuard` for synthetic `DexFile` objects on Android Nougat and below, and aligns hook trampolines correctly for improved ART hook compatibility.

We sincerely invite you to join our [Telegram](https://t.me/NPatch) group to get more information and updates about NPatch.

## Supported Versions

- **Minimum:** Android 9 (API 28)
- **Maximum:** **Android 17 (SDK 37)**

## Download

- **Stable Releases**: Available on [GitHub Releases](https://github.com/7723mod/NPatch/releases/latest)
- **Canary Builds**: Test versions released by developers, available on the Telegram channel [@ONPatch](https://t.me/ONPatch)

## Usage

### Through Manager (Recommended)
1. Download `manager-v<verName>-<verCode>-release.apk` from Releases and install it.
2. Select target application and patch it on-device with GUI guidance.

### Through CLI (Jar)
```bash
java -jar jar-v<verName>-<verCode>-release.jar --manager target.apk
```
See the [Usage Guide](https://npatch.nkbe.top/en/guide/usage.html) for detailed CLI arguments and options.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=7723mod/NPatch&type=Date)](https://star-history.com/#7723mod/NPatch&Date)

## Credits

- [Vector](https://github.com/HSSkyBoy/Vector): Core runtime
- [Xpatch](https://github.com/WindySha/Xpatch): Fork source
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib): Repacking tool

## License

NPatch is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
