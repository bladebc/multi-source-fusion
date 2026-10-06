package com.example.multisensorlogger.pdr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 对拍：把仓库 data/ 里 App 录的 ZIP 原样回放给在线引擎，逐步对比离线 Python 管线的结果。
 * 基准由 pdr/export_app_reference.py 生成（参数同 PdrConfig 默认值）；改了 pdr.py 的默认管线后要重新导出。
 */
class PdrEngineParityTest {
    @Test
    fun libraryWalkMatchesPython() = checkParity("session_20261006_081737_496_fe4b5910.zip", "library_walk_reference.json")

    @Test
    fun sampleSessionMatchesPython() = checkParity("session_20261006_075107_474_1c743e9b.zip", "sample_session_reference.json")

    private fun checkParity(zipName: String, referenceName: String) {
        val ref = JSONObject(javaClass.classLoader!!.getResource(referenceName)!!.readText())
        val engine = ZipFile(File(repoData(), zipName)).use { z ->
            replayRecording(PdrConfig()) { name ->
                z.entries().asSequence().firstOrNull { it.name.endsWith(name) }?.let { z.getInputStream(it) }
            }
        }

        assertEquals("初始航向", ref.getDouble("psi0_deg"), engine.psi0!!, 0.05)
        val expected = ref.getJSONArray("steps")
        assertEquals("步数", expected.length(), engine.steps.size)
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val a = engine.steps[i]
            assertEquals("第 ${i + 1} 步时刻", e.getDouble("t"), a.timeS, 0.011)
            assertEquals("第 ${i + 1} 步步长", e.getDouble("length"), a.length, 1e-3)
            assertEquals("第 ${i + 1} 步航向", e.getDouble("psi"), a.headingDeg, 0.05)
            assertEquals("第 ${i + 1} 步 x", e.getDouble("x"), a.x, 0.02)
            assertEquals("第 ${i + 1} 步 y", e.getDouble("y"), a.y, 0.02)
        }
    }

    companion object {
        /** 单元测试工作目录是 app/，数据在仓库根的 data/。 */
        fun repoData(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "data") }
            .first { it.isDirectory && it.listFiles()!!.any { f -> f.name.endsWith(".zip") } }
    }
}
