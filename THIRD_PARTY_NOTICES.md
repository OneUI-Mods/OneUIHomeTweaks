# Third-party components

- `io.github.libxposed:api:102.0.0`: modern libxposed API 102, Apache License 2.0. Resolved from Maven Central; compile-only and not included in the installed APK.
- dexlib2 2.5.2: BSD 3-Clause, https://github.com/JesusFreke/smali . Used for DEX inspection. Its transitive dependencies, including Guava, retain their own licenses.
- Material Components for Android 1.14.0 and AndroidX: Apache License 2.0. https://github.com/material-components/material-components-android and https://android.googlesource.com/platform/frameworks/support/ .
- Gradle wrapper: Apache License 2.0, https://github.com/gradle/gradle .

The MIT license applies to the original module code, not to these dependencies. Dependencies' license notices are preserved in their distributions. No Samsung or MultiPicture executable or decompiled implementation is redistributed here.

The complete runtime artifact inventory is [third_party/components.tsv](third_party/components.tsv). The build verifies it and generates the in-app attribution and full license text assets. See [review sources](third_party/README.md).
