import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust half of the app: this Gradle project is the android/ folder of the `capcraft-android`
// crate (src/crates/capcraft-android), which sits in Concat's cargo workspace (src/) two folders up.
// Build it in the environment Concat's src/scripts/mobile-env.sh describes (FFmpeg and sherpa-onnx
// for the phone, from src/scripts/ffmpeg-mobile.sh and sherpa-mobile.sh).
val crateDir: File = rootDir.parentFile
val cargoWorkspaceDir: File = crateDir.parentFile.parentFile
val rustAbis: List<String> =
    providers.gradleProperty("capcraft.abis").getOrElse("arm64-v8a").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val minApi = 29

// The app's version: gradle.properties' capcraft.version. The version code follows from it
// (0.0.1-beta → 101, 0.0.1-beta.2 → 102, 0.0.1 → 199, 0.1.0-beta → 10001), times ten plus the ABI
// of a one-ABI build (armeabi-v7a 1, arm64-v8a 2, x86_64 3; 0 for several).
val appVersion: String = providers.gradleProperty("capcraft.version").get()
val appVersionCode: Int = run {
    val m = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z-]+?(\.(\d+))?)?""").matchEntire(appVersion)) {
        "capcraft.version $appVersion is not a version like 1.2.3, 1.2.3-beta or 1.2.3-beta.4"
    }
    val (major, minor, patch) = m.groupValues.subList(1, 4).map { it.toInt() }
    val pre = if (m.groupValues[4].isEmpty()) null else m.groupValues[6].toIntOrNull() ?: 1
    require(minor < 100 && patch < 100 && (pre ?: 1) in 1..98) {
        "capcraft.version $appVersion: minor and patch must be under 100, and a pre-release's number 1 to 98"
    }
    val base = ((major * 100 + minor) * 100 + patch) * 100 + (pre ?: 99)
    providers.gradleProperty("capcraft.versionCode").orNull?.let { listed ->
        require(listed.trim().toIntOrNull() == base) {
            "gradle.properties: capcraft.versionCode=$listed, but version $appVersion's is $base (set both before tagging)"
        }
    }
    val abi = when (rustAbis.singleOrNull()) {
        "armeabi-v7a" -> 1
        "arm64-v8a" -> 2
        "x86_64" -> 3
        else -> 0
    }
    base * 10 + abi
}

// The release key, when the build is given one (the release workflow passes it from the
// repository's secrets). Without one a release build is signed with the debug key; with
// -Pcapcraft.unsigned=true (F-Droid's build) it is not signed at all.
val releaseKeystore: String? = providers.environmentVariable("CAPCRAFT_KEYSTORE").orNull
val unsignedRelease: Boolean = providers.gradleProperty("capcraft.unsigned").orNull == "true"

/** rustup's `cargo`, else `cargo` from PATH; `CARGO` overrides. */
val cargoExecutable: String = run {
    val exe = if (System.getProperty("os.name").lowercase().startsWith("windows")) "cargo.exe" else "cargo"
    val rustup = File(System.getProperty("user.home"), ".cargo/bin/$exe")
    providers.environmentVariable("CARGO").orNull ?: if (rustup.isFile) rustup.absolutePath else exe
}

/** Where sherpa-onnx's runtime libraries for arm64 are (src/scripts/sherpa-mobile.sh). */
val sherpaLibDir: String =
    providers.environmentVariable("SHERPA_ONNX_LIB_DIR").orNull
        ?: File(cargoWorkspaceDir, "vendor/sherpa-onnx/jniLibs/arm64-v8a").absolutePath

/** Builds `libcapcraft_android.so` (release profile) for each ABI with cargo-ndk. */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspaceDir: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val runtimeLibs: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val targets = abis.get().flatMap { listOf("-t", it) }
        val out = outputDir.get().asFile.absolutePath
        // Reproducible: the library mustn't hold the paths it was built in.
        val cargoHome = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".cargo").absolutePath
        val remap = listOf(
            "--remap-path-prefix=${File(workspaceDir.get()).absolutePath}=/capcraft",
            "--remap-path-prefix=$cargoHome=/cargo",
        )
        execOperations.exec {
            workingDir(workspaceDir.get())
            environment("ANDROID_NDK_HOME", ndkDir.get())
            environment("CARGO_ENCODED_RUSTFLAGS", remap.joinToString("\u001f"))
            commandLine(
                listOf(cargo.get(), "ndk") + targets +
                    listOf("--platform", apiLevel.get().toString(), "-o", out, "build", "--release", "-p", "capcraft-android", "--lib"),
            )
        }
        // cargo-ndk copies every shared library cargo left behind; the app ships its own library
        // and the runtime libraries sherpa-onnx needs (its C API, ONNX Runtime, libc++).
        val keep = setOf("libcapcraft_android.so")
        File(out).walk().filter { it.isFile && it.name !in keep }.forEach { it.delete() }
        val runtime = File(runtimeLibs.get())
        if (runtime.isDirectory && "arm64-v8a" in abis.get()) {
            runtime.listFiles { f -> f.extension == "so" }.orEmpty().forEach { lib ->
                lib.copyTo(File(out, "arm64-v8a/${lib.name}"), overwrite = true)
            }
        }
    }
}

/** Generates the Kotlin bindings from the library cargo built (UniFFI library mode). */
abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspaceDir: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        execOperations.exec {
            workingDir(workspaceDir.get())
            commandLine(
                cargo.get(), "run", "--quiet", "-p", "capcraft-android-bindgen", "--",
                "generate", library.get().asFile.absolutePath, "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
            )
        }
    }
}

/**
 * Filled icons (Material Symbols, Apache-2.0: the crate's assets/icons-material) as Compose path
 * data: `MaterialIcons.ContentCut` and so on, one per SVG. Each is one or more filled paths on
 * the 960-unit grid, whose y runs from -960 to 0.
 */
abstract class MaterialIconsTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val iconsDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val pkg = File(out, "io/github/artcraftandroid/capcraft/ui").apply { mkdirs() }
        val path = Regex("""<path\b[^>]*\bd="([^"]*)"""")
        val sb = StringBuilder()
        sb.appendLine("// Generated by app/build.gradle.kts (MaterialIconsTask) from assets/icons-material: do not edit.")
        sb.appendLine("package io.github.artcraftandroid.capcraft.ui\n")
        sb.appendLine("import androidx.compose.ui.graphics.vector.ImageVector\n")
        sb.appendLine("/** Material Symbols (Apache-2.0, Google; assets/icons-material/LICENSE-material-symbols.txt). */")
        sb.appendLine("object MaterialIcons {")
        val files = iconsDir.get().asFile.listFiles { f -> f.extension == "svg" }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val paths = path.findAll(f.readText()).map { it.groupValues[1] }.toList()
            if (paths.isEmpty()) continue
            val id = f.nameWithoutExtension.split('_', '-').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
            val list = paths.joinToString(", ") { "\"" + it + "\"" }
            sb.appendLine("    val $id: ImageVector by lazy { material(\"${f.nameWithoutExtension}\", $list) }")
        }
        sb.appendLine("}")
        File(pkg, "MaterialIcons.kt").writeText(sb.toString())
    }
}

/** Files copied into the APK's assets under the names given: the licences the About screen shows. */
abstract class CopyFiles : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val files: ConfigurableFileCollection
    @get:Input abstract val targets: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (file in files) {
            val target = targets.get()[file.name] ?: continue
            file.copyTo(File(out, target), overwrite = true)
        }
    }
}

/**
 * The app's UI icons (Lucide, ISC: the crate's assets/icons) as Compose path data, generated at
 * build time: `LucideIcons.Scissors` and so on, one per SVG. Lucide draws with strokes only, from
 * paths, circles, ellipses, rects, lines and polylines, which become SVG path strings here.
 */
abstract class LucideIconsTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val iconsDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val pkg = File(out, "io/github/artcraftandroid/capcraft/ui").apply { mkdirs() }
        val attr = Regex("""([\w-]+)="([^"]*)"""")
        val element = Regex("""<(path|circle|ellipse|rect|line|polyline|polygon)\b([^>]*)/?>""")
        fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        val sb = StringBuilder()
        sb.appendLine("// Generated by app/build.gradle.kts (LucideIconsTask) from assets/icons: do not edit.")
        sb.appendLine("package io.github.artcraftandroid.capcraft.ui\n")
        sb.appendLine("import androidx.compose.ui.graphics.vector.ImageVector\n")
        sb.appendLine("/** Lucide icons (ISC, Lucide Contributors; assets/icons/LICENSE-lucide.txt). */")
        sb.appendLine("object LucideIcons {")
        val files = iconsDir.get().asFile.listFiles { f -> f.extension == "svg" }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val paths = mutableListOf<String>()
            for (m in element.findAll(f.readText())) {
                val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                fun d(k: String) = a[k]?.toDoubleOrNull() ?: 0.0
                when (m.groupValues[1]) {
                    "path" -> a["d"]?.let(paths::add)
                    "circle", "ellipse" -> {
                        val rx = if (m.groupValues[1] == "circle") d("r") else d("rx")
                        val ry = if (m.groupValues[1] == "circle") d("r") else d("ry")
                        val cx = d("cx"); val cy = d("cy")
                        paths += "M${n(cx - rx)} ${n(cy)}a${n(rx)} ${n(ry)} 0 1 0 ${n(2 * rx)} 0a${n(rx)} ${n(ry)} 0 1 0 ${n(-2 * rx)} 0"
                    }
                    "rect" -> {
                        val x = d("x"); val y = d("y"); val w = d("width"); val h = d("height")
                        val rx = minOf(a["rx"]?.toDoubleOrNull() ?: a["ry"]?.toDoubleOrNull() ?: 0.0, w / 2)
                        val ry = minOf(a["ry"]?.toDoubleOrNull() ?: rx, h / 2)
                        paths += if (rx <= 0.0) "M${n(x)} ${n(y)}h${n(w)}v${n(h)}h${n(-w)}Z" else
                            "M${n(x + rx)} ${n(y)}H${n(x + w - rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w)} ${n(y + ry)}V${n(y + h - ry)}" +
                                "A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w - rx)} ${n(y + h)}H${n(x + rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x)} ${n(y + h - ry)}" +
                                "V${n(y + ry)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + rx)} ${n(y)}Z"
                    }
                    "line" -> paths += "M${n(d("x1"))} ${n(d("y1"))}L${n(d("x2"))} ${n(d("y2"))}"
                    "polyline", "polygon" -> {
                        val pts = (a["points"] ?: "").trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                        if (pts.size >= 4) {
                            val s = StringBuilder("M${pts[0]} ${pts[1]}")
                            for (i in 2 until pts.size - 1 step 2) s.append("L${pts[i]} ${pts[i + 1]}")
                            if (m.groupValues[1] == "polygon") s.append("Z")
                            paths += s.toString()
                        }
                    }
                }
            }
            if (paths.isEmpty()) continue
            val id = f.nameWithoutExtension.split('-').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
            val list = paths.joinToString(", ") { "\"" + it.replace("\"", "") + "\"" }
            sb.appendLine("    val $id: ImageVector by lazy { lucide(\"${f.nameWithoutExtension}\", $list) }")
        }
        sb.appendLine("}")
        File(pkg, "LucideIcons.kt").writeText(sb.toString())
    }
}

android {
    namespace = "io.github.artcraftandroid.capcraft"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.artcraftandroid.capcraft"
        minSdk = minApi
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters.addAll(rustAbis) }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("CAPCRAFT_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("CAPCRAFT_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("CAPCRAFT_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (unsignedRelease) null else signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // reproducible builds: no record of the checkout in the APK
            vcsInfo { include = false }
        }
    }
    // reproducible builds: no dependency metadata signing block (F-Droid's check rejects it)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        // the native libraries stay uncompressed and page-aligned, so they load in place
        jniLibs { useLegacyPackaging = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val cargoBuild = tasks.register<CargoNdkBuild>("cargoBuild$name") {
            group = "rust"
            description = "Builds libcapcraft_android.so for $rustAbis"
            cargo.set(cargoExecutable)
            workspaceDir.set(cargoWorkspaceDir.absolutePath)
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
            abis.set(rustAbis)
            apiLevel.set(minApi)
            runtimeLibs.set(sherpaLibDir)
            // cargo knows what changed
            outputs.upToDateWhen { false }
        }
        val bindgen = tasks.register<UniffiBindgen>("uniffiBindgen$name") {
            group = "rust"
            description = "Generates the Kotlin bindings of libcapcraft_android.so"
            cargo.set(cargoExecutable)
            workspaceDir.set(cargoWorkspaceDir.absolutePath)
            library.set(cargoBuild.flatMap { it.outputDir.file("${rustAbis.first()}/libcapcraft_android.so") })
        }
        val icons = tasks.register<LucideIconsTask>("lucideIcons$name") {
            iconsDir.set(File(crateDir, "assets/icons"))
        }
        val materialIcons = tasks.register<MaterialIconsTask>("materialIcons$name") {
            iconsDir.set(File(crateDir, "assets/icons-material"))
        }
        val repoDir = cargoWorkspaceDir.parentFile
        val licences = tasks.register<CopyFiles>("capcraftLicences$name") {
            files.from(
                File(repoDir, "LICENSE"),
                File(repoDir, "THIRD_PARTY_NOTICES.md"),
                File(crateDir, "NOTICE.md"),
                File(crateDir, "assets/icons/LICENSE-lucide.txt"),
                File(crateDir, "assets/icons-material/LICENSE-material-symbols.txt"),
            )
            targets.set(
                mapOf(
                    "LICENSE" to "licences/LICENSE.txt",
                    "THIRD_PARTY_NOTICES.md" to "licences/THIRD_PARTY_NOTICES.md",
                    "NOTICE.md" to "licences/NOTICE.md",
                    "LICENSE-lucide.txt" to "licences/LICENSE-lucide.txt",
                    "LICENSE-material-symbols.txt" to "licences/LICENSE-material-symbols.txt",
                ),
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, CopyFiles::outputDir)
        variant.sources.java?.addGeneratedSourceDirectory(icons, LucideIconsTask::outputDir)
        variant.sources.java?.addGeneratedSourceDirectory(materialIcons, MaterialIconsTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDir)
        variant.sources.java?.addGeneratedSourceDirectory(bindgen, UniffiBindgen::outputDir)
    }
}

// reproducible builds: no baseline profile in the APK (its bytes vary from one build to the next)
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
}
