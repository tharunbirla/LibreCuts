package com.tharunbirla.librecuts.services

import android.content.Context
import android.net.Uri
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.VideoProject
import com.tharunbirla.librecuts.utils.ProjectSerializer
import java.io.File
import java.io.IOException

class ProjectRepository(private val context: Context) {

    fun saveProject(project: VideoProject, uri: Uri): Result<Unit> = runCatching {
        val recipe = EditRecipe.fromVideoProject(project.sourceName, project)
        val json = ProjectSerializer.serialize(recipe)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toByteArray())
        } ?: throw IOException("Could not open output stream for $uri")
    }

    fun loadProject(uri: Uri): Result<VideoProject> = runCatching {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open input stream for $uri")

        inputStream.bufferedReader().use { reader ->
            val recipe = ProjectSerializer.deserialize(reader)
            recipe.toVideoProject()
        }
    }

    fun saveProject(project: VideoProject, file: File): Result<Unit> = runCatching {
        if (!file.parentFile?.exists()!!) {
            file.parentFile?.mkdirs()
        }

        val recipe = EditRecipe.fromVideoProject(project.sourceName, project)
        val json = ProjectSerializer.serialize(recipe)
        file.outputStream().use { outputStream ->
            outputStream.write(json.toByteArray())
        }
    }

    fun loadProject(file: File): Result<VideoProject> = runCatching {
        if (!file.exists()) {
            throw IOException("Project file does not exist: ${file.absolutePath}")
        }

        file.bufferedReader().use { reader ->
            val recipe = ProjectSerializer.deserialize(reader)
            recipe.toVideoProject()
        }
    }
}
