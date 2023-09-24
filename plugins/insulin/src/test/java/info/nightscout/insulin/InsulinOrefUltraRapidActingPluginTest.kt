package info.nightscout.insulin

import app.aaps.interfaces.configuration.Config
import app.aaps.interfaces.insulin.Insulin
import app.aaps.interfaces.logging.AAPSLogger
import app.aaps.interfaces.profile.ProfileFunction
import app.aaps.interfaces.resources.ResourceHelper
import app.aaps.interfaces.rx.bus.RxBus
import app.aaps.interfaces.ui.UiInteraction
import app.aaps.interfaces.utils.HardLimits
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InsulinOrefUltraRapidActingPluginTest {

    private lateinit var sut: InsulinOrefUltraRapidActingPlugin

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var rxBus: RxBus
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var aapsLogger: AAPSLogger
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    private var injector: HasAndroidInjector = HasAndroidInjector {
        AndroidInjector {
        }
    }

    @BeforeEach
    fun setup() {
        sut = InsulinOrefUltraRapidActingPlugin(injector, rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
    }

    @Test
    fun `simple peak test`() {
        assertThat(sut.peak).isEqualTo(55)
    }

    @Test
    fun getIdTest() {
        assertThat(sut.id).isEqualTo(Insulin.InsulinType.OREF_ULTRA_RAPID_ACTING)
    }

    @Test
    fun commentStandardTextTest() {
        `when`(rh.gs(eq(R.string.ultra_fast_acting_insulin_comment))).thenReturn("Fiasp")
        assertThat(sut.commentStandardText()).isEqualTo("Fiasp")
    }

    @Test
    fun getFriendlyNameTest() {
        `when`(rh.gs(eq(R.string.ultra_rapid_oref))).thenReturn("Ultra-Rapid Oref")
        assertThat(sut.friendlyName).isEqualTo("Ultra-Rapid Oref")
    }

}
