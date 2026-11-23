# Vector loads the complete APK DEX into every scoped process. Strip unreachable code
# while leaving reflection names and compiled hook behavior intact.
-dontobfuscate
-dontoptimize

# Vector discovers this class from META-INF/xposed/java_init.list, outside the manifest.
-keep class dev.ryuya.oneuihometweaks.HookEntry { *; }
