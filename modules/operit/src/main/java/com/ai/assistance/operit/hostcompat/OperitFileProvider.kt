package com.ai.assistance.operit.hostcompat

import androidx.core.content.FileProvider

/**
 * Identity subclass so the module's FileProvider can merge independently of the host's own
 * `androidx.core.content.FileProvider` declaration (the manifest merger keys providers by
 * class name). Authority: "${packageName}.operit.fileprovider" (@xml/file_paths).
 */
class OperitFileProvider : FileProvider()
