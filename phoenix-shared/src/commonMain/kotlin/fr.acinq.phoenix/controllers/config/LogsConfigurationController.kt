package fr.acinq.phoenix.controllers.config

import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.controllers.AppController
import fr.acinq.phoenix.utils.LogMemory
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.getTemporaryDirectoryPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.log.LoggerFactory
import org.kodein.memory.file.*
import org.kodein.memory.use


class AppLogsConfigurationController(
    loggerFactory: LoggerFactory,
    private val ctx: PlatformContext,
    private val logMemory: LogMemory
) : AppController<LogsConfiguration.Model, LogsConfiguration.Intent>(
    loggerFactory = loggerFactory,
    firstModel = LogsConfiguration.Model.Awaiting
) {
    constructor(business: PhoenixBusiness): this(
        loggerFactory = business.loggerFactory,
        ctx = business.ctx,
        logMemory = business.logMemory
    )

    private val numberOfFiles = 3 // Edit for longer files

    override fun process(intent: LogsConfiguration.Intent) {
        when (intent) {
            is LogsConfiguration.Intent.Export -> {
                launch {
                    model(LogsConfiguration.Model.Exporting)
                    logMemory.rotate().join()
                    val file = mergeLogs(logMemory.directory, numberOfFiles, ctx)
                    model(LogsConfiguration.Model.Ready(file.path))
                }
            }
        }
    }

    private suspend fun mergeLogs(
        directory: Path,
        numberOfFiles: Int,
        ctx: PlatformContext
    ): Path = withContext(Dispatchers.Default) {
        val files = directory
            .listDir()
            .sortedByDescending { it.name }
            .take(numberOfFiles)
            .toList()
        val fileName = files.last().name + "_" + files.first().name
        val tmpDir = Path(getTemporaryDirectoryPath(ctx))
        val tmpFile = tmpDir.resolve(fileName)
        tmpFile.openWriteableFile().use { output ->
            files.reversed().forEach { file ->
                file.openReadableFile().use { input ->
                    val buffer = ByteArray(16384)
                    var done = false
                    do {
                        val read = input.receive(dst = buffer)
                        if (read > 0) {
                            output.putBytes(src = buffer, srcOffset = 0, length = read)
                        } else {
                            done = true
                        }
                    } while (!done);
                }
            }
        }
        tmpFile
    }
}
