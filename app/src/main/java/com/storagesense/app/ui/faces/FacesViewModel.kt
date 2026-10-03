package com.storagesense.app.ui.faces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storagesense.app.data.local.room.FaceClusterDao
import com.storagesense.app.ai.face.FaceClusterEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FacesViewModel @Inject constructor(
    private val faceClusterDao: FaceClusterDao
) : ViewModel() {

    private val _clusters = MutableStateFlow<Map<Int, List<FaceClusterEntity>>>(emptyMap())
    val clusters: StateFlow<Map<Int, List<FaceClusterEntity>>> = _clusters.asStateFlow()

    init {
        loadClusters()
    }

    private fun loadClusters() {
        viewModelScope.launch {
            val clusterIds = faceClusterDao.getAllPersonClusterIds()
            val map = mutableMapOf<Int, List<FaceClusterEntity>>()
            
            for (id in clusterIds) {
                val faces = faceClusterDao.getFacesForPerson(id)
                if (faces.isNotEmpty()) {
                    map[id] = faces
                }
            }
            _clusters.value = map
        }
    }
}
