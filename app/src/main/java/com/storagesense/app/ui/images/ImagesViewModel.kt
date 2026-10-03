package com.storagesense.app.ui.images

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.ai.vision.ImageAnalyzer
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.indexing.ScanPhase
import com.storagesense.app.indexing.StorageIndexManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ImagesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val imageAnalyzer: ImageAnalyzer,
    private val storageIndexManager: StorageIndexManager
) : ViewModel() {

    private val _images = MutableStateFlow<List<FileItem>>(emptyList())
    val images: StateFlow<List<FileItem>> = _images.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isClassifying = MutableStateFlow(false)
    val isClassifying: StateFlow<Boolean> = _isClassifying.asStateFlow()

    @Volatile
    private var cachedAllImages: List<FileItem> = emptyList()

    private var classificationJob: Job? = null

    init {
        loadImages()

        // Reactively reload whenever storage index manager completes or updates
        viewModelScope.launch {
            storageIndexManager.progress.collect { prog ->
                if (!prog.isRunning && prog.phase == ScanPhase.COMPLETED) {
                    loadImages()
                }
            }
        }
    }

    fun loadImages() {
        viewModelScope.launch {
            val imageFiles = withContext(Dispatchers.IO) {
                val dbAll = fileRepository.getAllFiles()
                val dbImages = dbAll.filter { it.category == FileCategory.IMAGE_PHOTO || it.category == FileCategory.IMAGE_SCREENSHOT }
                if (dbImages.isNotEmpty()) {
                    dbImages.sortedByDescending { it.lastModifiedEpochMs }
                } else {
                    // Instant fallback: load directly from MediaStore so Images tab is NEVER empty
                    queryMediaStoreImages()
                }
            }

            cachedAllImages = imageFiles
            filterAndDisplay(_selectedCategory.value)

            // Auto-trigger background classification on newest unclassified images
            startBackgroundClassification(imageFiles)
        }
    }

    private fun queryMediaStoreImages(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext() && list.size < 1500) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null
                    if (path.isNullOrBlank()) continue
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) continue

                    val name = if (nameCol != -1) cursor.getString(nameCol) ?: file.name else file.name
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else file.length()
                    val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else file.lastModified()
                    val ext = file.extension.lowercase()
                    val isScreenshot = name.contains("screenshot", ignoreCase = true) || path.contains("screenshot", ignoreCase = true)

                    list.add(
                        FileItem(
                            path = path,
                            name = name,
                            extension = ext,
                            sizeBytes = size,
                            lastModifiedEpochMs = date,
                            category = if (isScreenshot) FileCategory.IMAGE_SCREENSHOT else FileCategory.IMAGE_PHOTO
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        filterAndDisplay(category)
    }

    private fun filterAndDisplay(category: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val filtered = filterImages(cachedAllImages, category)
            _images.value = filtered
        }
    }

    private fun startBackgroundClassification(images: List<FileItem>) {
        classificationJob?.cancel()
        classificationJob = viewModelScope.launch(Dispatchers.Default) {
            val toClassify = images
                .take(150) // Fast batch of 150 newest user photos
                .filter { it.imageLabels.isEmpty() && !it.hasFaces }

            if (toClassify.isEmpty()) {
                _isClassifying.value = false
                return@launch
            }

            _isClassifying.value = true
            var updatedCount = 0

            for (item in toClassify) {
                if (!isActive) break

                val vision = imageAnalyzer.analyzeImage(item.path)
                if (vision.labels.isNotEmpty() || vision.hasFaces) {
                    val updated = item.copy(
                        imageLabels = vision.labels,
                        hasFaces = vision.hasFaces
                    )
                    withContext(Dispatchers.IO) {
                        fileRepository.insertOrUpdate(updated)
                    }

                    // Update memory cache
                    cachedAllImages = cachedAllImages.map {
                        if (it.path == updated.path) updated else it
                    }
                    updatedCount++

                    if (updatedCount % 4 == 0) {
                        filterAndDisplay(_selectedCategory.value)
                    }
                }
                yield()
            }

            if (updatedCount % 4 != 0) {
                filterAndDisplay(_selectedCategory.value)
            }
            _isClassifying.value = false
        }
    }

    private val peopleKeywords = setOf(
        "person", "human", "people", "man", "men", "woman", "women", "child", "children",
        "boy", "boys", "girl", "girls", "face", "selfie", "portrait", "smile", "smiling",
        "crowd", "player", "players", "model", "baby", "toddler", "bride", "groom", "lady", "gentleman"
    )

    private val petKeywords = setOf(
        "pet", "pets", "dog", "dogs", "cat", "cats", "puppy", "kitten", "animal", "animals",
        "bird", "birds", "carnivore", "canidae", "felidae", "mammal", "mammals", "fauna",
        "whiskers", "snout", "tail", "fish", "hound", "terrier", "horse", "equine", "reptile"
    )

    private val foodKeywords = setOf(
        "food", "dish", "cuisine", "meal", "snack", "dessert", "breakfast", "lunch", "dinner",
        "fruit", "vegetable", "produce", "pizza", "burger", "sandwich", "salad", "bread",
        "cake", "curry", "rice", "meat", "chicken", "beef", "pork", "seafood", "drink",
        "coffee", "tea", "beverage", "soup", "noodle", "noodles", "pasta", "biryani",
        "pastry", "baking", "bakery", "sweetness", "delicacy", "ingredient", "recipe"
    )

    private val foodPhrases = setOf(
        "fast food", "junk food", "baked goods"
    )

    private val vehicleKeywords = setOf(
        "vehicle", "vehicles", "car", "cars", "automobile", "automobiles", "automotive",
        "truck", "trucks", "bus", "buses", "van", "vans", "motorcycle", "motorcycles",
        "scooter", "scooters", "bicycle", "bicycles", "bike", "bikes", "train", "trains",
        "airplane", "aircraft", "aeroplane", "boat", "boats", "ship", "ships", "aviation",
        "tire", "tires", "tyre", "tyres"
    )

    private val vehiclePhrases = setOf(
        "motor vehicle", "mode of transport", "auto part"
    )

    private val natureKeywords = setOf(
        "nature", "landscape", "tree", "trees", "plant", "plants", "flora", "flower",
        "flowers", "grass", "forest", "forests", "mountain", "mountains", "sky", "cloud",
        "clouds", "water", "river", "rivers", "lake", "lakes", "sea", "ocean", "beach",
        "beaches", "sunset", "sunrise", "sunlight", "wilderness", "outdoor", "leaf",
        "leaves", "twig", "branch", "branches", "rock", "rocks", "scenery", "field", "garden"
    )

    private val naturePhrases = setOf(
        "natural landscape"
    )

    private val textKeywords = setOf(
        "text", "document", "documents", "font", "paper", "newspaper", "book", "books",
        "receipt", "receipts", "invoice", "invoices", "handwriting", "poster", "posters",
        "publication", "whiteboard", "signage", "diagram", "number", "presentation",
        "ticket", "tickets", "bill", "bills", "letter", "note", "blackboard", "writing",
        "flyer", "brochure", "calendar"
    )

    private fun matchesCategory(
        labels: List<String>,
        keywords: Set<String>,
        phrases: Set<String> = emptySet()
    ): Boolean {
        for (label in labels) {
            val lower = label.lowercase().trim()
            if (phrases.any { lower.contains(it) }) return true
            val tokens = lower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
            if (tokens.any { it in keywords }) return true
        }
        return false
    }

    private fun filterImages(allFiles: List<FileItem>, category: String): List<FileItem> {
        return when (category) {
            "All" -> allFiles
            "People" -> allFiles.filter { file ->
                file.hasFaces || matchesCategory(file.imageLabels, peopleKeywords) ||
                file.name.contains("selfie", ignoreCase = true) ||
                file.name.contains("portrait", ignoreCase = true) ||
                file.path.contains("selfie", ignoreCase = true) ||
                file.path.contains("portrait", ignoreCase = true)
            }
            "Screenshot" -> allFiles.filter { file ->
                file.category == FileCategory.IMAGE_SCREENSHOT ||
                file.path.contains("screenshot", ignoreCase = true) ||
                file.name.contains("screenshot", ignoreCase = true) ||
                file.imageLabels.any { label ->
                    val lower = label.lowercase().trim()
                    lower.contains("screenshot") || lower.contains("software") ||
                    lower.contains("web page") || lower.contains("operating system") ||
                    lower.contains("display device") || lower.contains("user interface")
                }
            }
            "Pet" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, petKeywords) ||
                file.name.contains("dog", ignoreCase = true) ||
                file.name.contains("cat", ignoreCase = true) ||
                file.name.contains("pet", ignoreCase = true)
            }
            "Food" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, foodKeywords, foodPhrases) ||
                file.name.contains("food", ignoreCase = true) ||
                file.name.contains("recipe", ignoreCase = true)
            }
            "Vehicle" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, vehicleKeywords, vehiclePhrases) ||
                file.name.contains("car", ignoreCase = true) ||
                file.name.contains("bike", ignoreCase = true)
            }
            "Nature" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, natureKeywords, naturePhrases)
            }
            "Text" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, textKeywords) ||
                file.name.contains("doc", ignoreCase = true) ||
                file.name.contains("receipt", ignoreCase = true) ||
                file.name.contains("invoice", ignoreCase = true) ||
                file.name.contains("bill", ignoreCase = true) ||
                file.name.contains("scan", ignoreCase = true) ||
                file.name.contains("note", ignoreCase = true) ||
                file.name.contains("aadhar", ignoreCase = true) ||
                file.name.contains("pan", ignoreCase = true)
            }
            else -> allFiles
        }
    }
}
