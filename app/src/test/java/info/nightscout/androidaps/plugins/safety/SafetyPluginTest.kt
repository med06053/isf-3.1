package info.nightscout.androidaps.plugins.safety

import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.HardLimitsMock
import info.nightscout.androidaps.TestBaseWithProfile
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatusProvider
import info.nightscout.database.impl.AppRepository
import info.nightscout.interfaces.Constants
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.plugin.ActivePlugin
import info.nightscout.interfaces.plugin.PluginType
import info.nightscout.interfaces.profiling.Profiler
import info.nightscout.interfaces.pump.defs.PumpDescription
import info.nightscout.interfaces.utils.HardLimits
import info.nightscout.plugins.R
import info.nightscout.plugins.aps.openAPSAMA.OpenAPSAMAPlugin
import info.nightscout.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import info.nightscout.plugins.constraints.safety.SafetyPlugin
import info.nightscout.plugins.pump.virtual.VirtualPumpPlugin
import info.nightscout.plugins.source.GlimpPlugin
import info.nightscout.shared.sharedPreferences.SP
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`

class SafetyPluginTest : TestBaseWithProfile() {

    @Mock lateinit var sp: SP
    @Mock lateinit var constraintChecker: Constraints
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var virtualPumpPlugin: VirtualPumpPlugin
    @Mock lateinit var glimpPlugin: GlimpPlugin
    @Mock lateinit var profiler: Profiler
    @Mock lateinit var repository: AppRepository
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider

    private lateinit var hardLimits: HardLimits
    private lateinit var safetyPlugin: SafetyPlugin
    private lateinit var openAPSAMAPlugin: OpenAPSAMAPlugin
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    private val injector = HasAndroidInjector { AndroidInjector { } }
    private val pumpDescription = PumpDescription()

    @Before
    fun prepare() {
        `when`(rh.gs(R.string.hardlimit)).thenReturn("hard limit")
        `when`(rh.gs(R.string.itmustbepositivevalue)).thenReturn("it must be positive value")
        `when`(rh.gs(R.string.pumplimit)).thenReturn("pump limit")
        `when`(rh.gs(R.string.maxvalueinpreferences)).thenReturn("max value in preferences")
        `when`(rh.gs(info.nightscout.plugins.aps.R.string.max_daily_basal_multiplier)).thenReturn("max daily basal multiplier")
        `when`(rh.gs(info.nightscout.plugins.aps.R.string.max_basal_multiplier)).thenReturn("max basal multiplier")
        `when`(rh.gs(R.string.limitingbolus)).thenReturn("Limiting bolus to %1\$.1f U because of %2\$s")
        `when`(rh.gs(R.string.limitingbasalratio)).thenReturn("Limiting max basal rate to %1\$.2f U/h because of %2\$s")
        `when`(rh.gs(R.string.limitingiob)).thenReturn("Limiting IOB to %1\$.1f U because of %2\$s")
        `when`(rh.gs(R.string.limitingcarbs)).thenReturn("Limiting carbs to %1\$d g because of %2\$s")
        `when`(rh.gs(R.string.limitingpercentrate)).thenReturn("Limiting max percent rate to %1\$d%% because of %2\$s")
        `when`(rh.gs(R.string.pumpisnottempbasalcapable)).thenReturn("Pump is not temp basal capable")
        `when`(rh.gs(info.nightscout.plugins.aps.R.string.increasing_max_basal)).thenReturn("Increasing max basal value because setting is lower than your max basal in profile")
        `when`(rh.gs(info.nightscout.plugins.aps.R.string.smb_disabled_in_preferences)).thenReturn("SMB disabled in preferences")
        `when`(rh.gs(R.string.closedmodedisabledinpreferences)).thenReturn("Closed loop mode disabled in preferences")
        `when`(rh.gs(R.string.closed_loop_disabled_on_dev_branch)).thenReturn("Running dev version. Closed loop is disabled.")
        `when`(rh.gs(R.string.smbalwaysdisabled)).thenReturn("SMB always and after carbs disabled because active BG source doesn\\'t support advanced filtering")
        `when`(rh.gs(R.string.smbnotallowedinopenloopmode)).thenReturn("SMB not allowed in open loop mode")
        `when`(rh.gs(R.string.key_child)).thenReturn("child")
        `when`(rh.gs(R.string.lowglucosesuspend)).thenReturn("Low Glucose Suspend")

        `when`(activePlugin.activePump).thenReturn(virtualPumpPlugin)
        `when`(virtualPumpPlugin.pumpDescription).thenReturn(pumpDescription)
        `when`(config.APS).thenReturn(true)
        hardLimits = HardLimitsMock(sp, rh)
        safetyPlugin = SafetyPlugin(injector, aapsLogger, rh, sp, rxBus, constraintChecker, activePlugin, hardLimits, config, iobCobCalculator, dateUtil)
        openAPSAMAPlugin = OpenAPSAMAPlugin(
            injector, aapsLogger, rxBus, constraintChecker, rh, profileFunction, context, activePlugin, iobCobCalculator, hardLimits, profiler, fabricPrivacy,
            dateUtil, repository, glucoseStatusProvider, sp
        )
        openAPSSMBPlugin = OpenAPSSMBPlugin(
            injector, aapsLogger, rxBus, constraintChecker, rh, profileFunction, context, activePlugin, iobCobCalculator, hardLimits, profiler, sp,
            dateUtil, repository, glucoseStatusProvider
        )
    }

    @Test
    fun pumpDescriptionShouldLimitLoopInvocation() {
        pumpDescription.isTempBasalCapable = false
        var c = Constraint(true)
        c = safetyPlugin.isLoopInvocationAllowed(c)
        Assert.assertEquals("Safety: Pump is not temp basal capable", c.getReasons(aapsLogger))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun disabledEngineeringModeShouldLimitClosedLoop() {
        `when`(sp.getString(R.string.key_aps_mode, "open")).thenReturn("closed")
        `when`(config.isEngineeringModeOrRelease()).thenReturn(false)
        var c = Constraint(true)
        c = safetyPlugin.isClosedLoopAllowed(c)
        Assert.assertTrue(c.getReasons(aapsLogger).contains("Running dev version. Closed loop is disabled."))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun setOpenLoopInPreferencesShouldLimitClosedLoop() {
        `when`(sp.getString(R.string.key_aps_mode, "open")).thenReturn("open")
        var c = Constraint(true)
        c = safetyPlugin.isClosedLoopAllowed(c)
        Assert.assertTrue(c.getReasons(aapsLogger).contains("Closed loop mode disabled in preferences"))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun notEnabledSMBInPreferencesDisablesSMB() {
        `when`(sp.getBoolean(info.nightscout.plugins.aps.R.string.key_use_smb, false)).thenReturn(false)
        `when`(constraintChecker.isClosedLoopAllowed(anyObject())).thenReturn(Constraint(true))
        var c = Constraint(true)
        c = openAPSSMBPlugin.isSMBModeEnabled(c)
        Assert.assertTrue(c.getReasons(aapsLogger).contains("SMB disabled in preferences"))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun openLoopPreventsSMB() {
        `when`(sp.getBoolean(info.nightscout.plugins.aps.R.string.key_use_smb, false)).thenReturn(true)
        `when`(constraintChecker.isClosedLoopAllowed(anyObject())).thenReturn(Constraint(false))
        var c = Constraint(true)
        c = safetyPlugin.isSMBModeEnabled(c)
        Assert.assertTrue(c.getReasons(aapsLogger).contains("SMB not allowed in open loop mode"))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun bgSourceShouldPreventSMBAlways() {
        `when`(activePlugin.activeBgSource).thenReturn(glimpPlugin)
        var c = Constraint(true)
        c = safetyPlugin.isAdvancedFilteringEnabled(c)
        Assert.assertEquals("Safety: SMB always and after carbs disabled because active BG source doesn\\'t support advanced filtering", c.getReasons(aapsLogger))
        Assert.assertEquals(false, c.value())
    }

    @Test
    fun basalRateShouldBeLimited() {
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsma_max_basal, 1.0)).thenReturn(1.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_current_basal_safety_multiplier, 4.0)).thenReturn(4.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_max_daily_safety_multiplier, 3.0)).thenReturn(3.0)
        `when`(sp.getString(info.nightscout.plugins.aps.R.string.key_age, "")).thenReturn("child")
        val c = Constraint(Constants.REALLYHIGHBASALRATE)
        safetyPlugin.applyBasalConstraints(c, validProfile)
        Assert.assertEquals(2.0, c.value(), 0.01)
        Assert.assertEquals(
            """
    Safety: Limiting max basal rate to 2.00 U/h because of hard limit
    """.trimIndent(), c.getReasons(aapsLogger)
        )
        Assert.assertEquals("Safety: Limiting max basal rate to 2.00 U/h because of hard limit", c.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun doNotAllowNegativeBasalRate() {
        `when`(sp.getString(R.string.key_age, "")).thenReturn("child")
        val d = Constraint(-0.5)
        safetyPlugin.applyBasalConstraints(d, validProfile)
        Assert.assertEquals(0.0, d.value(), 0.01)
        Assert.assertEquals("Safety: Limiting max basal rate to 0.00 U/h because of it must be positive value", d.getReasons(aapsLogger))
    }

    @Test
    fun percentBasalRateShouldBeLimited() {
        // No limit by default
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsma_max_basal, 1.0)).thenReturn(1.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_current_basal_safety_multiplier, 4.0)).thenReturn(4.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_max_daily_safety_multiplier, 3.0)).thenReturn(3.0)
        `when`(sp.getString(info.nightscout.plugins.aps.R.string.key_age, "")).thenReturn("child")
        val i = Constraint(Constants.REALLYHIGHPERCENTBASALRATE)
        safetyPlugin.applyBasalPercentConstraints(i, validProfile)
        Assert.assertEquals(200, i.value())
        Assert.assertEquals(
            """
Safety: Percent rate 1111111% recalculated to 11111.11 U/h with current basal 1.00 U/h
Safety: Limiting max basal rate to 2.00 U/h because of hard limit
Safety: Limiting max percent rate to 200% because of pump limit
Safety: Limiting max basal rate to 500.00 U/h because of pump limit
    """.trimIndent(), i.getReasons(aapsLogger)
        )
        Assert.assertEquals("Safety: Limiting max percent rate to 200% because of pump limit", i.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun percentBasalShouldBeLimitedBySMB() {
        // No limit by default
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsma_max_basal, 1.0)).thenReturn(1.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_current_basal_safety_multiplier, 4.0)).thenReturn(4.0)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsama_max_daily_safety_multiplier, 3.0)).thenReturn(3.0)
        `when`(sp.getString(info.nightscout.plugins.aps.R.string.key_age, "")).thenReturn("child")
        openAPSSMBPlugin.setPluginEnabled(PluginType.APS, true)
        val i = Constraint(Constants.REALLYHIGHBASALRATE)
        openAPSSMBPlugin.applyBasalConstraints(i, validProfile)
        Assert.assertEquals(1.0, i.value(), 0.01)
        Assert.assertEquals(
            """
            OpenAPSSMB: Limiting max basal rate to 1.00 U/h because of max value in preferences
            OpenAPSSMB: Limiting max basal rate to 4.00 U/h because of max basal multiplier
            OpenAPSSMB: Limiting max basal rate to 3.00 U/h because of max daily basal multiplier
            """.trimIndent(), i.getReasons(aapsLogger)
        )
        Assert.assertEquals("OpenAPSSMB: Limiting max basal rate to 1.00 U/h because of max value in preferences", i.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun doNotAllowNegativePercentBasalRate() {
        `when`(sp.getString(R.string.key_age, "")).thenReturn("child")
        val i = Constraint(-22)
        safetyPlugin.applyBasalPercentConstraints(i, validProfile)
        Assert.assertEquals(0, i.value())
        Assert.assertEquals(
            """
    Safety: Percent rate -22% recalculated to -0.22 U/h with current basal 1.00 U/h
    Safety: Limiting max basal rate to 0.00 U/h because of it must be positive value
    Safety: Limiting max percent rate to 0% because of pump limit
    """.trimIndent(), i.getReasons(aapsLogger)
        )
        Assert.assertEquals("Safety: Limiting max percent rate to 0% because of pump limit", i.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun bolusAmountShouldBeLimited() {
        `when`(sp.getDouble(R.string.key_treatmentssafety_maxbolus, 3.0)).thenReturn(3.0)
        `when`(sp.getString(R.string.key_age, "")).thenReturn("child")
        var d = Constraint(Constants.REALLYHIGHBOLUS)
        d = safetyPlugin.applyBolusConstraints(d)
        Assert.assertEquals(3.0, d.value(), 0.01)
        Assert.assertEquals(
            """
    Safety: Limiting bolus to 3.0 U because of max value in preferences
    Safety: Limiting bolus to 5.0 U because of hard limit
    """.trimIndent(), d.getReasons(aapsLogger)
        )
        Assert.assertEquals("Safety: Limiting bolus to 3.0 U because of max value in preferences", d.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun doNotAllowNegativeBolusAmount() {
        `when`(sp.getDouble(R.string.key_treatmentssafety_maxbolus, 3.0)).thenReturn(3.0)
        `when`(sp.getString(R.string.key_age, "")).thenReturn("child")
        var d = Constraint(-22.0)
        d = safetyPlugin.applyBolusConstraints(d)
        Assert.assertEquals(0.0, d.value(), 0.01)
        Assert.assertEquals("Safety: Limiting bolus to 0.0 U because of it must be positive value", d.getReasons(aapsLogger))
        Assert.assertEquals("Safety: Limiting bolus to 0.0 U because of it must be positive value", d.getMostLimitedReasons(aapsLogger))
    }

    @Test
    fun carbsAmountShouldBeLimited() {
        // No limit by default
        `when`(sp.getInt(R.string.key_treatmentssafety_maxcarbs, 48)).thenReturn(48)

        // Negative carbs not allowed
        var i = Constraint(-22)
        safetyPlugin.applyCarbsConstraints(i)
        Assert.assertEquals(0, i.value())
        Assert.assertEquals("Safety: Limiting carbs to 0 g because of it must be positive value", i.getReasons(aapsLogger))

        // Apply all limits
        i = safetyPlugin.applyCarbsConstraints(Constraint(Constants.REALLYHIGHCARBS))
        Assert.assertEquals(48, i.value())
        Assert.assertEquals("Safety: Limiting carbs to 48 g because of max value in preferences", i.getReasons(aapsLogger))
    }

    @Test
    fun iobShouldBeLimited() {
        openAPSAMAPlugin.setPluginEnabled(PluginType.APS, true)
        openAPSSMBPlugin.setPluginEnabled(PluginType.APS, true)
        //`when`(openAPSSMBPlugin.isEnabled()).thenReturn(true)
        //`when`(openAPSAMAPlugin.isEnabled()).thenReturn(false)
        `when`(sp.getString(R.string.key_aps_mode, "open")).thenReturn("lgs")
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapsma_max_iob, 1.5)).thenReturn(1.5)
        `when`(sp.getDouble(info.nightscout.plugins.aps.R.string.key_openapssmb_max_iob, 3.0)).thenReturn(3.0)
        `when`(sp.getString(R.string.key_age, "")).thenReturn("teenage")

        // Apply all limits
        var d = Constraint(Constants.REALLYHIGHIOB)
        d = safetyPlugin.applyMaxIOBConstraints(d)
        Assert.assertEquals(HardLimits.MAX_IOB_LGS, d.value(), 0.01)
        Assert.assertEquals("Safety: Limiting IOB to 0.0 U because of Low Glucose Suspend", d.getReasons(aapsLogger))
        Assert.assertEquals("Safety: Limiting IOB to 0.0 U because of Low Glucose Suspend", d.getMostLimitedReasons(aapsLogger))

        // Apply all limits
        d = Constraint(Constants.REALLYHIGHIOB)
        val a = openAPSAMAPlugin.applyMaxIOBConstraints(d)
        Assert.assertEquals(1.5, a.value(), 0.01)
        Assert.assertEquals("OpenAPSAMA: Limiting IOB to 1.5 U because of max value in preferences\nOpenAPSAMA: Limiting IOB to 7.0 U because of hard limit", d.getReasons(aapsLogger))
        Assert.assertEquals("OpenAPSAMA: Limiting IOB to 1.5 U because of max value in preferences", d.getMostLimitedReasons(aapsLogger))

        // Apply all limits
        d = Constraint(Constants.REALLYHIGHIOB)
        val s = openAPSSMBPlugin.applyMaxIOBConstraints(d)
        Assert.assertEquals(3.0, s.value(), 0.01)
        Assert.assertEquals("OpenAPSSMB: Limiting IOB to 3.0 U because of max value in preferences\nOpenAPSSMB: Limiting IOB to 22.0 U because of hard limit", d.getReasons(aapsLogger))
        Assert.assertEquals("OpenAPSSMB: Limiting IOB to 3.0 U because of max value in preferences", d.getMostLimitedReasons(aapsLogger))
    }
}