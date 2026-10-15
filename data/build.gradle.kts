plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * GitHub OAuth App 的 client_id 由 gradle.properties 注入。
 *
 * Device Flow 不需要 client_secret，所以 client_id 可以直接内置进产物；但仍然不该硬编码在源码里，
 * 换一个 OAuth App 或换一份 fork 时只改配置即可。留空时设置页会提示「未配置」，只能手动填 token。
 */
val githubOAuthClientId: Provider<String> =
    providers.gradleProperty("appmarket.githubOAuth.clientId").orElse("")
val githubOAuthScope: Provider<String> =
    providers.gradleProperty("appmarket.githubOAuth.scope").orElse("read:user")

val generateGithubOAuthConfig = tasks.register("generateGithubOAuthConfig") {
    val outputDir = layout.buildDirectory.dir("generated/githubOAuth/kotlin")
    inputs.property("clientId", githubOAuthClientId)
    inputs.property("scope", githubOAuthScope)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("top/app/market/data/remote/github")
        dir.mkdirs()
        dir.resolve("GithubOAuthConfig.kt").writeText(
            buildString {
                appendLine("// 由 Gradle 生成，请勿手改；改 gradle.properties 里的 appmarket.githubOAuth.*")
                appendLine("package top.app.market.data.remote.github")
                appendLine()
                appendLine("internal object GithubOAuthConfig {")
                appendLine("    /** GitHub OAuth App 的 client_id（Device Flow 不需要 client_secret）。 */")
                appendLine("    const val CLIENT_ID: String = \"${githubOAuthClientId.get()}\"")
                appendLine()
                appendLine("    /** 申请的最小权限：只用于读取公开仓库信息与当前账号登录名。 */")
                appendLine("    const val SCOPE: String = \"${githubOAuthScope.get()}\"")
                appendLine("}")
            }
        )
    }
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)

    android {
        compileSdk { version = release(ProjectConfig.Android.COMPILE_SDK) }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.data"
    }

    jvm("desktop")

    sourceSets {
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain.get())
        }
        commonMain.configure { kotlin.srcDir(generateGithubOAuthConfig) }
        commonMain.dependencies {
            implementation(projects.domain)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        androidMain {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.hiddenapibypass)
                implementation(libs.rikka.shizuku.api)
                implementation(libs.rikka.shizuku.provider)
                implementation(libs.ktor.client.okhttp)
            }
        }
        named("desktopMain") {
            dependsOn(jvmMain)
        }
        named("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
