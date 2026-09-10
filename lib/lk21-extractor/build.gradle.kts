plugins {
    id("lib-android")
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
