plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "cn.onan.sentinel"
    compileSdk = 35
    defaultConfig {
        // Deliberately isolated: never use the v0.1.0 application ID without migration/signature review.
        applicationId = "cn.onan.sentinel.preview"
        minSdk = 26
        targetSdk = 34
        versionCode = 300
        versionName = "0.3.0-p0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    signingConfigs.getByName("debug") { storeFile = file("${rootProject.projectDir}/.local-signing/debug.keystore") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<Test>().configureEach {
    // Keep Robolectric's download lock/cache in the project, including on restricted build hosts.
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    doFirst { testHome.mkdirs() }
}
