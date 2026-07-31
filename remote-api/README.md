# NPatch Remote API

This Android library contains the module-app client for NPatch's manager-backed Remote Store.
The release AAR is copied to `out/release` by `:remote-api:buildRelease`.

It requires the libxposed service interface:

```kotlin
implementation("io.github.libxposed:interface:102.0.0")
implementation(files("libs/npatch-remote-api.aar"))
```
