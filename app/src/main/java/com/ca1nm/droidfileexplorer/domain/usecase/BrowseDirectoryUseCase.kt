package com.ca1nm.droidfileexplorer.domain.usecase

import com.ca1nm.droidfileexplorer.domain.model.FileItem
import com.ca1nm.droidfileexplorer.domain.repository.FileRepository
import javax.inject.Inject

class BrowseDirectoryUseCase @Inject constructor(
    private val repository: FileRepository
) {
    suspend operator fun invoke(path: String, countChildren: Boolean = true): Result<List<FileItem>> =
        repository.listDirectory(path, countChildren)
}
