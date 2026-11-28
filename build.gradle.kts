// Top-level build file for the Music Player app.
// NOTE: The UI in this project is a functional placeholder. The final UI
// design will be applied later, after the user approves a prototype design.
plugins {
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 2.2.10: AGP 8.9.1 resolves kotlin-stdlib 2.2.10, and the
    // 2.0.21 compiler cannot read its 2.2.0 metadata ("Module was
    // compiled with an incompatible version of Kotlin").
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
