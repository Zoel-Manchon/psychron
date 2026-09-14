// AGP 8.13 with Gradle 8.14 and Kotlin 2.2: the newest line whose combination is
// well trodden. AGP 9 folds Kotlin support into the plugin and requires Gradle 9;
// that migration is worth doing, but not as the first build of a new app.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
