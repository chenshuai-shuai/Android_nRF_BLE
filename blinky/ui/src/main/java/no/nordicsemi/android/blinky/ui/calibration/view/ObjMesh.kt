package no.nordicsemi.android.blinky.ui.calibration.view

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.Vec3
import kotlin.math.abs
import kotlin.math.max

internal data class MeshTriangle(
    val a: Int,
    val b: Int,
    val c: Int,
)

internal data class ObjMesh(
    val vertices: List<Vec3>,
    val triangles: List<MeshTriangle>,
)

internal object ObjMeshLoader {
    suspend fun load(context: Context, assetPath: String): ObjMesh = withContext(Dispatchers.IO) {
        val sourceVertices = ArrayList<Vec3>(8192)
        val triangles = ArrayList<MeshTriangle>(4096)

        context.assets.open(assetPath).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("v ") -> {
                        val parts = line.trim().split(WHITESPACE)
                        if (parts.size >= 4) {
                            sourceVertices += Vec3(
                                x = parts[1].toFloat(),
                                y = parts[2].toFloat(),
                                z = parts[3].toFloat(),
                            )
                        }
                    }

                    line.startsWith("f ") -> {
                        val rawIndices = line.trim().split(WHITESPACE)
                            .drop(1)
                            .mapNotNull { token ->
                                val vertexToken = token.substringBefore('/').trim()
                                vertexToken.toIntOrNull()?.minus(1)
                            }
                        if (rawIndices.size >= 3) {
                            val root = rawIndices.first()
                            for (i in 1 until rawIndices.lastIndex) {
                                triangles += MeshTriangle(
                                    a = root,
                                    b = rawIndices[i],
                                    c = rawIndices[i + 1],
                                )
                            }
                        }
                    }
                }
            }
        }

        normalizeMesh(sourceVertices, triangles)
    }

    private fun normalizeMesh(
        sourceVertices: List<Vec3>,
        triangles: List<MeshTriangle>,
    ): ObjMesh {
        if (sourceVertices.isEmpty()) {
            return ObjMesh(emptyList(), emptyList())
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        sourceVertices.forEach { vertex ->
            minX = minOf(minX, vertex.x)
            minY = minOf(minY, vertex.y)
            minZ = minOf(minZ, vertex.z)
            maxX = maxOf(maxX, vertex.x)
            maxY = maxOf(maxY, vertex.y)
            maxZ = maxOf(maxZ, vertex.z)
        }

        val center = Vec3(
            x = (minX + maxX) * 0.5f,
            y = (minY + maxY) * 0.5f,
            z = (minZ + maxZ) * 0.5f,
        )
        val scale = max(
            1e-3f,
            max(abs(maxX - minX), max(abs(maxY - minY), abs(maxZ - minZ))),
        )

        val normalizedVertices = sourceVertices.map { vertex ->
            Vec3(
                x = (vertex.x - center.x) / scale,
                y = (vertex.y - center.y) / scale,
                z = (vertex.z - center.z) / scale,
            )
        }

        return ObjMesh(
            vertices = normalizedVertices,
            triangles = triangles,
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
