package com.tharunbirla.librecuts.repository

import android.content.Context
import android.net.Uri
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.VideoProject
import com.tharunbirla.librecuts.utils.ProjectSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

interface ProjectRepository {
    suspend fun loadRecipe(uri: Uri): EditRecipe?
    suspend fun saveRecipe(uri: Uri, recipe: EditRecipe): Boolean
    suspend fun loadProject(uri: Uri): VideoProject?
    suspend fun saveProject(uri: Uri, project: VideoProject): Boolean
}

class ProjectRepositoryImpl(
    private val context: Context
) : ProjectRepository {

    override suspend fun loadRecipe(uri: Uri): EditRecipe? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { reader ->
                    ProjectSerializer.deserialize(reader)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveRecipe(uri: Uri, recipe: EditRecipe): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = ProjectSerializer.serialize(recipe)
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                outputStream.write(json.toByteArray())
            } ?: throw IOException("Could not open output stream for $uri")
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadProject(uri: Uri): VideoProject? = withContext(Dispatchers.IO) {
        loadRecipe(uri)?.toVideoProject()
    }

    override suspend fun saveProject(uri: Uri, project: VideoProject): Boolean = withContext(Dispatchers.IO) {
        try {
            val recipe = EditRecipe.fromVideoProject(project.sourceName, project)
            saveRecipe(uri, recipe)
        } catch (e: Exception) {
            false
        }
    }
}
