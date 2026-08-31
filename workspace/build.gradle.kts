plugins {
    id("yuihub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// debug 快速验证：只请求 debug 变体时只编 arm64，跳过 x86_64 native 编译
val taskNames = gradle.startParameter.taskNames
val isDebugFastBuild = taskNames.isNotEmpty() &&
    taskNames.all { it.lowercase().contains("debug") } &&
    taskNames.none { it.lowercase().contains("bundle") }

android {
    namespace = "me.rerere.workspace"

    defaultConfig {
        ndk {
            if (isDebugFastBuild) {
                abiFilters += listOf("arm64-v8a")
            } else {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
