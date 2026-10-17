import java.util.Properties

plugins { id("com.android.application") }
val distributionProperties = Properties().apply {
    providers.environmentVariable("ONEUI_SIGNING_PROPERTIES").orNull?.let { path ->
        file(path).inputStream().use { load(it) }
        for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
            check(!getProperty(key).isNullOrBlank()) { "Missing release signing property: $key" }
        }
    }
}
android {
    namespace = "net.ryuya_dev.oneuilivecolors"
    compileSdk = 36
    defaultConfig {
        applicationId = "net.ryuya_dev.oneuilivecolors"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.9.0"
    }
    signingConfigs {
        if (!distributionProperties.isEmpty) create("distribution") {
            storeFile = file(distributionProperties.getProperty("storeFile"))
            storePassword = distributionProperties.getProperty("storePassword")
            keyAlias = distributionProperties.getProperty("keyAlias")
            keyPassword = distributionProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            // Reflection and external hook contracts must retain their names.
            isMinifyEnabled = false
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
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}

val exportRuntimeComponents = tasks.register("exportRuntimeComponents") {
    doLast {
        val components = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }.toSortedSet()
        val file = layout.buildDirectory.file("runtime-components.txt").get().asFile
        file.parentFile.mkdirs(); file.writeText(components.joinToString("\n") + "\n")
    }
}
val generatedLicenses = layout.buildDirectory.dir("generated/licenseAssets")
android.sourceSets.getByName("main").assets.directories.add(generatedLicenses.get().asFile.absolutePath)
val dependencyLicenses = tasks.register("generateDependencyLicenses") {
    dependsOn(exportRuntimeComponents)
    inputs.file(rootProject.file("third_party/components.tsv"))
    inputs.dir(rootProject.file("third_party"))
    inputs.file(rootProject.file("LICENSE"))
    inputs.file(layout.buildDirectory.file("runtime-components.txt"))
    outputs.dir(generatedLicenses)
    doLast {
        val rows = rootProject.file("third_party/components.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val licensed = rows.map { it[0] }.toSet()
        val actual = layout.buildDirectory.file("runtime-components.txt").get().asFile.readLines().filter { it.isNotBlank() }.toSet() + "io.github.libxposed:api:102.0.0"
        check(actual == licensed) { "Review dependency licenses: missing=${actual-licensed}; obsolete=${licensed-actual}" }
        val out = generatedLicenses.get().asFile; out.mkdirs()
        rootProject.file("LICENSE").copyTo(out.resolve("APP_LICENSE.txt"),true)
        val notices = buildString {
            append("Bundled runtime dependencies (including transitive dependencies), plus compile-only Xposed API.\n\n")
            append("AndroidX / Material: The Android Open Source Project and respective contributors.\nKotlin / coroutines / annotations: JetBrains and respective contributors.\nGuava / Error Prone / J2ObjC / JSpecify: Google and respective contributors.\nlibxposed API: libxposed contributors.\n\n")
            for (row in rows) append(row[0]+"\n"+row[1]+"\n"+row.getOrElse(3){""}+"\n\n")
            for (name in rows.map { it[2] }.distinct()) {
                append("\n===== $name =====\n\n")
                append(rootProject.file("third_party/$name").readText())
            }
        }
        out.resolve("THIRD_PARTY_NOTICES.txt").writeText(notices)
    }
}
tasks.named("preBuild").configure { dependsOn(dependencyLicenses) }
