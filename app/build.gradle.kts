import java.util.Properties

plugins { id("com.android.application") }

val launcherCpuIconUpload = providers.gradleProperty("launcherCpuIconUpload").orNull == "true"

val distributionProperties =
    Properties().apply {
        providers.environmentVariable("ONEUI_SIGNING_PROPERTIES").orNull?.let { path ->
            file(path).reader(Charsets.UTF_8).use { load(it) }
            for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
                check(!getProperty(key).isNullOrBlank()) {
                    "Missing release signing property: $key"
                }
            }
        }
    }

android {
    namespace = "dev.ryuya.oneuihometweaks"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    buildFeatures { buildConfig = true }
    if (launcherCpuIconUpload) {
        ndkVersion = "29.0.13113456"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    defaultConfig {
        applicationId = "dev.ryuya.oneuihometweaks"
        minSdk = 31
        targetSdk = 37
        versionCode = 287
        versionName = "0.21.235"
        buildConfigField(
            "boolean", "LAUNCHER_CLAIM_QUEUED_CLOSING_PAIR",
            (providers.gradleProperty("launcherClaimQueuedClosingPair").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_CLAIM_QUEUED_CLOSING_WINDOW",
            (providers.gradleProperty("launcherClaimQueuedClosingWindow").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_FREE_GRID_INITIAL_PAGE_WORKER",
            (providers.gradleProperty("launcherFreeGridInitialPageWorker").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_FREE_GRID_SHUTDOWN_RECEIVER_WORKER",
            (providers.gradleProperty("launcherFreeGridShutdownReceiverWorker").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_PREWARM_HOME_UP_PROVIDER",
            (providers.gradleProperty("launcherPrewarmHomeUpProvider").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "STOCK_BATTERY_PASS_THROUGH_LOCK",
            (providers.gradleProperty("stockBatteryPassThroughLock").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "STOCK_WIDGET_SIZE_SAVE_BATCH",
            (providers.gradleProperty("stockWidgetSizeSaveBatch").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "STOCK_WIDGET_BROADCAST_WORKER",
            (providers.gradleProperty("stockWidgetBroadcastWorker").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "STOCK_SETTINGS_ACCOUNT_PROFILE_WORKER",
            (providers.gradleProperty("stockSettingsAccountProfileWorker").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "STOCK_SETTINGS_CATEGORY_QUERIES",
            (providers.gradleProperty("stockSettingsCategoryQueries").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_PREPARE_LIVE_ICON_BITMAPS",
            providers.gradleProperty("launcherPrepareLiveIconBitmaps").orNull.equals("true", ignoreCase = true).toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_DEOPTIMIZE_LIVE_ICON_CALLERS",
            providers.gradleProperty("launcherDeoptimizeLiveIconCallers").orNull.equals("true", ignoreCase = true).toString(),
        )
        testInstrumentationRunner = "dev.ryuya.oneuihometweaks.MemoryInstrumentation"
        buildConfigField("boolean", "LAUNCHER_CPU_ICON_UPLOAD", launcherCpuIconUpload.toString())
        // Live-card diagnostics are opt-in for local investigation, never default release behavior.
        buildConfigField(
            "boolean",
            "RECENTS_LIVE_TRACE",
            providers
                .gradleProperty("recentsLiveTrace")
                .map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_PERF_TRACE",
            providers.gradleProperty("shadePerfTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_WORK_TILE_TRACE",
            providers.gradleProperty("shadeWorkTileTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_LAYER_ID_TRACE",
            providers.gradleProperty("shadeLayerIdTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_RECOMPOSE_TRACE",
            providers.gradleProperty("shadeRecomposeTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_FRAME_RATE_CACHE",
            providers.gradleProperty("shadeFrameRateCache").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_TRIM_DEBOUNCE",
            providers.gradleProperty("shadeTrimDebounce").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "SHADE_TRIM_MERGE_VISIBILITY",
            providers.gradleProperty("shadeTrimMergeVisibility").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_PERF_TRACE",
            providers.gradleProperty("launcherPerfTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_BLUR_CACHE",
            providers.gradleProperty("launcherBlurCache").map { (it == "true").toString() }
                .getOrElse("true"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_TRANSITION_DISPLAY_LIST_REUSE",
            providers.gradleProperty("launcherTransitionDisplayListReuse").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean", "LAUNCHER_RENDER_TRANSACTION_MERGE",
            (providers.gradleProperty("launcherRenderTransactionMerge").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean", "LAUNCHER_STOCK_TRANSACTION_TRACE",
            (providers.gradleProperty("launcherStockTransactionTrace").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_REUSE_SEARCH_BACKGROUND_LAYOUT",
            providers.gradleProperty("launcherReuseSearchBackgroundLayout").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_OPTIMIZE_STACK_WIDGET_OPTIONS",
            providers.gradleProperty("launcherOptimizeStackWidgetOptions").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_PREPARE_STACK_WIDGET_OFF_MAIN",
            (providers.gradleProperty("launcherPrepareStackWidgetOffMain").orNull == "true").toString(),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_ICON_BUFFER_TRACE",
            providers.gradleProperty("launcherIconBufferTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_ICON_GEOMETRY_TRACE",
            providers.gradleProperty("launcherIconGeometryTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_CLOSING_ICON_SURFACE",
            providers.gradleProperty("launcherClosingIconSurface").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_DIRECT_CLOSING_SURFACES",
            providers.gradleProperty("launcherDirectClosingSurfaces").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_DETACHED_CLOSING_SPRING",
            providers.gradleProperty("launcherDetachedClosingSpring").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_DETACHED_OPENING",
            providers.gradleProperty("launcherDetachedOpening").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_SKIP_HIDDEN_OPENING_VIEWS",
            providers.gradleProperty("launcherSkipHiddenOpeningViews").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_SKIP_TRANSPARENT_CLOSING_BACKGROUND",
            providers.gradleProperty("launcherSkipTransparentClosingBackground").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_SKIP_DETACHED_WINDOW_UPDATES",
            providers.gradleProperty("launcherSkipDetachedWindowUpdates").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_CLOSING_ICON_PROJECTION_TRACE",
            providers.gradleProperty("launcherClosingIconProjectionTrace").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_SPRING_ALPHA_CACHE",
            providers.gradleProperty("launcherSpringAlphaCache").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_SPRING_REPLAY_PROBE",
            providers.gradleProperty("launcherSpringReplayProbe").map { (it == "true").toString() }
                .getOrElse("false"),
        )
        buildConfigField(
            "boolean",
            "LAUNCHER_OPEN_REPLAY_PROBE",
            providers.gradleProperty("launcherOpenReplayProbe").map { (it == "true").toString() }
                .getOrElse("false"),
        )
    }
    signingConfigs {
        if (!distributionProperties.isEmpty)
            create("distribution") {
                storeFile = file(distributionProperties.getProperty("storeFile"))
                storePassword = distributionProperties.getProperty("storePassword")
                keyAlias = distributionProperties.getProperty("keyAlias")
                keyPassword = distributionProperties.getProperty("keyPassword")
            }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = providers.gradleProperty("shrinkOneUiReleaseDex").orNull == "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

dependencies {
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("io.github.libxposed:service:102.0.0")

    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}

val exportRuntimeComponents =
    tasks.register("exportRuntimeComponents") {
        doLast {
            val components =
                configurations
                    .getByName("debugRuntimeClasspath")
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .map { it.moduleVersion.id.toString() }
                    .toSortedSet()
            val file = layout.buildDirectory.file("runtime-components.txt").get().asFile
            file.parentFile.mkdirs()
            file.writeText(components.joinToString("\n") + "\n")
        }
    }
val generatedLicenses = layout.buildDirectory.dir("generated/licenseAssets")

android.sourceSets
    .getByName("main")
    .assets
    .directories
    .add(generatedLicenses.get().asFile.absolutePath)

val dependencyLicenses =
    tasks.register("generateDependencyLicenses") {
        dependsOn(exportRuntimeComponents)
        inputs.file(rootProject.file("third_party/components.tsv"))
        inputs.dir(rootProject.file("third_party"))
        inputs.file(rootProject.file("LICENSE"))
        inputs.file(layout.buildDirectory.file("runtime-components.txt"))
        outputs.dir(generatedLicenses)
        doLast {
            val rows =
                rootProject
                    .file("third_party/components.tsv")
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.split('\t') }
            val licensed = rows.map { it[0] }.toSet()
            val actual =
                layout.buildDirectory
                    .file("runtime-components.txt")
                    .get()
                    .asFile
                    .readLines()
                    .filter { it.isNotBlank() }
                    .toSet() + "io.github.libxposed:api:102.0.0"
            check(actual == licensed) {
                "Review dependency licenses: missing=${actual-licensed}; obsolete=${licensed-actual}"
            }
            val out = generatedLicenses.get().asFile
            out.mkdirs()
            rootProject.file("LICENSE").copyTo(out.resolve("APP_LICENSE.txt"), true)
            val notices = buildString {
                append(
                    "Bundled runtime dependencies (including transitive dependencies), plus compile-only Xposed API.\n\n",
                )
                append(
                    "AndroidX / Material: The Android Open Source Project and respective contributors.\nKotlin / coroutines / annotations: JetBrains and respective contributors.\nGuava / Error Prone / J2ObjC / JSpecify: Google and respective contributors.\nlibxposed API: libxposed contributors.\n\n",
                )
                for (row in rows) append(
                    row[0] + "\n" + row[1] + "\n" + row.getOrElse(3) { "" } + "\n\n",
                )
                for (name in rows.map { it[2] }.distinct()) {
                    append("\n===== $name =====\n\n")
                    append(rootProject.file("third_party/$name").readText())
                }
            }
            out.resolve("THIRD_PARTY_NOTICES.txt").writeText(notices)
        }
    }

tasks.named("preBuild").configure { dependsOn(dependencyLicenses) }
