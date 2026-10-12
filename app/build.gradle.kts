// ShaderVulkan - Gradle build para selector de shaders tipo RetroArch
import java.util.Properties

plugins {
    id("com.android.application")
}

// T95: direcciones que salen en "Acerca de". Vacías = el botón no aparece. La del código fuente la exige la GPL al distribuir el APK
// (ponerla cuando el repositorio sea público); la de apoyo (Ko-fi, Patreon...) solo la lleva el sabor `github`: Play no admite cobros
// fuera de su sistema de facturación.
val sourceCodeUrl = ""
val supportUrl = ""

// T95: firma de release. Si existe keystore.properties (fuera del repositorio) con storeFile, storePassword, keyAlias y keyPassword,
// la release sale firmada con esa clave: la del APK de github y la clave de subida a Play. Sin el fichero, sale sin firmar como antes.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sanabria.shadervulkan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sanabria.shadervulkan"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // T95: mismo código y mismo identificador de aplicación en los dos canales; solo cambia lo que exige cada uno.
    // github: APK que se instala a mano (hay que permitir los ajustes restringidos para activar Accesibilidad) y enlace de apoyo.
    // play: sin enlaces de pago externos. Se firman con claves distintas, así que uno no actualiza al otro (desinstalar para cambiar).
    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("boolean", "SIDELOAD", "true")
            buildConfigField("String", "SUPPORT_URL", "\"$supportUrl\"")
        }
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SIDELOAD", "false")
            buildConfigField("String", "SUPPORT_URL", "\"\"")
        }
    }
    defaultConfig.buildConfigField("String", "SOURCE_CODE_URL", "\"$sourceCodeUrl\"")

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Símbolos de las bibliotecas nativas para los informes de fallos de Play Console
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // T90: R8 (reduce y ofusca el código Kotlin; quita los Log.v/d/i, T91). Lo que el motor llama por nombre desde C++ se
            // conserva con las reglas de src/main/keepRules/rules.keep
            optimization {
                enable = true
            }
        }
        // R19: para medir rendimiento. Como la release (no depurable: ART la ejecuta como en un usuario) pero firmada con la clave de
        // debug, así se instala desde Android Studio (Build Variants -> profile). No cambia el código.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }

    sourceSets.getByName("main") {
        assets.srcDirs("src/main/assets")
    }
    // La documentación del proyecto (src/main/cache) solo viaja en los APK de debug: en una release no tiene que entrar (T92)
    sourceSets.getByName("debug") {
        assets.srcDirs("src/main/cache")
    }

    // src/main/resources/masters (copias de RetroArch y slang-shaders, solo de consulta, fuera del repositorio) no es del programa:
    // no debe entrar en ningun APK (T92)
    packaging {
        resources {
            excludes += "masters/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    dependencies {
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("androidx.recyclerview:recyclerview:1.3.2")
        implementation("androidx.cardview:cardview:1.0.0")
        implementation("androidx.constraintlayout:constraintlayout:2.2.0")
        implementation("com.google.android.material:material:1.12.0")
        // T90: ComponentActivity, enableEdgeToEdge, OnBackPressedCallback y ActivityResultContracts (antes llegaban por activity-compose)
        implementation("androidx.activity:activity-ktx:1.9.3")
        implementation("androidx.documentfile:documentfile:1.0.1")
        testImplementation("junit:junit:4.13.2")
    }
}