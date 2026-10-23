package dev.ryuya.oneuihometweaks;

/** Independently recorded contracts for the two reviewed launcher APKs. */
record FolderContracts(
    String manager,
    String animator,
    String prepare,
    String resetGeometry,
    String viewModelField,
    String managerGetter) {
  static FolderContracts forSdk(int sdk) {
    return switch (HookCompatibility.platform(sdk)) {
      case ANDROID_16 -> new FolderContracts("g3.i", "K5.p1", "t", "c", "f", "Y");
      case ANDROID_17 -> new FolderContracts("t5.h", "lb.e0", "A", "k", "c", "y0");
      case UNSUPPORTED -> throw new UnsupportedOperationException("Unreviewed folder platform");
    };
  }
}
