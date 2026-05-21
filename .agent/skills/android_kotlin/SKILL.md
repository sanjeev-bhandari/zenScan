---
name: android_kotlin
description: Core Android & Kotlin Systems Development practices including ViewBinding, Activity Result Contracts, and lifecycleScope.
---

# Core Android & Kotlin Systems Development Skill

This skill documents the foundational Android Jetpack and Kotlin development practices used in **ZenScan**. It covers standard Jetpack APIs, Activity Result Contracts, ViewBinding, and Lifecycle coordination.

---

## 1. Kotlin Development Standards

* **Expressive & Concise Syntax**: Utilize Kotlin-specific functions to manage local resources and clean initialization properties:
  * Use `.apply { ... }` for setting up intents and properties on newly instantiated objects.
  * Use `.also { ... }` or `.let { ... }` for mapping values or handling optional variables.
  * Use `.use { ... }` on Closable streams (e.g., `InputStream`, `OutputStream`) to ensure resources are automatically closed under all execution paths.
* **Property Access**: Ensure components use modern property syntax rather than getter/setter methods.

---

## 2. Jetpack ViewBinding Architecture

ViewBinding is used for all Activity and Fragment layouts in ZenScan. It replaces `findViewById` to provide type-safe and null-safe view references.

### Implementation Pattern in Activities
```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupClickListeners()
    }
}
```

### Absolute Rules
1. **Never call `findViewById`** directly. Use `binding.<view_id>` instead.
2. **Resource Naming**: Ensure all XML view IDs are defined in snake_case (e.g. `btn_gallery`, `image_preview`). ViewBinding will automatically camel-case them in Kotlin (e.g. `binding.btnGallery`, `binding.imagePreview`).
3. **Leak Prevention**: If implementing ViewBinding in a Fragment, always clear the backing binding reference in `onDestroyView()` to prevent severe garbage collector memory leaks.

---

## 3. Activity Result Contracts & Photo Picker

ZenScan integrates the modern Jetpack Activity Result API for securely fetching user images without requesting broad storage permissions.

### Implementation Pattern
```kotlin
// Register the visual media picker launcher at activity start
private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
        // Navigate to preview with the selected URI
        val intent = Intent(this, PreviewActivity::class.java).apply {
            data = uri
        }
        startActivity(intent)
    } else {
        Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
    }
}

// Launching the Picker on Action Click
binding.btnGallery.setOnClickListener {
    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}
```

---

## 4. Lifecycle-Aware Scopes

To keep background processing perfectly aligned with Android Activity cycles, use `lifecycleScope` provided by the `androidx.lifecycle:lifecycle-runtime-ktx` library.

* **Main Thread Launches**: Avoid blocking the main thread. Run visual rendering or storage reads within Coroutine contexts inside the lifecycle scope.
* **Auto-Cancellation**: Tasks launched in `lifecycleScope` are automatically cancelled when the Activity is destroyed, avoiding memory leaks and execution crashes.

---

## 5. Visual Reference Locations
* Dashboard Coordinator: [MainActivity.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/MainActivity.kt)
* App Layout Main: [activity_main.xml](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/res/layout/activity_main.xml)
