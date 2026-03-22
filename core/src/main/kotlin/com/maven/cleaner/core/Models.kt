package com.maven.cleaner.core

import java.nio.file.Path

data class Artifact(
    val groupId: String,
    val artifactId: String,
    val versions: List<ArtifactVersion>
)

data class ArtifactVersion(
    val version: String,
    val path: Path,
    val size: Long,
    val isSnapshot: Boolean = version.endsWith("-SNAPSHOT"),
    var isLocalOnly: Boolean? = null
)
