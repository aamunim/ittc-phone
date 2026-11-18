// 🎓 ITTC Phone: Fossify Commons 6.1.0 (GPL-3.0, https://github.com/FossifyOrg/Commons), kept here with
// small ITTC changes (see ITTC_CHANGES.md) so ITTC Phone can have its own app id on Google Play.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(clibs.plugins.library)
    alias(clibs.plugins.kotlinAndroid)
    alias(clibs.plugins.ksp)
    alias(clibs.plugins.kotlinSerialization)
    alias(clibs.plugins.compose.compiler)
    alias(clibs.plugins.parcelize)
}


android {
    namespace = "org.fossify.commons"

    compileSdk = clibs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        minSdk = clibs.versions.app.build.minimumSDK.get().toInt()
        vectorDrawables.useSupportLibrary = true
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(clibs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.clibs.versions.app.build.kotlinJVMTarget.get())
        )
        compilerOptions.freeCompilerArgs.set(
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi",
                "-Xcontext-receivers"
            )
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }
}

dependencies {
    implementation(clibs.kotlinx.serialization.json)
    api(clibs.kotlin.immutable.collections)
    implementation(clibs.androidx.constraintlayout)
    implementation(clibs.androidx.documentfile)
    implementation(clibs.androidx.swiperefreshlayout)
    implementation(clibs.androidx.exifinterface)
    implementation(clibs.androidx.biometric.ktx)
    implementation(clibs.androidx.lifecycle.process)
    implementation(clibs.ez.vcard)

    implementation(clibs.bundles.lifecycle)
    implementation(clibs.bundles.compose)
    implementation(clibs.compose.view.binding)
    debugImplementation(clibs.bundles.compose.preview)

    api(clibs.joda.time)
    api(clibs.recyclerView.fastScroller)
    api(clibs.reprint)
    api(clibs.rtl.viewpager)
    api(clibs.patternLockView)
    api(clibs.androidx.core.ktx)
    api(clibs.androidx.appcompat)
    api(clibs.material)
    api(clibs.gson)

    implementation(clibs.glide.compose)
    api(clibs.glide)
    ksp(clibs.glide.compiler)

    api(clibs.bundles.room)
    ksp(clibs.androidx.room.compiler)
}
