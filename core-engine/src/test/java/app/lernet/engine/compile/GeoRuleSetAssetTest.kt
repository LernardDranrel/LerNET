package app.lernet.engine.compile

import app.lernet.routing.GeoRuleSets
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class GeoRuleSetAssetTest {
    @Test
    fun bundledFilesAreOfficialSrs() {
        val dir = File("src/main/assets/rule-set")
        val actual = dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        val expected = GeoRuleSets.bundled.map(GeoRuleSets::fileName).sorted()
        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
        expected.forEach { name ->
            val magic = File(dir, name).inputStream().use { input -> ByteArray(3).also { input.read(it) } }
            assertThat(magic.decodeToString()).isEqualTo("SRS")
        }
    }
}
