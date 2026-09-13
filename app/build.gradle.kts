plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.sensorlaunchguard"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.sensorlaunchguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            // 保留依赖库的许可证/声明文件：Apache-2.0 要求再分发时附带许可证与声明。
            // 多个依赖会带同名文件，用 pickFirst 取其一而不是整体排除。
            pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf(
                // 这两个是构建工具产生的重复副本，非许可证正文。
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // 本模块通过反射访问隐藏 API（SystemSensorManager / ActivityThread）实现钩子，
        // 这是 Xposed 模块的固有做法，无法规避；版本提示类检查也不作为构建门槛。
        // ObsoleteSdkInt：自适应图标必须放在 mipmap-anydpi-v26（anydpi 单独使用无法通过资源链接）。
        disable += setOf(
            "PrivateApi",
            "OldTargetApi",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ObsoleteSdkInt",
        )
        warningsAsErrors = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.14.0")
}
