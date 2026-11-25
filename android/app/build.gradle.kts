plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.moroshka.notes"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.moroshka.notes"
        testInstrumentationRunner = "app.moroshka.notes.SmokeRunner"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.0-alpha.11"
        resValue("string", "moroshka_app_name", "Морошка")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            resValue("string", "moroshka_app_name", "Морошка Debug")
        }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            isDebuggable = false
            resValue("string", "moroshka_app_name", "Морошка Preview")
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.2")
    implementation("androidx.core:core:1.15.0")
}

val prepareServer by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("../server")) { into("server") }
    from(rootProject.layout.projectDirectory.dir("../web")) { into("web") }
    from(rootProject.layout.projectDirectory.dir("../deploy")) {
        include("install.sh", "compose.yaml", "Caddyfile")
        into("deploy")
    }
    from(rootProject.layout.projectDirectory.file("../Dockerfile"))
    from(rootProject.layout.projectDirectory.file("../.dockerignore"))
    into(layout.buildDirectory.dir("generated/server-assets/server"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/server-assets"))
tasks.named("preBuild").configure { dependsOn(prepareServer) }
