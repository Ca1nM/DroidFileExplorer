package com.ca1nm.droidfileexplorer.domain.usecase

import com.ca1nm.droidfileexplorer.domain.model.OperationProgress
import com.ca1nm.droidfileexplorer.domain.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MoveFilesUseCase @Inject constructor(
    private val repository: FileRepository
) {
    operator fun invoke(sources: List<String>, destination: String): Flow<OperationProgress> =
        repository.moveFiles(sources, destination)
}
