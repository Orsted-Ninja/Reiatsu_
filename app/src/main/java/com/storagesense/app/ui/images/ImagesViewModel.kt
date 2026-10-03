package com.storagesense.app.ui.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.ai.vision.ImageAnalyzer
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject

@HiltViewModel
class ImagesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val imageAnalyzer: ImageAnalyzer
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
    }

    fun loadImages() {
        viewModelScope.launch {
            val allFiles = withContext(Dispatchers.IO) {
                fileRepository.getAllFiles()
            }
            val imageFiles = withContext(Dispatchers.Default) {
                allFiles.filter { it.category.name.startsWith("IMAGE") }
                    .sortedByDescending { it.lastModifiedEpochMs }
            }
            cachedAllImages = imageFiles
            filterAndDisplay(_selectedCategory.value)

            // Auto-trigger background classification on newest images
            startBackgroundClassification(imageFiles)
        }
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
                .take(300) // Focus on the 300 newest user images first
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

                    if (updatedCount % 3 == 0) {
                        filterAndDisplay(_selectedCategory.value)
                    }
                }
                yield()
            }

            if (updatedCount % 3 != 0) {
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
                file.hasFaces || matchesCategory(file.imageLabels, peopleKeywords)
            }
            "Screenshot" -> allFiles.filter { file ->
                file.category.name == "IMAGE_SCREENSHOT" ||
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
                matchesCategory(file.imageLabels, petKeywords)
            }
            "Food" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, foodKeywords, foodPhrases)
            }
            "Vehicle" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, vehicleKeywords, vehiclePhrases)
            }
            "Nature" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, natureKeywords, naturePhrases)
            }
            "Text" -> allFiles.filter { file ->
                matchesCategory(file.imageLabels, textKeywords)
            }
            else -> allFiles
        }
    }
}
