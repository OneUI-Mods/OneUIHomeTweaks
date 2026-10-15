# Third-party components

- `app/libs/xposed-api-82.jar`: rovo89's Xposed API 82, Apache License 2.0. Downloaded from https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar . SHA-256: `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`. Compile-only; not included in the installed APK.
- dexlib2 2.5.2: BSD 3-Clause, https://github.com/JesusFreke/smali . Used for DEX inspection. Its transitive dependencies, including Guava, retain their own licenses.
- Material Components for Android 1.14.0 and AndroidX: Apache License 2.0. https://github.com/material-components/material-components-android and https://android.googlesource.com/platform/frameworks/support/ .
- Gradle wrapper: Apache License 2.0, https://github.com/gradle/gradle .

The MIT license applies to the original module code, not to these dependencies. Dependencies' license notices are preserved in their distributions. No Samsung or MultiPicture executable or decompiled implementation is redistributed here.

The complete runtime artifact inventory is [third_party/components.tsv](third_party/components.tsv). The build verifies it and generates the in-app attribution and full license text assets. See [review sources](third_party/README.md).
