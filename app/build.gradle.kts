plugins { id("com.android.application"); kotlin("android") }
android {
    namespace="com.palmpay.app"
    compileSdk=35
    defaultConfig {
        applicationId="com.palmpay.app"
        minSdk=23
        targetSdk=35
        versionCode=1
        versionName="1.0"
    }
}
