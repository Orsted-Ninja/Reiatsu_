package com.storagesense.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storagesense.app.ai.face.FaceClusterEntity

@Dao
interface FaceClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceClusterEntity): Long

    @Query("SELECT * FROM face_clusters")
    suspend fun getAllFaces(): List<FaceClusterEntity>

    @Query("UPDATE face_clusters SET personClusterId = :clusterId WHERE id = :id")
    suspend fun updateClusterId(id: Long, clusterId: Int)

    @Query("SELECT DISTINCT personClusterId FROM face_clusters WHERE personClusterId != -1 ORDER BY personClusterId ASC")
    suspend fun getAllPersonClusterIds(): List<Int>

    @Query("SELECT * FROM face_clusters WHERE personClusterId = :clusterId")
    suspend fun getFacesForPerson(clusterId: Int): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE personClusterId = -1")
    suspend fun getUnclusteredFaces(): List<FaceClusterEntity>

    @Query("SELECT COUNT(*) FROM face_clusters")
    suspend fun getTotalFacesCount(): Int

    @Query("SELECT COUNT(*) FROM face_clusters WHERE personClusterId != -1")
    suspend fun getClusteredFacesCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM face_clusters WHERE imagePath = :path LIMIT 1)")
    suspend fun hasFaceForPath(path: String): Boolean

    @Query("UPDATE face_clusters SET personName = :name WHERE personClusterId = :clusterId")
    suspend fun updatePersonName(clusterId: Int, name: String)

    @Query("UPDATE face_clusters SET thumbnailPath = :thumbnailPath WHERE id = :id")
    suspend fun updateThumbnailPath(id: Long, thumbnailPath: String)

    @Query("SELECT * FROM face_clusters WHERE personName LIKE '%' || :nameQuery || '%'")
    suspend fun searchFacesByPersonName(nameQuery: String): List<FaceClusterEntity>

    @Query("DELETE FROM face_clusters")
    suspend fun clearAll()
}
