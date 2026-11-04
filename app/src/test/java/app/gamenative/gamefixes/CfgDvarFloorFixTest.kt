package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Test

class CfgDvarFloorFixTest {
    private val floors = mapOf("r_picmip" to 1, "r_picmip_bump" to 1)

    @Test
    fun raisesValuesBelowTheFloor() {
        val cfg = "seta r_picmip \"0\"\nseta r_picmip_bump \"0\"\nseta r_picmip_spec \"0\"\n"
        assertEquals(
            "seta r_picmip \"1\"\nseta r_picmip_bump \"1\"\nseta r_picmip_spec \"0\"\n",
            CfgDvarFloorFix.raiseDvars(cfg, floors),
        )
    }

    @Test
    fun keepsLowerQualityTheUserChose() {
        val cfg = "seta r_picmip \"3\"\r\nseta r_picmip_bump \"2\"\r\n"
        assertEquals(cfg, CfgDvarFloorFix.raiseDvars(cfg, floors))
    }

    @Test
    fun findsConfigWhateverTheFolderCase() {
        val root = java.nio.file.Files.createTempDirectory("bo").toFile()
        val cfg = java.io.File(root, "Players/config.cfg").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(cfg.canonicalPath, CfgDvarFloorFix.resolveIgnoringCase(root, "players/config.cfg")?.canonicalPath)
        assertEquals(null, CfgDvarFloorFix.resolveIgnoringCase(root, "players/config_mp.cfg"))
        root.deleteRecursively()
    }

    @Test
    fun doesNotTouchOtherDvarsWithTheSamePrefix() {
        val cfg = "seta r_picmip_manual \"0\"\nseta r_picmip \"0\"\n"
        assertEquals(
            "seta r_picmip_manual \"0\"\nseta r_picmip \"1\"\n",
            CfgDvarFloorFix.raiseDvars(cfg, mapOf("r_picmip" to 1)),
        )
    }
}
