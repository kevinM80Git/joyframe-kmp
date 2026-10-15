import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The phone controller page for PhonePads: Joyframe's PhonePadController (its real TouchStick) in
// Kotlin/Wasm. Apps bundle build/dist/wasmJs/productionExecutable (see docs/quickstart.md, "Phones as controllers").
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "phonepad.js" } }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies { implementation(project(":joyframe")) }
    }
}
