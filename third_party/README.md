# Dependency license inventory

`components.tsv` records each resolved runtime artifact (including transitives) and the compile-only Xposed API. The build compares it with Gradle's resolved artifact coordinates and fails for additions, removed dependencies, or changed versions until the inventory is reviewed. Full texts are packaged into the in-app license viewer.

Sources used for review:

- Maven POM license fields of the exact installed versions from Google Maven / Maven Central.
- Apache license: https://www.apache.org/licenses/LICENSE-2.0.txt
- dexlib2 and bundled notices: https://github.com/JesusFreke/smali/blob/v2.5.2/NOTICE
- Checker Framework 2.5.2 notices: https://github.com/typetools/checker-framework/blob/checker-framework-2.5.2/LICENSE.txt . Only the annotation artifact is bundled; its permissive MIT option is used. The upstream notice is included in full for attribution; the Checker Framework implementation is not bundled.
- Animal Sniffer: https://github.com/mojohaus/animal-sniffer/blob/master/LICENSE (MIT; the selected artifact is 1.17).
- Guava 27.1: https://github.com/google/guava/blob/v27.1/COPYING (Apache-2.0, also inherited from its parent POM).
- libxposed API 102: https://github.com/JingMatrix/libxposed-api (compile only).

The list concerns code distributed in the APK plus the repository's compile-only API. JUnit and build tools are not shipped in the APK; their respective upstream licenses still apply when those tools are redistributed.
