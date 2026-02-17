package com.fontinstaller.blu

import android.os.IBinder
import kotlin.system.exitProcess

class UserService : IUserService.Stub() {
    
    override fun destroy() {
        exitProcess(0)
    }
    
    override fun exit() {
        destroy()
    }
    
    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotEmpty()) "ERROR: $error" else output
        } catch (e: Exception) {
            "EXCEPTION: ${e.message}"
        }
    }
}
