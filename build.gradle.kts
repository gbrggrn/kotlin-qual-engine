plugins {
    kotlin("jvm") version "1.9.22"
    id("application")
    id("org.openjfx.javafxplugin") version "0.0.13"
}

group = "com.qualengine"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/multik/maven")
}

dependencies {
    // --- UI ---
    implementation("org.openjfx:javafx-controls:21")
    implementation("org.openjfx:javafx-fxml:21")
    implementation("org.kordamp.bootstrapfx:bootstrapfx-core:0.4.0")

    // --- DATABASE (The Engine) ---
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // --- AI & MATH ---
    implementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")
    implementation("org.jetbrains.kotlinx:multik-core:0.2.3")

    // --- UTILS ---
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")
}

kotlin {
    jvmToolchain(21)
}

application {
    // MAKE SURE THIS MATCHES YOUR PACKAGE
    mainClass.set("com.qualengine.HelloApplication")
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
}