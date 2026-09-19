plugins {
    id("yuihub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// 全 ABI 配置：无论 debug/release 均只编 arm64-v8a 原生库

android {
    namespace = "me.rerere.workspace"
    // aarch64 容器内只有社区构建的 NDK r29，显式指定以跳过 AGP 默认版本的自动下载
    ndkVersion = "29.0.14206865"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
