dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Increase this integer whenever a new plugin build is published.
version = 10

cloudstream {
    description = "Catálogo y fichas informativas de Doramasia"
    status = 1
    tvTypes = listOf("TvSeries")
    requiresResources = true
    language = "es"
    iconUrl = "https://doramasia.net/wp-content/uploads/2026/11/favicon-doramas.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
